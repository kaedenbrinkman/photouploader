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
    private val onAuthError: (String) -> Unit = {},
) {
    @Volatile
    private var exchangeInFlight = false
    fun bindCoordinator() {
        GoogleAuthCoordinator.bind { resultCode, data ->
            deliverAuthorizationResult(resultCode, data)
        }
    }

    fun unbindCoordinator() {
        GoogleAuthCoordinator.unbind()
    }

    fun start() {
        exchangeInFlight = false
        onAuthError("") // clear
        val webClientId = repository.googleWebClientId()
        PipelineLog.i("auth", "start connect webClientIdConfigured=${webClientId.isNotBlank()}")
        if (webClientId.isBlank()) {
            val msg = "Google Web client ID not configured"
            PipelineLog.i("auth", "start aborted: $msg")
            onAuthError(msg)
            return
        }
        val requestedScopes = listOf(
            Scope("openid"),
            Scope("email"),
            Scope("profile"),
            Scope("https://www.googleapis.com/auth/photoslibrary.appendonly"),
            Scope("https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata",
            ),
        )
        val authorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)
            .requestOfflineAccess(webClientId)
            .build()
        PipelineLog.i("auth", "authorization request built offlineAccess=true scopes=${requestedScopes.size}")

        Identity.getAuthorizationClient(activity)
            .authorize(authorizationRequest)
            .addOnSuccessListener { authorizationResult ->
                val hasResolution = authorizationResult.hasResolution()
                PipelineLog.i("auth", "authorize onSuccess hasResolution=$hasResolution")
                if (hasResolution) {
                    val pending = authorizationResult.pendingIntent
                    if (pending == null) {
                        val msg = "Authorization resolution missing pending intent"
                        PipelineLog.i("auth", msg)
                        onAuthError(msg)
                        return@addOnSuccessListener
                    }
                    PipelineLog.i("auth", "launching authorization pending intent")
                    authorizationLauncher.launch(
                        IntentSenderRequest.Builder(pending.intentSender).build(),
                    )
                } else {
                    val hasCode = !authorizationResult.serverAuthCode.isNullOrBlank()
                    PipelineLog.i("auth", "immediate result serverAuthCodePresent=$hasCode")
                    exchangeServerAuthCode(
                        authorizationResult.serverAuthCode,
                        authorizationResult.accessToken,
                    )
                }
            }
            .addOnFailureListener { e ->
                val msg = e.message ?: "Google authorization failed"
                PipelineLog.i("auth", "authorize onFailure: $msg")
                PipelineLog.w("auth", msg, e)
                onAuthError(msg)
            }
    }

    fun deliverAuthorizationResult(resultCode: Int, data: Intent?) {
        PipelineLog.i(
            "auth",
            "deliverAuthorizationResult resultCode=$resultCode dataPresent=${data != null}",
        )
        if (resultCode != Activity.RESULT_OK) {
            val msg = "Google sign-in canceled (result $resultCode)"
            PipelineLog.i("auth", msg)
            onAuthError(msg)
            return
        }
        onAuthorizationResult(data)
    }

    private fun onAuthorizationResult(data: Intent?) {
        if (data == null) {
            val msg = "Google authorization returned no intent data"
            PipelineLog.i("auth", msg)
            onAuthError(msg)
            return
        }
        try {
            val authorizationResult =
                Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
            val hasCode = !authorizationResult.serverAuthCode.isNullOrBlank()
            PipelineLog.i("auth", "parsed authorization result serverAuthCodePresent=$hasCode")
            exchangeServerAuthCode(
                authorizationResult.serverAuthCode,
                authorizationResult.accessToken,
            )
        } catch (e: ApiException) {
            val msg = e.message ?: "Google authorization result failed"
            PipelineLog.i("auth", "getAuthorizationResultFromIntent failed: $msg")
            PipelineLog.w("auth", msg, e)
            onAuthError(msg)
        }
    }

    fun signOut() {
        exchangeInFlight = false
        onAuthError("")
        repository.signOutGoogle()
    }

    private fun exchangeServerAuthCode(serverAuthCode: String?, googleAccessToken: String? = null) {
        val code = serverAuthCode?.trim().orEmpty()
        val hasCode = code.isNotEmpty()
        PipelineLog.i("auth", "exchangeServerAuthCode serverAuthCodePresent=$hasCode")
        if (!hasCode) {
            val msg = "Google did not return a server auth code (check Web client ID + Android client SHA-1)"
            PipelineLog.i("auth", msg)
            onAuthError(msg)
            return
        }
        if (exchangeInFlight) {
            PipelineLog.i("auth", "exchange already in flight, ignoring duplicate callback")
            return
        }
        exchangeInFlight = true
        scope.launch {
            try {
                runCatching {
                    PipelineLog.i("auth", "POST /auth/google starting")
                    repository.completeGoogleSignIn(code, googleAccessToken)
                }.onSuccess {
                    PipelineLog.i("auth", "session saved linked=${repository.isGoogleAccountLinked()}")
                    onAuthError("")
                    onAuthFinished()
                }.onFailure { err ->
                    val msg = err.message ?: "Backend session exchange failed"
                    PipelineLog.i("auth", "POST /auth/google failed: $msg")
                    PipelineLog.w("auth", msg, err)
                    onAuthError(msg)
                }
            } finally {
                exchangeInFlight = false
            }
        }
    }
}
