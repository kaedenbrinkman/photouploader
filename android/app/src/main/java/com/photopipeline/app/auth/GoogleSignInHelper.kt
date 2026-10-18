package com.photopipeline.app.auth

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.photopipeline.app.core.PipelineLog
import com.photopipeline.app.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class GoogleSignInHelper(
    private val activity: Activity,
    private val repository: Repository,
    private val scope: CoroutineScope,
    private val authorizationLauncher: ActivityResultLauncher<IntentSenderRequest>,
    private val onAuthFinished: () -> Unit = {},
) {
    fun start() {
        val webClientId = repository.googleWebClientId()
        if (webClientId.isBlank()) {
            PipelineLog.w("auth", "Google Web client ID not configured")
            return
        }
        val requestedScopes = listOf(
            Scope("openid"),
            Scope("email"),
            Scope("profile"),
            Scope("https://www.googleapis.com/auth/photoslibrary.appendonly"),
            Scope("https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata"),
        )
        val authorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)
            .requestOfflineAccess(webClientId)
            .build()

        Identity.getAuthorizationClient(activity)
            .authorize(authorizationRequest)
            .addOnSuccessListener { authorizationResult ->
                if (authorizationResult.hasResolution()) {
                    val pending = authorizationResult.pendingIntent
                    if (pending == null) {
                        PipelineLog.w("auth", "Authorization resolution missing pending intent")
                        return@addOnSuccessListener
                    }
                    authorizationLauncher.launch(
                        IntentSenderRequest.Builder(pending.intentSender).build(),
                    )
                } else {
                    exchangeServerAuthCode(authorizationResult.serverAuthCode)
                }
            }
            .addOnFailureListener { e ->
                PipelineLog.w("auth", "Google authorization failed: ${e.message}", e)
            }
    }

    fun onAuthorizationResult(data: Intent?) {
        if (data == null) {
            PipelineLog.w("auth", "Google authorization returned no intent data")
            return
        }
        try {
            val authorizationResult =
                Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
            exchangeServerAuthCode(authorizationResult.serverAuthCode)
        } catch (e: ApiException) {
            PipelineLog.w("auth", "Google authorization result failed: ${e.message}", e)
        }
    }

    private fun exchangeServerAuthCode(serverAuthCode: String?) {
        val code = serverAuthCode?.trim().orEmpty()
        if (code.isEmpty()) {
            PipelineLog.w("auth", "Google authorization returned no server auth code")
            return
        }
        scope.launch {
            runCatching {
                repository.completeGoogleSignIn(code)
            }.onSuccess {
                onAuthFinished()
            }.onFailure { err ->
                PipelineLog.w("auth", "Backend session exchange failed: ${err.message}", err)
            }
        }
    }
}
