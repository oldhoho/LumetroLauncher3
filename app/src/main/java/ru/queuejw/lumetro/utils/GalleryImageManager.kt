package ru.queuejw.lumetro.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

object GalleryImageManager {
    fun saveImageToGalleryWithAutoDelete(
        context: Context,
        bitmap: Bitmap,
        deleteAfterMinutes: Long,
    ): Uri? = try {
        val name = "lumetro_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lumetro")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
        }
        uri
    } catch (e: Exception) {
        null
    }

    fun saveUriImageToGallery(context: Context, sourceUri: Uri, deleteAfterMinutes: Long = 0): Boolean {
        return try {
            val name = "lumetro_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lumetro")
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            try {
                val input = resolver.openInputStream(sourceUri) ?: throw IllegalStateException()
                input.use { source ->
                    val output = resolver.openOutputStream(uri) ?: throw IllegalStateException()
                    output.use { source.copyTo(it) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null
                    )
                }
                true
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun openImageInGallery(context: Context, imageUri: Uri, targetPkg: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(imageUri, "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (targetPkg.isNotBlank()) intent.setPackage(targetPkg)
        runCatching { context.startActivity(intent) }
    }
}
