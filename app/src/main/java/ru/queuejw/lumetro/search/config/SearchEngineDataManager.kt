package ru.queuejw.lumetro.search.config

import android.content.Context
import ru.queuejw.lumetro.gesture.config.IconType
import ru.queuejw.lumetro.utils.readKey
import ru.queuejw.lumetro.utils.saveKey
import ru.queuejw.lumetro.utils.searchStore
import java.util.UUID

object SearchEngineDataManager {
    const val KEY_SEARCH_ENGINE_LIST = "key_search_engine_list"
    const val KEY_DEFAULT_ENGINE_TAG = "key_default_engine_tag"
    private const val KEY_SEARCH_ENGINE_INITIALIZED = "key_search_engine_initialized"

    @Volatile
    private var cachedEngineList: List<SearchEngine>? = null

    fun buildTag(): String = UUID.randomUUID().toString()

    private fun createDefaultEngines(): List<SearchEngine> = listOf(
        SearchEngine().apply {
            tag = buildTag()
            name = "Bing"
            engineType = EngineType.DIRECT_LINK
            searchLink = "https://www.bing.com/search?q=%s"
            iconType = IconType.TEXT
            textIcon = "B"
            showInSearchEngineList = true
        },
        SearchEngine().apply {
            tag = buildTag()
            name = "Google"
            engineType = EngineType.DIRECT_LINK
            searchLink = "https://www.google.com/search?q=%s"
            iconType = IconType.TEXT
            textIcon = "G"
            showInSearchEngineList = true
        },
    )

    suspend fun ensureDefaultEngines(context: Context): Boolean {
        if (context.searchStore.readKey(KEY_SEARCH_ENGINE_INITIALIZED, false)) return false
        val existing = readSearchEngineList(context)
        if (existing.isNotEmpty()) {
            context.searchStore.saveKey(KEY_SEARCH_ENGINE_INITIALIZED, true)
            return false
        }
        val defaults = createDefaultEngines()
        saveSearchEngineList(context, defaults)
        setDefaultEngineTag(context, defaults.firstOrNull()?.tag)
        context.searchStore.saveKey(KEY_SEARCH_ENGINE_INITIALIZED, true)
        return true
    }

    suspend fun readSearchEngineList(context: Context): List<SearchEngine> {
        cachedEngineList?.let { return it }
        val json = context.searchStore.readKey(KEY_SEARCH_ENGINE_LIST, "")
        val list = SearchEngine.toModelList(json).orEmpty()
        cachedEngineList = list
        return list
    }

    suspend fun saveSearchEngineList(context: Context, engines: List<SearchEngine>) {
        val sorted = engines.filter { it.showInSearchEngineList } +
            engines.filter { !it.showInSearchEngineList }
        cachedEngineList = sorted
        context.searchStore.saveKey(KEY_SEARCH_ENGINE_LIST, SearchEngine.toJsonList(sorted))
    }

    suspend fun addSearchEngine(context: Context, engine: SearchEngine) {
        val list = readSearchEngineList(context).toMutableList()
        list.removeAll { it.tag == engine.tag }
        list.add(engine)
        saveSearchEngineList(context, list)
    }

    suspend fun updateSearchEngine(context: Context, engine: SearchEngine) {
        val list = readSearchEngineList(context).toMutableList()
        val index = list.indexOfFirst { it.tag == engine.tag }
        if (index >= 0) list[index] = engine else list.add(engine)
        saveSearchEngineList(context, list)
    }

    suspend fun removeSearchEngine(context: Context, tag: String) {
        val list = readSearchEngineList(context).filterNot { it.tag == tag }
        saveSearchEngineList(context, list)
        if (getDefaultEngineTag(context) == tag) {
            setDefaultEngineTag(context, list.firstOrNull()?.tag)
        }
    }

    suspend fun findSearchEngineByTag(context: Context, tag: String): SearchEngine? =
        readSearchEngineList(context).firstOrNull { it.tag == tag }

    suspend fun setDefaultEngineTag(context: Context, tag: String?) {
        context.searchStore.saveKey(KEY_DEFAULT_ENGINE_TAG, tag.orEmpty())
    }

    suspend fun getDefaultEngineTag(context: Context): String? =
        context.searchStore.readKey(KEY_DEFAULT_ENGINE_TAG, "").ifBlank { null }

    suspend fun getDefaultSearchEngine(context: Context): SearchEngine? {
        val tag = getDefaultEngineTag(context) ?: return null
        return findSearchEngineByTag(context, tag)
    }

    suspend fun getSearchEnginesByType(context: Context, type: EngineType): List<SearchEngine> =
        readSearchEngineList(context).filter { it.engineType == type }

    suspend fun clearAllSearchEngines(context: Context) {
        cachedEngineList = emptyList()
        context.searchStore.saveKey(KEY_SEARCH_ENGINE_LIST, "")
        setDefaultEngineTag(context, null)
    }

    suspend fun importSearchEngines(context: Context, json: String): Int {
        val imported = SearchEngine.toModelList(json) ?: return 0
        val merged = readSearchEngineList(context).toMutableList()
        imported.forEach { engine ->
            merged.removeAll { it.tag == engine.tag }
            merged.add(engine)
        }
        saveSearchEngineList(context, merged)
        return imported.size
    }

    suspend fun exportSearchEnginesJson(context: Context): String =
        SearchEngine.toJsonList(readSearchEngineList(context))
}
