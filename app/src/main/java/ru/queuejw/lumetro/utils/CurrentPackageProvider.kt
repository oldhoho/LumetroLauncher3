package ru.queuejw.lumetro.utils

import android.accessibilityservice.AccessibilityService
import ru.queuejw.lumetro.components.core.sidebar.SidebarAccessibilityService

object CurrentPackageProvider {
    fun resolveCurrentPackageName(): String? =
        SidebarAccessibilityService.getInstance()
            ?.rootInActiveWindow?.packageName?.toString()

    fun accessibilityService(): AccessibilityService? =
        SidebarAccessibilityService.getInstance()
}
