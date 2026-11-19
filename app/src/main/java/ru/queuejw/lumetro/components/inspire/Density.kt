package ru.queuejw.lumetro.components.inspire

import android.content.res.Resources

internal val Float.dp2px: Float
    get() = this * Resources.getSystem().displayMetrics.density

internal val Int.dp2px: Float
    get() = this.toFloat() * Resources.getSystem().displayMetrics.density
