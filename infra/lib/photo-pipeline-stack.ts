import * as cdk from 'aws-cdk-lib';
import * as apigwv2 from 'aws-cdk-lib/aws-apigatewayv2';
import * as apigwIntegrations from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import * as authorizers from 'aws-cdk-lib/aws-apigatewayv2-authorizers';
import * as budgets from 'aws-cdk-lib/aws-budgets';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as events from 'aws-cdk-lib/aws-events';
import * as targets from 'aws-cdk-lib/aws-events-targets';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as lambdaNode from 'aws-cdk-lib/aws-lambda-nodejs';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import * as path from 'path';

/**
 * Idle-cost design: no NAT/VPC endpoints/custom KMS/Secrets Manager/REST API/always-on EC2.
 * Workers launch in a default-VPC public subnet (parameterized); security group has no inbound rules.
 */
export class PhotoPipelineStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    const rawRetentionDays = new cdk.CfnParameter(this, 'RawDeepArchiveAfterDays', {
      type: 'Number',
      default: 30,
      description: 'Days in Standard before transitioning RAW objects to Deep Archive',
    });

    const workerSubnetId = new cdk.CfnParameter(this, 'WorkerPublicSubnetId', {
      type: 'String',
      default: 'subnet-PLACEHOLDER',
      description: 'Default VPC public subnet ID for ephemeral workers (no NAT required)',
    });

    const workerVpcId = new cdk.CfnParameter(this, 'WorkerVpcId', {
      type: 'String',
      default: 'vpc-PLACEHOLDER',
      description: 'Default VPC ID for worker security group',
    });

    const bucket = new s3.Bucket(this, 'ShootsBucket', {
      bucketName: cdk.PhysicalName.GENERATE_IF_NEEDED,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
      versioned: false,
      /** No cost until accelerated PUT/GET; presign Lambda uses s3-accelerate when requested. */
      transferAcceleration: true,
      lifecycleRules: [
        {
          id: 'raw-deep-archive',
          prefix: 'shoots/',
          transitions: [
            {
              storageClass: s3.StorageClass.DEEP_ARCHIVE,
              transitionAfter: cdk.Duration.days(rawRetentionDays.valueAsNumber),
            },
          ],
        },
      ],
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const table = new dynamodb.Table(this, 'PipelineTable', {
      partitionKey: { name: 'pk', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'sk', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      encryption: dynamodb.TableEncryption.AWS_MANAGED,
      pointInTimeRecovery: false,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const googleOAuthParam = new ssm.StringParameter(this, 'GoogleOAuthClientParam', {
      parameterName: '/photopipeline/google-oauth-client',
      stringValue: JSON.stringify({ placeholder: true, configure: 'paste client_secret.json' }),
      type: ssm.ParameterType.SECURE_STRING,
      description: 'Google OAuth client JSON (Web app type for redirect flow)',
    });

    const userPool = new cognito.UserPool(this, 'PhotoPipelineUserPool', {
      selfSignUpEnabled: false,
      signInAliases: { email: true },
      passwordPolicy: { minLength: 12, requireLowercase: true, requireUppercase: true, requireDigits: true },
      accountRecovery: cognito.AccountRecovery.EMAIL_ONLY,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const userPoolClient = userPool.addClient('AndroidAppClient', {
      authFlows: { userPassword: true, userSrp: true },
      generateSecret: false,
    });

    const vpc = ec2.Vpc.fromVpcAttributes(this, 'DefaultVpc', {
      vpcId: workerVpcId.valueAsString,
      availabilityZones: cdk.Fn.getAzs(),
      publicSubnetIds: [workerSubnetId.valueAsString],
    });

    const workerSecurityGroup = new ec2.SecurityGroup(this, 'WorkerSg', {
      vpc,
      allowAllOutbound: true,
      description: 'Ephemeral LightCraft worker — no inbound access',
    });

    const workerRole = new iam.Role(this, 'WorkerInstanceRole', {
      assumedBy: new iam.ServicePrincipal('ec2.amazonaws.com'),
      description: 'Scoped access for one-shoot EC2 worker',
    });
    bucket.grantReadWrite(workerRole);
    table.grantReadWriteData(workerRole);
    workerRole.addToPolicy(
      new iam.PolicyStatement({
        actions: ['ssm:GetParameter', 'ssm:PutParameter'],
        resources: [`arn:aws:ssm:${this.region}:${this.account}:parameter/photopipeline/users/*`],
      }),
    );

    const workerInstanceProfile = new iam.CfnInstanceProfile(this, 'WorkerInstanceProfile', {
      roles: [workerRole.roleName],
    });

    const workerAmi = ec2.MachineImage.latestAmazonLinux2023({
      cpuType: ec2.AmazonLinuxCpuType.X86_64,
    });

    const lambdaEnv = {
      BUCKET_NAME: bucket.bucketName,
      TABLE_NAME: table.tableName,
      GOOGLE_OAUTH_PARAM: googleOAuthParam.parameterName,
      USER_POOL_ID: userPool.userPoolId,
      CLIENT_ID: userPoolClient.userPoolClientId,
      WORKER_INSTANCE_PROFILE_NAME: workerInstanceProfile.ref,
      WORKER_SECURITY_GROUP_ID: workerSecurityGroup.securityGroupId,
      WORKER_SUBNET_ID: workerSubnetId.valueAsString,
      WORKER_AMI_ID: workerAmi.getImage(this).imageId,
      WORKER_MAX_LIFETIME_MINUTES: '120',
      AWS_ACCOUNT_ID: this.account,
    };

    const lambdaDefaults: lambdaNode.NodejsFunctionProps = {
      runtime: lambda.Runtime.NODEJS_20_X,
      timeout: cdk.Duration.seconds(30),
      memorySize: 256,
      environment: lambdaEnv,
      bundling: { minify: true, sourceMap: true, externalModules: ['@aws-sdk/*'] },
      logRetention: logs.RetentionDays.ONE_WEEK,
    };

    const presignFn = new lambdaNode.NodejsFunction(this, 'PresignFn', {
      ...lambdaDefaults,
      entry: path.join(__dirname, '../lambda/presign.ts'),
    });
    bucket.grantReadWrite(presignFn);
    table.grantReadWriteData(presignFn);

    const finalizeFn = new lambdaNode.NodejsFunction(this, 'FinalizeFn', {
      ...lambdaDefaults,
      entry: path.join(__dirname, '../lambda/finalize.ts'),
      timeout: cdk.Duration.seconds(60),
    });
    bucket.grantReadWrite(finalizeFn);
    table.grantReadWriteData(finalizeFn);
    finalizeFn.addToRolePolicy(
      new iam.PolicyStatement({
        actions: ['ec2:RunInstances', 'ec2:CreateTags', 'ec2:DescribeInstances', 'iam:PassRole'],
        resources: ['*'],
      }),
    );

    const statusFn = new lambdaNode.NodejsFunction(this, 'StatusFn', {
      ...lambdaDefaults,
      entry: path.join(__dirname, '../lambda/status.ts'),
    });
    bucket.grantRead(statusFn);
    table.grantReadData(statusFn);

    const estimateFn = new lambdaNode.NodejsFunction(this, 'EstimateFn', {
      ...lambdaDefaults,
      entry: path.join(__dirname, '../lambda/estimate.ts'),
    });

    const oauthFn = new lambdaNode.NodejsFunction(this, 'OAuthFn', {
      ...lambdaDefaults,
      entry: path.join(__dirname, '../lambda/oauth.ts'),
    });
    googleOAuthParam.grantRead(oauthFn);
    oauthFn.addToRolePolicy(
      new iam.PolicyStatement({
        actions: ['ssm:PutParameter', 'ssm:GetParameter'],
        resources: [`arn:aws:ssm:${this.region}:${this.account}:parameter/photopipeline/users/*`],
      }),
    );
    table.grantReadWriteData(oauthFn);

    const watchdogFn = new lambdaNode.NodejsFunction(this, 'WatchdogFn', {
      ...lambdaDefaults,
      entry: path.join(__dirname, '../lambda/watchdog.ts'),
      timeout: cdk.Duration.seconds(60),
    });
    watchdogFn.addToRolePolicy(
      new iam.PolicyStatement({
        actions: ['ec2:DescribeInstances', 'ec2:TerminateInstances'],
        resources: ['*'],
      }),
    );

    const httpAuthorizer = new authorizers.HttpUserPoolAuthorizer(
      'CognitoJwtAuthorizer',
      userPool,
      { userPoolClients: [userPoolClient], identitySource: ['$request.header.Authorization'] },
    );

    const httpApi = new apigwv2.HttpApi(this, 'PhotoPipelineHttpApi', {
      apiName: 'PhotoPipeline',
      corsPreflight: {
        allowHeaders: ['Authorization', 'Content-Type'],
        allowMethods: [apigwv2.CorsHttpMethod.ANY],
        allowOrigins: ['*'],
      },
    });

    const jwtRoutes = { authorizer: httpAuthorizer };

    httpApi.addRoutes({
      path: '/shoots',
      methods: [apigwv2.HttpMethod.POST],
      integration: new apigwIntegrations.HttpLambdaIntegration('PresignRoot', presignFn),
      ...jwtRoutes,
    });
    httpApi.addRoutes({
      path: '/shoots/estimate',
      methods: [apigwv2.HttpMethod.POST],
      integration: new apigwIntegrations.HttpLambdaIntegration('Estimate', estimateFn),
      ...jwtRoutes,
    });
    httpApi.addRoutes({
      path: '/pricing',
      methods: [apigwv2.HttpMethod.GET],
      integration: new apigwIntegrations.HttpLambdaIntegration('PricingTable', estimateFn),
    });
    httpApi.addRoutes({
      path: '/shoots/{shootId}/presign',
      methods: [apigwv2.HttpMethod.POST],
      integration: new apigwIntegrations.HttpLambdaIntegration('Presign', presignFn),
      ...jwtRoutes,
    });
    httpApi.addRoutes({
      path: '/shoots/{shootId}/finalize',
      methods: [apigwv2.HttpMethod.POST],
      integration: new apigwIntegrations.HttpLambdaIntegration('Finalize', finalizeFn),
      ...jwtRoutes,
    });
    httpApi.addRoutes({
      path: '/shoots/{shootId}/status',
      methods: [apigwv2.HttpMethod.GET],
      integration: new apigwIntegrations.HttpLambdaIntegration('Status', statusFn),
      ...jwtRoutes,
    });
    httpApi.addRoutes({
      path: '/oauth/photos/start',
      methods: [apigwv2.HttpMethod.GET],
      integration: new apigwIntegrations.HttpLambdaIntegration('OAuthStart', oauthFn),
    });
    httpApi.addRoutes({
      path: '/oauth/photos/callback',
      methods: [apigwv2.HttpMethod.GET],
      integration: new apigwIntegrations.HttpLambdaIntegration('OAuthCallback', oauthFn),
    });

    new events.Rule(this, 'WorkerWatchdogSchedule', {
      schedule: events.Schedule.rate(cdk.Duration.hours(1)),
      targets: [new targets.LambdaFunction(watchdogFn)],
    });

    new budgets.CfnBudget(this, 'MonthlyBudget', {
      budget: {
        budgetName: 'photopipeline-monthly',
        budgetLimit: { amount: 25, unit: 'USD' },
        timeUnit: 'MONTHLY',
        budgetType: 'COST',
      },
      notificationsWithSubscribers: [
        {
          notification: { comparisonOperator: 'GREATER_THAN', threshold: 80, notificationType: 'ACTUAL' },
          subscribers: [{ subscriptionType: 'EMAIL', address: 'owner@example.com' }],
        },
      ],
    });

    new cdk.CfnOutput(this, 'HttpApiUrl', { value: httpApi.apiEndpoint });
    new cdk.CfnOutput(this, 'BucketName', { value: bucket.bucketName });
    new cdk.CfnOutput(this, 'UserPoolId', { value: userPool.userPoolId });
    new cdk.CfnOutput(this, 'UserPoolClientId', { value: userPoolClient.userPoolClientId });
    new cdk.CfnOutput(this, 'GoogleOAuthParamName', { value: googleOAuthParam.parameterName });
    new cdk.CfnOutput(this, 'IdleCostNotes', {
      value:
        'Expect ~$0/mo idle: HTTP API/Lambda/DynamoDB/S3/SSM params only charge on use; no NAT/KMS/Secrets/Cognito MAU on basic pool.',
    });
  }
}
