package com.photopipeline.app.core

object AppDefaults {
    const val API_BASE_URL = "https://3jamg4s1j9.execute-api.us-west-2.amazonaws.com"
    const val AWS_REGION = "us-west-2"
    /** Must match Web OAuth client authorized redirect URI in Google Cloud. */
    const val GOOGLE_REDIRECT_URI = "com.photopipeline.app:/oauth2redirect"
    /**
     * Web application OAuth client ID (not Android client ID). Override in Settings after you create
     * clients in Google Cloud — see gphotos/GOOGLE_OAUTH_ANDROID.md.
     */
    const val GOOGLE_WEB_CLIENT_ID = ""
}
