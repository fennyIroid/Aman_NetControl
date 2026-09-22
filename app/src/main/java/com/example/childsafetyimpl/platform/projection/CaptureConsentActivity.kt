package com.example.childsafetyimpl.platform.projection

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The one place screen capture consent is asked for.
 *
 * Android will only show that dialog on top of an Activity, and it will only accept the answer
 * once per session. Without a window of its own, the request can only be made from the video
 * blurring screen, which means a session that ends while the child is inside YouTube can never be
 * restarted from where the parent finds out about it: the notification, the permissions list, or
 * the dashboard.
 *
 * So this Activity exists to be invisible. It opens, asks, hands the answer to the capture
 * service, and closes, leaving the parent wherever they were.
 */
class CaptureConsentActivity : ComponentActivity() {

    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenAnalysisService.start(this, result.resultCode, data)
        } else {
            ScreenAnalysisService.State.setStopped(
                "Screen capture was declined, so video cannot be judged or covered while it " +
                    "plays in another app.",
            )
        }
        finish()
        // No animation: this window is never meant to be seen, and a transition would announce it.
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            ScreenAnalysisService.State.setError("This device does not offer screen capture.")
            finish()
            return
        }

        runCatching { consent.launch(manager.createScreenCaptureIntent()) }
            .onFailure {
                ScreenAnalysisService.State.setError(
                    "Android would not open the screen capture dialog: ${it.message}",
                )
                finish()
            }
    }

    companion object {
        /** Used by the notification that offers to restart a session that ended. */
        fun intent(context: Context): Intent =
            Intent(context, CaptureConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
