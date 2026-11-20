package ru.queuejw.lumetro.gesture

import android.content.Context

class GestureManager {
    enum class InteractionMode {
        NORMAL,
        POINTER_DRAG_SELECT,
        INSPIRE_SELECT,
    }

    fun setInteractionMode(mode: InteractionMode) {
    }
}

val Context.gestureManager: GestureManager? get() = null
