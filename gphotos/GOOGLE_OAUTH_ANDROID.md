# Google OAuth clients for Photo Pipeline (Android + backend)

The app uses **authorization code + PKCE** (Custom Tabs via AppAuth). The **backend** exchanges the code (with the Web client **secret**), stores the Google **refresh token** per user `sub` in SSM, and returns a **session JWT** for API calls. There is **no Cognito** and no username/password sign-up.

## 1. Google Cloud project setup

1. Enable **Photos Library API**.
2. OAuth consent screen: External, add yourself as a test user, scopes:
   - `openid`, `email`, `profile`
   - `https://www.googleapis.com/auth/photoslibrary.appendonly`
   - `https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata`

## 2. OAuth clients to create

### A. Web application (required)

Used by the Android app for the authorization request (`client_id`) and by the backend to exchange the auth code (needs **client secret**).

| Field | Value |
|--------|--------|
| Type | **Web application** |
| Authorized redirect URIs | **`com.photopipeline.app:/oauth2redirect`** (exactly; custom scheme redirect for AppAuth) |

Download JSON as `client_secret.json` and store in AWS SSM:

```bash
aws ssm put-parameter \
  --name /photopipeline/google-oauth-client \
  --type SecureString \
  --value file://client_secret.json \
  --overwrite
```

Copy the **Client ID** (ends in `.apps.googleusercontent.com`) into the Android app **Settings → Google Web OAuth client ID** (or set `AppDefaults.GOOGLE_WEB_CLIENT_ID` at build time).

### B. Android (required by Google for native apps)

| Field | Value |
|--------|--------|
| Type | **Android** |
| Package name | **`com.photopipeline.app`** |
| SHA-1 certificate fingerprint | **`DB:24:AC:E6:E9:43:CB:D1:1D:BD:7C:CB:DF:DD:A2:01:C0:01:CB:44`** |

This is the **stable debug keystore** at `android/keystore/debug.keystore` (CI and local debug builds). For Play Store release, add a second Android client (or additional fingerprint) with your **release** keystore SHA-1.

The Android client ID is **not** entered in the app; it links your APK signing cert to the same Google Cloud project as the Web client.

## 3. Backend session secret (required)

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
| Redirect URI | `com.photopipeline.app:/oauth2redirect` |

## 5. API flow

1. User taps **Connect with Google** → Google sign-in (PKCE).
2. App `POST /auth/google` with `{ code, codeVerifier, redirectUri }` (no auth header).
3. Backend returns `{ sessionToken, sub, email, expiresIn }`.
4. App sends `Authorization: Bearer <sessionToken>` on `/shoots/*` routes.

Refresh tokens live at SSM `/photopipeline/users/<google-sub>/gphotos-token` for the worker.
