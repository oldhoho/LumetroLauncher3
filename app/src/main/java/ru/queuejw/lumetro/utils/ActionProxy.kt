package ru.queuejw.lumetro.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.queuejw.lumetro.gesture.config.GestureActionType

class ActionProxy(
    private val context: Context,
    private val linkString: String,
) {
    suspend fun execute(
        anchor: Any? = null,
        side: String? = null,
        sourceTriggerTag: String? = null,
    ) {
        executeLink(side = side)
    }

    suspend fun executeLink(
        anchor: Any? = null,
        side: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(linkString))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    companion object {
        fun buildFreeformActivityOptions(
            context: Context,
            side: String? = null,
        ): android.app.ActivityOptions = android.app.ActivityOptions.makeBasic()

        fun startNubiaFreeformActivity(activity: android.app.Activity, intent: Intent) {
            activity.startActivity(intent)
        }
    }
}

fun uriBuilder(type: GestureActionType): Uri.Builder =
    Uri.parse("https://www.google.com/search").buildUpon()
