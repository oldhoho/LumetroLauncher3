package ru.queuejw.lumetro.search

data class InjectionTask(
    val query: String,
    val targetPackage: String,
    val targetActivity: String,
    val inputEnter: Boolean,
    val usePaste: Boolean = false,
    val startTime: Long = System.currentTimeMillis()
)
