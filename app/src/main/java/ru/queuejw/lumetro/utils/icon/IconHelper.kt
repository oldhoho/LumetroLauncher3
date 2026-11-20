package ru.queuejw.lumetro.utils.icon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toDrawable
import android.util.Log
import ru.queuejw.lumetro.gesture.ui.RoundImageTextView

object IconHelper {
    var iconImageRoundPercent: Float = 0f

    fun getIcon(context: Context, packageName: String): Drawable? = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.onFailure { Log.w("IconHelper", "getIcon failed for $packageName", it) }.getOrNull()

    fun getIconDrawable(context: Context, pkgName: String, iconPath: String): Drawable? =
        runCatching {
            iconPath.takeIf { it.isNotBlank() }?.let(::getIconBitmap)?.let { it.toDrawable(context.resources) }
        }.getOrNull() ?: getIcon(context, pkgName)

    fun getIconBitmap(iconPath: String): Bitmap? = runCatching {
        BitmapFactory.decodeFile(iconPath)
    }.getOrNull()
}

fun RoundImageTextView.setAppIcon(
    icon: Drawable?,
    placeholder: Int = android.R.drawable.ic_menu_gallery,
) {
    setImage(icon ?: placeholder)
}
