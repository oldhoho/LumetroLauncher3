package ru.queuejw.lumetro.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

private const val PREFS_NAME = "settings"

class SettingsStore(@PublishedApi internal val prefs: SharedPreferences)

val Context.settingsStore: SettingsStore
    get() = SettingsStore(applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

val Context.searchStore: SettingsStore
    get() = SettingsStore(applicationContext.getSharedPreferences("search", Context.MODE_PRIVATE))

@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> SettingsStore.readKey(key: String, defaultValue: T): T =
    when (defaultValue) {
        is Boolean -> prefs.getBoolean(key, defaultValue) as T
        is Int -> prefs.getInt(key, defaultValue) as T
        is Long -> prefs.getLong(key, defaultValue) as T
        is Float -> prefs.getFloat(key, defaultValue) as T
        is String -> prefs.getString(key, defaultValue) as T
        else -> defaultValue
    }

inline fun <reified T : Any> SettingsStore.saveKey(key: String, value: T) {
    when (value) {
        is Boolean -> prefs.edit { putBoolean(key, value) }
        is Int -> prefs.edit { putInt(key, value) }
        is Long -> prefs.edit { putLong(key, value) }
        is Float -> prefs.edit { putFloat(key, value) }
        is String -> prefs.edit { putString(key, value) }
    }
}
