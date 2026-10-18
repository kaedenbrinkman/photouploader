# Google OAuth clients for Photo Pipeline (Android + backend)

The app uses **Google Identity Services** `AuthorizationClient` with **`requestOfflineAccess(WEB_CLIENT_ID)`** to obtain a **server auth code**. The app posts it to `POST /auth/google`; the backend exchanges it with the **Web application** client id + secret (`redirect_uri` empty string), validates the ID token audience (or calls userinfo), stores the refresh token per Google **`sub`** in SSM, and returns a session JWT.

There is **no Cognito**, **no AppAuth**, and **no custom-scheme redirect URI** on the Web client.

## 1. Google Cloud project setup

1. Enable **Photos Library API**.
2. OAuth consent screen: External, test users, scopes:
   - `openid`, `email`, `profile`
   - `https://www.googleapis.com/auth/photoslibrary.appendonly`
   - `https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata`

## 2. OAuth clients to create

### A. Web application (required — backend + offline access)

| Field | Value |
|--------|--------|
| Type | **Web application** |
| Authorized redirect URIs | **None required** for server auth code from Android GIS (backend exchange uses `redirect_uri=` empty) |

Download JSON as `client_secret.json` → SSM:

```bash
aws ssm put-parameter \
  --name /photopipeline/google-oauth-client \
  --type SecureString \
  --value file://client_secret.json \
  --overwrite
```

Copy the **Web client ID** into `AppDefaults.GOOGLE_WEB_CLIENT_ID` (or Settings).

### B. Android (required — authorizes the APK)

| Field | Value |
|--------|--------|
| Type | **Android** |
| Package name | **`com.photopipeline.app`** |
| SHA-1 | **`DB:24:AC:E6:E9:43:CB:D1:1D:BD:7C:CB:DF:DD:A2:01:C0:01:CB:44`** (stable debug keystore at `android/keystore/debug.keystore`) |

You do **not** put the Android client ID in the app.

## 3. Backend session secret

```bash
aws ssm put-parameter \
  --name /photopipeline/session-jwt-secret \
  --type SecureString \
  --value "$(openssl rand -base64 32)" \
  --overwrite
```

## 4. App defaults (overridable in Settings)

| Setting | Default |
|---------|---------|
| API base URL | `https://3jamg4s1j9.execute-api.us-west-2.amazonaws.com` |
| AWS region | `us-west-2` |
| Web client ID | `AppDefaults.GOOGLE_WEB_CLIENT_ID` |

## 5. API flow

1. User taps **Connect with Google** → GIS authorization + offline access.
2. App `POST /auth/google` with `{ "serverAuthCode": "..." }`.
3. Backend returns `{ sessionToken, sub, email, expiresIn }`.
4. App uses `Authorization: Bearer <sessionToken>` on `/shoots/*`.

Refresh tokens: SSM `/photopipeline/users/<google-sub>/gphotos-token`.
