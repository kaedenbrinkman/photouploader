# Google Photos uploader setup (not executed in this investigation)
1. Google Cloud console: create a project; enable **Photos Library API**.
2. OAuth consent screen: User type *External*; add yourself as a test user; add scopes
   `photoslibrary.appendonly` and `photoslibrary.readonly.appcreateddata`.
   - While publishing status is **Testing**, refresh tokens expire after **7 days** (Google OAuth docs).
     For an unattended worker, either re-auth weekly or move the app to **In production**; Photos scopes
     require OAuth verification ("unverified app" screen until verified; for personal use with <100 users
     you can click through, but that does not lift the Testing 7-day limit).
3. Create an OAuth client of type **Desktop app**; download `client_secret.json`.
4. `python -m venv .venv && .venv/bin/pip install requests google-auth-oauthlib`
5. First run on a machine with a browser: `.venv/bin/python gphotos_upload.py --client-secret client_secret.json --token token.json --album "Test" --ledger ledger.json test.jpg`
6. Store `token.json` (refresh token) in AWS Secrets Manager/SSM SecureString (KMS); worker fetches at start, writes back if rotated. Never log it.
Use a non-personal test JPEG for the first run.

For this pipeline, the **Android app** uses **Connect with Google** (authorization code + PKCE). The app calls `POST /auth/google`; the backend stores the refresh token per Google `sub` in SSM. See **`GOOGLE_OAUTH_ANDROID.md`** for Web/Android client types, redirect URI, and SHA-1.
