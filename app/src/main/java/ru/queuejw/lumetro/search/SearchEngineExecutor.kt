package ru.queuejw.lumetro.search

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.queuejw.lumetro.R
import ru.queuejw.lumetro.gesture.EventBusManager.produceSearchEvent
import ru.queuejw.lumetro.gesture.SearchEvent
import ru.queuejw.lumetro.inspire.InspireDataHolder
import ru.queuejw.lumetro.inspire.ManagedBitmap
import ru.queuejw.lumetro.share.ImageShareLauncher
import ru.queuejw.lumetro.search.config.EngineType
import ru.queuejw.lumetro.search.config.SearchContentMode
import ru.queuejw.lumetro.search.config.SearchEngine
import ru.queuejw.lumetro.utils.ToastHelper
import ru.queuejw.lumetro.utils.app.isActivityExported
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class SearchEngineExecutor(
    val activity: AppCompatActivity,
    val engine: SearchEngine,
    val freeFrom: Boolean,
    val executedCallback: () -> Unit
) {
    private val uiDispatcher = Dispatchers.Main.immediate
    private val ioDispatcher = Dispatchers.IO
    private val imageShareLauncher by lazy { ImageShareLauncher(activity.applicationContext) }

    fun execute(
        query: String = "",
        bitmapHandle: ManagedBitmap? = null,
        imageUris: List<Uri> = emptyList(),
        onSearchStarting: () -> Boolean = { true }
    ) =
        activity.lifecycleScope.launch(uiDispatcher) {
            val triggerSide = InspireDataHolder.triggerSide
            val resolvedMode = engine.resolveContentMode(
                hasText = query.isNotBlank(),
                hasImage = bitmapHandle?.bitmapOrNull != null || imageUris.isNotEmpty()
            )
            val mode = if (resolvedMode == SearchContentMode.ASK) {
                chooseContentMode() ?: return@launch
            } else {
                resolvedMode
            }
            if (!onSearchStarting()) return@launch
            when {
                mode == SearchContentMode.IMAGE -> {
                    if (!executeShareImageSearch(bitmapHandle, imageUris, triggerSide)) {
                        return@launch
                    }
                }

                else -> when (engine.engineType) {
                    EngineType.DIRECT_LINK -> {
                        var searchUrl = engine.searchLink?.replace("%s", Uri.encode(query))
                        searchUrl = searchUrl?.replace("%q", query)
                        if (searchUrl.isNullOrBlank()) return@launch
                        launchIntent(
                            Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                                engine.targetPackage?.takeIf { it.isNotEmpty() }?.let(::setPackage)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }

                    EngineType.EXTERN_JUMP_LINK -> {
                        val pkg = engine.targetPackage ?: ""
                        val activityName = engine.targetActivity ?: ""
                        val externLink = engine.externJumpLink ?: ""
                        if (pkg.isEmpty() || externLink.isEmpty()) {
                            return@launch
                        }
                        launchIntent(
                            Intent(Intent.ACTION_VIEW, Uri.parse(externLink)).apply {
                                engine.externJumpPackage?.takeIf { it.isNotEmpty() }?.let(::setPackage)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                        produceSearchEvent(
                            SearchEvent(
                                InjectionTask(
                                    query = query,
                                    targetPackage = pkg,
                                    targetActivity = activityName,
                                    inputEnter = engine.autoInputEnter
                                )
                            )
                        )
                    }

                    EngineType.JUMP_TO_ACTIVITY -> {
                        val pkg = engine.targetPackage ?: ""
                        val activityName = engine.targetActivity ?: ""
                        if (pkg.isEmpty() || activityName.isEmpty()) {
                            return@launch
                        }
                        launchIntent(
                            Intent().apply {
                                component = ComponentName(pkg, activityName)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                        produceSearchEvent(
                            SearchEvent(
                                InjectionTask(
                                    query = query,
                                    targetPackage = pkg,
                                    targetActivity = activityName,
                                    inputEnter = engine.autoInputEnter
                                )
                            )
                        )
                    }

                    EngineType.SHARE_TO_APP -> {
                        val pkg = engine.targetPackage ?: return@launch
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, query)
                            if (!engine.targetActivity.isNullOrEmpty()) {
                                setClassName(pkg, engine.targetActivity ?: "")
                            } else {
                                setPackage(pkg)
                            }
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        launchIntent(shareIntent)
                    }

                    else -> Unit
                }
            }
            executedAfterSearch()
        }

    private fun launchIntent(intent: Intent) {
        runCatching { activity.startActivity(intent) }
            .onFailure {
                ToastHelper.showInUIThread(
                    activity.applicationContext,
                    activity.getString(R.string.inspire_search_target_app_invalid)
                )
            }
    }

    private suspend fun chooseContentMode(): SearchContentMode? = suspendCancellableCoroutine { continuation ->
        val modes = arrayOf(SearchContentMode.TEXT, SearchContentMode.IMAGE)
        val labels = arrayOf(
            activity.getString(R.string.search_content_mode_text),
            activity.getString(R.string.search_content_mode_image)
        )
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.search_content_mode_title)
            .setItems(labels) { _, which ->
                if (continuation.isActive) continuation.resume(modes[which])
            }
            .setOnDismissListener {
                if (continuation.isActive) continuation.resume(null)
            }
            .create()
        continuation.invokeOnCancellation { dialog.dismiss() }
        if (continuation.isActive) dialog.show()
    }

    private suspend fun executeShareImageSearch(
        bitmapHandle: ManagedBitmap?,
        imageUris: List<Uri>,
        triggerSide: String? = null
    ): Boolean {
        val pkg = engine.imageShareTargetPackage() ?: return false
        val result = when {
            imageUris.isNotEmpty() -> {
                imageShareLauncher.launchForSearchEngine(
                    activity = activity,
                    imageUris = imageUris,
                    targetPackage = pkg,
                    targetActivity = engine.imageShareTargetActivity(),
                    freeform = freeFrom,
                    triggerSide = triggerSide
                )
            }

            bitmapHandle != null -> {
                bitmapHandle.acquire()?.use { handle ->
                    imageShareLauncher.launchForSearchEngine(
                        activity = activity,
                        bitmap = handle.requireBitmap(),
                        targetPackage = pkg,
                        targetActivity = engine.imageShareTargetActivity(),
                        freeform = freeFrom,
                        triggerSide = triggerSide
                    )
                } ?: ImageShareLauncher.LaunchResult.LAUNCH_FAILED
            }

            else -> {
                ToastHelper.showInUIThread(
                    activity.applicationContext,
                    activity.getString(R.string.search_engine_image_required)
                )
                return false
            }
        }

        return when (result) {
            ImageShareLauncher.LaunchResult.SUCCESS -> true
            ImageShareLauncher.LaunchResult.UNFREEZE_FAILED -> false

            ImageShareLauncher.LaunchResult.TARGET_NOT_FOUND -> {
                ToastHelper.showInUIThread(
                    activity.applicationContext,
                    activity.getString(R.string.inspire_share_image_target_failed)
                )
                false
            }

            ImageShareLauncher.LaunchResult.LAUNCH_FAILED -> {
                ToastHelper.showInUIThread(
                    activity.applicationContext,
                    activity.getString(R.string.inspire_share_image_failed)
                )
                false
            }
        }
    }

    private suspend fun executedAfterSearch() {
        withContext(ioDispatcher) { }
        executedCallback()
    }
}
