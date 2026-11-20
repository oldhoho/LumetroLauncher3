package ru.queuejw.lumetro.inspire

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import ru.queuejw.lumetro.R
import ru.queuejw.lumetro.components.core.sidebar.SidebarAccessibilityService
import ru.queuejw.lumetro.gesture.config.GestureActionType
import ru.queuejw.lumetro.link.LinkLauncher
import ru.queuejw.lumetro.pin.PinPoolStore
import ru.queuejw.lumetro.search.config.SearchEngineDataManager
import ru.queuejw.lumetro.settings.InspireSettingsActivity
import ru.queuejw.lumetro.settings.InspireSettingsActivity.Companion.KEY_INSPIRE_DELAY_REMOVE_IMAGE
import ru.queuejw.lumetro.settings.InspireSettingsActivity.Companion.KEY_SEARCH_APP
import ru.queuejw.lumetro.settings.InspireSettingsActivity.Companion.KEY_SEARCH_LINK
import ru.queuejw.lumetro.utils.ActionProxy
import ru.queuejw.lumetro.utils.ClipboardUtils
import ru.queuejw.lumetro.utils.GalleryImageManager
import ru.queuejw.lumetro.utils.ToastHelper
import ru.queuejw.lumetro.utils.readKey
import ru.queuejw.lumetro.utils.settingsStore
import ru.queuejw.lumetro.utils.uriBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

object InspireHelperUtils {
    /**
     * Opens the first link found in [text] using the configured dispatch rules.
     *
     * Rule matching, short link resolution and the fallback to the system browser all live in
     * [LinkLauncher], so this only extracts the text and forwards it.
     */
    suspend fun extractAndOpenURL(context: Context, text: String, side: String? = null): Boolean {
        return LinkLauncher.openFromText(context, text, sourcePackage = InspireDataHolder.sourcePackage.orEmpty(),
            side = side ?: InspireDataHolder.triggerSide)
    }

    /**
     * Opens the first link with the system default handler, skipping the dispatch rules. This
     * is the long-press escape hatch when a rule misfires.
     */
    suspend fun extractAndOpenURLDirect(context: Context, text: String): Boolean {
        return LinkLauncher.openFromText(context, text, bypassRules = true, side = InspireDataHolder.triggerSide)
    }


    private suspend fun saveImageToGalleryInternal(
        context: Context,
        bitmap: Bitmap,
        isPress: Boolean
    ): Uri? =
        withContext(
            Dispatchers.IO
        ) {
            val delayRemove = context.settingsStore.readKey(KEY_INSPIRE_DELAY_REMOVE_IMAGE, false)
            val delayTime = if (!isPress && delayRemove) 5 else 0
            return@withContext GalleryImageManager.saveImageToGalleryWithAutoDelete(
                context,
                bitmap,
                delayTime.toLong()
            )
        }

    suspend fun saveImageToGallery(context: Context, bitmapHandle: ManagedBitmap, isPress: Boolean): Uri? =
        bitmapHandle.acquire()?.use { handle ->
            saveImageToGalleryInternal(context, handle.requireBitmap(), isPress)
        }

