package ru.queuejw.lumetro.tile

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.FrameLayout
import ru.queuejw.lumetro.components.core.sidebar.SidebarAccessibilityService

class ScreenContentTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // Collapsing the shade through a transparent dialog keeps the screenshot
        // free of the quick-settings panel.
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar).apply {
            setContentView(FrameLayout(this@ScreenContentTileService))
            setCancelable(true)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(0f)
            setOnShowListener {
                dismiss()
                if (!SidebarAccessibilityService.startScreenContent()) {
                    android.widget.Toast.makeText(
                        this@ScreenContentTileService,
                        "请先开启 Lumetro 无障碍服务",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
        showDialog(dialog)
    }
}
