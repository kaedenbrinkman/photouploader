package com.photopipeline.app.core

object AppDefaults {
    const val API_BASE_URL = "https://3jamg4s1j9.execute-api.us-west-2.amazonaws.com"
    const val AWS_REGION = "us-west-2"
    /**
     * Web application OAuth client ID (server client ID for offline access / serverAuthCode).
     * Override in Settings. Android OAuth client (package + SHA-1) authorizes the APK only.
     */
    const val GOOGLE_WEB_CLIENT_ID = ""
}