    private suspend fun saveBitmapToCacheFileInternal(
        context: Context,
        bitmap: Bitmap,
        prefix: String = "inspire"
    ): File? = withContext(Dispatchers.IO) {
        try {
            val imagesFolder = File(context.cacheDir, "images").apply { mkdirs() }
            val file = File(imagesFolder, "${prefix}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun saveBitmapToCacheFile(
        context: Context,
        bitmapHandle: ManagedBitmap,
        prefix: String = "inspire"
    ): File? = bitmapHandle.acquire()?.use { handle ->
        saveBitmapToCacheFileInternal(context, handle.requireBitmap(), prefix)
    }
    fun shareText(context: Context, text: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.inspire_share_text_title))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            ToastHelper.showInUIThread(context, context.getString(R.string.inspire_share_text_failed))
        }
    }

    private suspend fun copyBitmapToClipboardInternal(
        context: Context,
        bitmap: Bitmap,
        showToast: Boolean = true,
        autoSaveToPinPool: Boolean = false
    ) {
        val written = ClipboardUtils.copyImageToClipboard(
            context = context,
            bitmap = bitmap,
            fileName = "shared_image.png",
            quality = 90,
            label = context.getString(R.string.inspire_clipboard_image_label),
        )
        if (!written) {
            Log.w("InspireHelperUtils", "Failed to copy image to clipboard")
            // The baseline always reported copy failures, even when the success toast was muted.
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.inspire_clipboard_image_copy_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        if (autoSaveToPinPool) {
            try {
                // The clipboard already holds this image, so the Pin pool hook must not write it again.
                PinPoolStore.addScreenContentImageIfEnabled(
                    context,
                    bitmap,
                    clipboardAlreadyWritten = true,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("InspireHelperUtils", "Failed to auto-save copied image to Pin pool", e)
            }
        }
        if (showToast) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.inspire_clipboard_image_copied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    suspend fun copyBitmapToClipboard(
        context: Context,
        bitmapHandle: ManagedBitmap,
        showToast: Boolean = true,
        autoSaveToPinPool: Boolean = false
    ) {
        bitmapHandle.acquire()?.use { handle ->
            copyBitmapToClipboardInternal(context, handle.requireBitmap(), showToast, autoSaveToPinPool)
        }
    }

    fun pinImage(context: SidebarAccessibilityService, bitmapHandle: ManagedBitmap) {
        bitmapHandle.acquire()?.let { PictureFloating.build(context, it) }
    }

    fun pinText(context: SidebarAccessibilityService, text: String) {
        PictureFloating.build(context, text)
    }

    suspend fun translateText(context: Context, text: String) {
        val pkg = context.settingsStore.readKey(InspireSettingsActivity.KEY_TRANSLATION_APP, "")
        if (pkg.isEmpty()) {
            ToastHelper.showInUIThread(
                context,
                context.getString(R.string.inspire_translation_app_not_set)
            )
            return
        }
        val intent = Intent().apply {
            setPackage(pkg)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
            type = "text/plain"
            action = Intent.ACTION_PROCESS_TEXT
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        }
        withContext(Dispatchers.Main) {
            try {
                context.startActivity(intent)
            } catch (ignored: ActivityNotFoundException) {
                ToastHelper.showInUIThread(
                    context,
                    context.getString(R.string.inspire_translation_app_not_found)
                )
            } catch (ignored: SecurityException) {
            }
        }
    }

    suspend fun searchText(context: Context, text: String) {
        val encodedQuery = URLEncoder.encode(text, "UTF-8")
        val defaultEngineLink = SearchEngineDataManager.getDefaultSearchEngine(context)
            ?.searchLink
            ?.takeIf { it.isNotBlank() }
        if (defaultEngineLink != null) {
            val target = defaultEngineLink
                .replace("%s", encodedQuery)
                .replace("%q", text)
            try {
                val intent = Intent(Intent.ACTION_VIEW, target.toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(intent)
                }
            } catch (ignored: Exception) {
                ToastHelper.showInUIThread(
                    context,
                    context.getString(R.string.inspire_search_link_error)
                )
            }
            return
        }
        val searchLink = context.settingsStore.readKey(KEY_SEARCH_LINK, "")
        if (searchLink.isEmpty()) {
            val searchApp = context.settingsStore.readKey(KEY_SEARCH_APP, "")
            if (searchApp.isEmpty()) {
                val link = uriBuilder(GestureActionType.SEARCH)
                    .appendQueryParameter("query", text)
                    .build()
                ActionProxy(context, link.toString()).execute(side = InspireDataHolder.triggerSide)
                return
            }
            val intent = Intent().apply {
                setPackage(searchApp)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                type = "text/plain"
                action = Intent.ACTION_PROCESS_TEXT
                putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            }
            withContext(Dispatchers.Main) {
                try {
                    context.startActivity(intent)
                } catch (ignored: ActivityNotFoundException) {
                    ToastHelper.showInUIThread(
                        context,
                        context.getString(R.string.inspire_search_target_app_invalid)
                    )
                } catch (ignored: SecurityException) {
                }
            }
        } else {
            try {
                val uri = searchLink.replace("%s", encodedQuery).toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(intent)
                }
            } catch (ignored: Exception) {
                ToastHelper.showInUIThread(
                    context,
                    context.getString(R.string.inspire_search_link_error)
                )
                ignored.printStackTrace()
            }
        }
    }
}
