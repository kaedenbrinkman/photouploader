package com.photopipeline.app.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import com.photopipeline.app.core.AppDefaults
import com.photopipeline.app.core.PipelineLog
import com.photopipeline.app.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

class GoogleSignInHelper(
    private val activity: Activity,
    private val repository: Repository,
    private val scope: CoroutineScope,
    private val onAuthFinished: () -> Unit = {},
) {
    private val authService = AuthorizationService(activity)
    private val redirectUri = Uri.parse(AppDefaults.GOOGLE_REDIRECT_URI)

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token"),
    )

    fun start(launcher: ActivityResultLauncher<Intent>) {
        val clientId = repository.googleWebClientId()
        if (clientId.isBlank()) {
            PipelineLog.w("auth", "Google Web client ID not configured")
            return
        }
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            redirectUri,
        )
            .setScopes(
                "openid",
                "email",
                "profile",
                "https://www.googleapis.com/auth/photoslibrary.appendonly",
                "https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata",
            )
            .build()
        launcher.launch(authService.getAuthorizationRequestIntent(request))
    }

    fun onActivityResult(data: Intent?) {
        if (data == null) {
            PipelineLog.w("auth", "Google authorization returned no intent data")
            return
        }
        val response = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        if (ex != null) {
            PipelineLog.w("auth", "Google authorization failed: ${ex.errorDescription}", ex)
            return
        }
        if (response == null) {
            PipelineLog.w("auth", "Google authorization returned no response")
            return
        }
        val code = response.authorizationCode ?: return
        val verifier = response.request.codeVerifier ?: return
        scope.launch {
            runCatching {
                repository.completeGoogleSignIn(code, verifier, AppDefaults.GOOGLE_REDIRECT_URI)
            }.onSuccess {
                onAuthFinished()
            }.onFailure { err ->
                PipelineLog.w("auth", "Backend session exchange failed: ${err.message}", err)
            }
        }
    }

    fun dispose() {
        authService.dispose()
    }
}
