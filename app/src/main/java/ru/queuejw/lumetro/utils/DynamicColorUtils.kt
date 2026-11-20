package ru.queuejw.lumetro.utils

import android.content.Context
import com.google.android.material.bottomsheet.BottomSheetDialog

object DynamicColorUtils {
    val primaryColor: Int get() = 0xFF6750A4.toInt()
    val onPrimaryContainerColor: Int get() = 0xFF21005D.toInt()
    val inverseOnSurfaceColor: Int get() = 0xFFF4EFF4.toInt()
    val secondaryContainerColor: Int get() = 0xFFE8DEF8.toInt()
    val surfaceContainerColor: Int get() = 0xFFF3EDF7.toInt()
    val surfaceContainerHighColor: Int get() = 0xFFECE6F0.toInt()
    val surfaceContainerHighestColor: Int get() = 0xFFE6E0E9.toInt()
    val onSurfaceColor: Int get() = 0xFF1D1B20.toInt()
    val onSurfaceVariantColor: Int get() = 0xFF49454F.toInt()
    val outlineColor: Int get() = 0xFF79747E.toInt()

    fun dynamicThemedContext(base: Context): Context = base

    fun refreshSurfaceColors(context: Context) {
    }

    fun applyBottomSheetSurface(dialog: BottomSheetDialog) {
        val sheet = dialog.findViewById<android.view.View>(
            com.google.android.material.R.id.design_bottom_sheet,
        )
        sheet?.setBackgroundColor(surfaceContainerColor)
    }
}
