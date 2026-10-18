package com.photopipeline.app.auth

import android.app.Activity
import android.content.Intent
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.photopipeline.app.core.PipelineLog

/**
 * Delivers Google Identity authorization results to the active [GoogleSignInHelper].
 * Needed when consent returns via [Activity.onNewIntent] (singleTop) instead of IntentSender callback.
 */
object GoogleAuthCoordinator {
    private var handler: ((Int, Intent?) -> Unit)? = null
    private var pending: Pair<Int, Intent?>? = null

    fun bind(deliver: (Int, Intent?) -> Unit) {
        handler = deliver
        PipelineLog.i("auth", "coordinator bind handler active")
        pending?.let { (code, data) ->
            PipelineLog.i("auth", "coordinator flushing pending result code=$code")
            pending = null
            deliver(code, data)
        }
    }

    fun unbind() {
        PipelineLog.i("auth", "coordinator unbind")
        handler = null
    }

    fun deliverActivityResult(resultCode: Int, data: Intent?) {
        PipelineLog.i(
            "auth",
            "coordinator deliverActivityResult resultCode=$resultCode dataPresent=${data != null}",
        )
        val h = handler
        if (h != null) {
            h(resultCode, data)
        } else {
            PipelineLog.i("auth", "coordinator queueing result (no handler yet)")
            pending = resultCode to data
        }
    }

    /** Call from [Activity.onCreate] / [Activity.onNewIntent] when the activity may carry GIS result extras. */
    fun tryCompleteFromIntent(activity: Activity, intent: Intent?) {
        if (intent == null) return
        try {
            Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(intent)
            PipelineLog.i("auth", "coordinator parsed authorization result from activity intent")
            deliverActivityResult(Activity.RESULT_OK, intent)
        } catch (_: ApiException) {
            // Not a Google authorization intent.
        } catch (_: IllegalArgumentException) {
            // Not a Google authorization intent.
        }
    }
}
