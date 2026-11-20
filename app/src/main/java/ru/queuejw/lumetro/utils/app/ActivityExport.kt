package ru.queuejw.lumetro.utils.app

import android.content.Context
import android.content.pm.PackageManager

fun isActivityExported(context: Context, packageName: String, activityName: String): Boolean {
    val info = context.packageManager.getActivityInfo(
        android.content.ComponentName(packageName, activityName),
        PackageManager.MATCH_DISABLED_COMPONENTS
    )
    return info.exported
}
