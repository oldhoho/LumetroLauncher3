package ru.queuejw.lumetro.search.config

import org.json.JSONArray
import org.json.JSONObject
import ru.queuejw.lumetro.gesture.config.IconType
import java.util.UUID

enum class EngineType {
    DIRECT_LINK,
    SHARE_TO_APP,
    SHARE_IMAGE_TO_APP,
    JUMP_TO_ACTIVITY,
    EXTERN_JUMP_LINK,
}

enum class SearchContentMode {
    TEXT,
    IMAGE,
    ASK,
}

class SearchEngine {
    var tag: String = UUID.randomUUID().toString()
    var name: String? = null
    var engineType: EngineType = EngineType.DIRECT_LINK
    var icon: String? = null
    var iconType: IconType = IconType.OTHER
    var textIcon: String? = null
    var searchLink: String? = null
    var externJumpLink: String? = null
    var externJumpPackage: String? = null
    var targetPackage: String? = null
    var targetActivity: String? = null
    var useCustomTabs: Boolean = false
    var autoInputEnter: Boolean = true
    var showInSearchEngineList: Boolean = true

    var imageShareEnabled: Boolean = false
    var imageTargetPackage: String? = null
    var imageTargetActivity: String? = null
    var mixedContentMode: SearchContentMode = SearchContentMode.TEXT

    fun resolveContentMode(hasText: Boolean, hasImage: Boolean): SearchContentMode = when {
        engineType == EngineType.SHARE_IMAGE_TO_APP -> SearchContentMode.IMAGE
        !imageShareEnabled || !hasImage -> SearchContentMode.TEXT
        !hasText -> SearchContentMode.IMAGE
        else -> mixedContentMode
    }

    fun supportsImageShare(): Boolean =
        engineType == EngineType.SHARE_IMAGE_TO_APP || imageShareEnabled

    fun imageShareTargetPackage(): String? =
        if (engineType == EngineType.SHARE_IMAGE_TO_APP) targetPackage else imageTargetPackage

    fun imageShareTargetActivity(): String? =
        if (engineType == EngineType.SHARE_IMAGE_TO_APP) targetActivity else imageTargetActivity

    fun toJson(format: Boolean = true): String =
        toJsonObject().toString(if (format) 2 else 0)

    private fun toJsonObject(): JSONObject = JSONObject().apply {
        put("tag", tag)
        put("name", name)
        put("engineType", engineType.name)
        put("icon", icon)
        put("iconType", iconType.name)
        put("textIcon", textIcon)
        put("searchLink", searchLink)
        put("externJumpLink", externJumpLink)
        put("externJumpPackage", externJumpPackage)
        put("targetPackage", targetPackage)
        put("targetActivity", targetActivity)
        put("useCustomTabs", useCustomTabs)
        put("autoInputEnter", autoInputEnter)
        put("showInSearchEngineList", showInSearchEngineList)
        put("imageShareEnabled", imageShareEnabled)
        put("imageTargetPackage", imageTargetPackage)
        put("imageTargetActivity", imageTargetActivity)
        put("mixedContentMode", mixedContentMode.name)
    }

    companion object {
        fun toModel(json: String): SearchEngine? = runCatching {
            fromJson(JSONObject(json))
        }.getOrNull()

        fun toJsonList(list: List<SearchEngine>): String =
            JSONArray().apply { list.forEach { put(it.toJsonObject()) } }.toString()

        fun toExportJsonList(list: List<SearchEngine>): String = toJsonList(list)

        fun toModelList(json: String): List<SearchEngine>? = runCatching {
            if (json.isBlank()) return@runCatching emptyList()
            val array = JSONArray(json)
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }.getOrNull()

        private fun fromJson(json: JSONObject): SearchEngine = SearchEngine().apply {
            tag = json.optString("tag", UUID.randomUUID().toString())
            name = json.optStringOrNull("name")
            engineType = runCatching { EngineType.valueOf(json.optString("engineType")) }
                .getOrDefault(EngineType.DIRECT_LINK)
            icon = json.optStringOrNull("icon")
            iconType = runCatching { IconType.valueOf(json.optString("iconType")) }
                .getOrDefault(IconType.OTHER)
            textIcon = json.optStringOrNull("textIcon")
            searchLink = json.optStringOrNull("searchLink")
            externJumpLink = json.optStringOrNull("externJumpLink")
            externJumpPackage = json.optStringOrNull("externJumpPackage")
            targetPackage = json.optStringOrNull("targetPackage")
            targetActivity = json.optStringOrNull("targetActivity")
            useCustomTabs = json.optBoolean("useCustomTabs", false)
            autoInputEnter = json.optBoolean("autoInputEnter", true)
            showInSearchEngineList = json.optBoolean("showInSearchEngineList", true)
            imageShareEnabled = json.optBoolean("imageShareEnabled", false)
            imageTargetPackage = json.optStringOrNull("imageTargetPackage")
            imageTargetActivity = json.optStringOrNull("imageTargetActivity")
            mixedContentMode = runCatching { SearchContentMode.valueOf(json.optString("mixedContentMode")) }
                .getOrDefault(SearchContentMode.TEXT)
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key) else null
    }
}
