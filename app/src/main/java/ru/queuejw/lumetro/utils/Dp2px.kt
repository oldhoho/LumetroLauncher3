package ru.queuejw.lumetro.utils

import android.content.res.Resources

val Int.dp2px: Float
    get() = this * Resources.getSystem().displayMetrics.density

val Float.dp2px: Float
    get() = this * Resources.getSystem().displayMetrics.density
