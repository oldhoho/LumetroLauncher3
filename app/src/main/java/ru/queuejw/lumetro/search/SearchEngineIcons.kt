package ru.queuejw.lumetro.search

import android.content.Context
import ru.queuejw.lumetro.R
import ru.queuejw.lumetro.gesture.config.IconType
import ru.queuejw.lumetro.gesture.ui.RoundImageTextView
import ru.queuejw.lumetro.search.config.EngineType
import ru.queuejw.lumetro.search.config.SearchEngine
import ru.queuejw.lumetro.utils.icon.IconHelper

fun imageBadgeIcon(item: SearchEngine): Int? = when {
    item.engineType == EngineType.SHARE_IMAGE_TO_APP -> R.drawable.baseline_image_24
    item.imageShareEnabled -> R.drawable.baseline_image_text_24
    else -> null
}

fun setEngineIcon(
    context: Context,
    view: RoundImageTextView,
    item: SearchEngine,
    showImageBadge: Boolean = false,
) {
    var success = false
    view.setBadge(null)

    when (item.iconType) {
        IconType.URI -> {
            val bitmap = item.icon?.let { IconHelper.getIconBitmap(it) }
            success = bitmap != null
            if (bitmap != null) view.setImage(bitmap)
        }

        IconType.TEXT -> {
            success = true
            view.setText(item.textIcon ?: "")
        }

        else -> success = false
    }

    if (!success) {
        val targetPackage = item.targetPackage
        if (!targetPackage.isNullOrEmpty()) {
            view.setImage(IconHelper.getIcon(context, targetPackage))
        } else {
            view.setText(item.name ?: "")
        }
    }

    if (showImageBadge) {
        imageBadgeIcon(item)?.let { view.setBadge(it) }
    }
}
