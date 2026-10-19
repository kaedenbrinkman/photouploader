import { BatchWriteCommand, DynamoDBDocumentClient, QueryCommand } from '@aws-sdk/lib-dynamodb';

function isStatelessFileItem(item: Record<string, unknown>): boolean {
  const st = item.state;
  if (st == null) return true;
  if (typeof st === 'string' && st.trim() === '') return true;
  return false;
}

/** Delete all FILE# rows without a state (legacy content-hash placeholders), paginated. */
export async function deleteStatelessFilePlaceholders(
  ddb: DynamoDBDocumentClient,
  tableName: string,
  shootId: string,
): Promise<number> {
  const pk = `SHOOT#${shootId}`;
  let deleted = 0;
  let startKey: Record<string, unknown> | undefined;
  do {
    const res = await ddb.send(
      new QueryCommand({
        TableName: tableName,
        KeyConditionExpression: 'pk = :pk AND begins_with(sk, :f)',
        ExpressionAttributeValues: { ':pk': pk, ':f': 'FILE#' },
        ExclusiveStartKey: startKey,
      }),
    );
    const toDelete = (res.Items ?? []).filter((it) => isStatelessFileItem(it as Record<string, unknown>));
    for (let i = 0; i < toDelete.length; i += 25) {
      const chunk = toDelete.slice(i, i + 25);
      if (!chunk.length) continue;
      await ddb.send(
        new BatchWriteCommand({
          RequestItems: {
            [tableName]: chunk.map((it) => ({
              DeleteRequest: { Key: { pk: it.pk, sk: it.sk } },
            })),
          },
        }),
      );
      deleted += chunk.length;
    }
    startKey = res.LastEvaluatedKey;
  } while (startKey);
  return deleted;
}
