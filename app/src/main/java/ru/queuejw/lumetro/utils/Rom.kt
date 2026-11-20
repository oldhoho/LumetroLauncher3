package ru.queuejw.lumetro.utils

import android.content.Context

object Rom {
    enum class RomFreeFormCode(val code: String) {
        NUBIA("nubia"),
        VIVO("vivo"),
        OTHER("other"),
    }

    fun getFreeFormCode(context: Context): String = RomFreeFormCode.OTHER.code
}
