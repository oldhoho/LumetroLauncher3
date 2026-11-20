package ru.queuejw.lumetro.link

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Serialisable snapshot of the whole link routing configuration.
 *
 * Import and export share one shape so a backup taken on one device restores exactly on
 * another. Unknown or missing fields fall back to defaults, which keeps older backups usable
 * after the model grows.
 */
data class LinkConfigBackup(
    val version: Int = CURRENT_VERSION,
    val rules: List<LinkDispatchRule> = emptyList(),
    val exempts: List<LinkExemptDomain> = emptyList(),
    val extracts: List<LinkExtractPattern> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** Result of an import attempt, so callers can report what actually happened. */
data class LinkImportResult(
    val rulesAdded: Int,
    val exemptsAdded: Int,
    val extractsAdded: Int,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null
}

/**
 * Converts the configuration to and from JSON.
 *
 * Import is additive and never overwrites: entries are matched by pattern (rules) or domain
 * (exemptions) and duplicates are skipped. That way importing the same file twice is harmless
 * and a partial backup cannot destroy an existing setup.
 */
object LinkConfigCodec {

    fun export(
        rules: List<LinkDispatchRule>,
        exempts: List<LinkExemptDomain>,
        extracts: List<LinkExtractPattern>
    ): String {
        return JSONObject().apply {
            put("version", LinkConfigBackup.CURRENT_VERSION)
            put("rules", rules.rulesJsonArray())
            put("exempts", exempts.exemptsJsonArray())
            put("extracts", extracts.extractsJsonArray())
        }.toString(2)
    }

    fun decode(json: String): LinkConfigBackup? = runCatching {
        val root = JSONObject(json)
        val backup = LinkConfigBackup(
            version = root.optInt("version", LinkConfigBackup.CURRENT_VERSION),
            rules = root.optJSONArray("rules").toRules(),
            exempts = root.optJSONArray("exempts").toExempts(),
            extracts = root.optJSONArray("extracts").toExtracts()
        )
        backup.takeIf { it.version == LinkConfigBackup.CURRENT_VERSION }
    }.getOrNull()

    fun rulesToJson(rules: List<LinkDispatchRule>): String = rules.rulesJsonArray().toString()

    fun rulesFromJson(json: String): List<LinkDispatchRule>? = runCatching {
        JSONArray(json).toRules()
    }.getOrNull()

    fun extractsToJson(extracts: List<LinkExtractPattern>): String = extracts.extractsJsonArray().toString()

    fun extractsFromJson(json: String): List<LinkExtractPattern>? = runCatching {
        JSONArray(json).toExtracts()
    }.getOrNull()

    fun exemptsToJson(exempts: List<LinkExemptDomain>): String = exempts.exemptsJsonArray().toString()

    fun exemptsFromJson(json: String): List<LinkExemptDomain>? = runCatching {
        JSONArray(json).toExempts()
    }.getOrNull()

    /**
     * Merges [incoming] into the existing configuration.
     *
     * New ids are assigned to imported entries so they cannot collide with existing rows that
     * happen to share an id but represent different content.
     */
    fun merge(
        existingRules: List<LinkDispatchRule>,
        existingExempts: List<LinkExemptDomain>,
        existingExtracts: List<LinkExtractPattern>,
        incoming: LinkConfigBackup
    ): Pair<LinkConfigBackup, LinkImportResult> {
        val knownPatterns = existingRules.map { it.pattern }.toMutableSet()
        val knownDomains = existingExempts.map { it.domain.lowercase() to it.sourcePackage }.toMutableSet()
        val knownExtractIds = existingExtracts.map { it.id }.toMutableSet()
        val knownExtractPatterns = existingExtracts.map { it.pattern }.toMutableSet()

        val nextPriority = (existingRules.maxOfOrNull { it.priority } ?: -1) + 1
        val newRules = incoming.rules.filter { rule ->
            rule.pattern.isNotBlank() && knownPatterns.add(rule.pattern)
        }.mapIndexed { index, rule ->
            rule.copy(id = UUID.randomUUID().toString(), priority = nextPriority + index)
        }
        val newExempts = incoming.exempts.filter { exempt ->
            exempt.domain.isNotBlank() && knownDomains.add(exempt.domain.lowercase() to exempt.sourcePackage)
        }.map { it.copy(id = UUID.randomUUID().toString()) }
        val newExtracts = incoming.extracts.filter { pattern ->
            if (pattern.pattern.isBlank() || (pattern.isBuiltin && pattern.id in knownExtractIds)) return@filter false
            if (!knownExtractPatterns.add(pattern.pattern)) return@filter false
            if (pattern.isBuiltin) knownExtractIds.add(pattern.id)
            true
        }.map {
            if (it.isBuiltin) it else it.copy(id = UUID.randomUUID().toString())
        }

        val merged = LinkConfigBackup(
            rules = existingRules + newRules,
            exempts = existingExempts + newExempts,
            extracts = existingExtracts + newExtracts
        )
        val result = LinkImportResult(
            rulesAdded = newRules.size,
            exemptsAdded = newExempts.size,
            extractsAdded = newExtracts.size
        )
        return merged to result
    }

    private fun List<LinkDispatchRule>.rulesJsonArray(): JSONArray = JSONArray().apply {
        forEach { rule ->
            put(JSONObject().apply {
                put("id", rule.id)
                put("name", rule.name)
                put("pattern", rule.pattern)
                put("targetPackage", rule.targetPackage)
                put("matchType", rule.matchType.name)
                put("template", rule.template)
                put("extractPattern", rule.extractPattern)
                put("isEnabled", rule.isEnabled)
                put("resolveShortLink", rule.resolveShortLink)
                put("resolveStrategy", rule.resolveStrategy.name)
                put("excludeFromRecents", rule.excludeFromRecents)
                put("priority", rule.priority)
                put("iconPath", rule.iconPath)
                put("hitCount", rule.hitCount)
                put("lastHitAt", rule.lastHitAt)
                put("component", rule.component)
            })
        }
    }

    private fun List<LinkExtractPattern>.extractsJsonArray(): JSONArray = JSONArray().apply {
        forEach { pattern ->
            put(JSONObject().apply {
                put("id", pattern.id)
                put("name", pattern.name)
                put("pattern", pattern.pattern)
                put("isEnabled", pattern.isEnabled)
                put("isBuiltin", pattern.isBuiltin)
            })
        }
    }

    private fun List<LinkExemptDomain>.exemptsJsonArray(): JSONArray = JSONArray().apply {
        forEach { exempt ->
            put(JSONObject().apply {
                put("id", exempt.id)
                put("domain", exempt.domain)
                put("sourcePackage", exempt.sourcePackage)
                put("isEnabled", exempt.isEnabled)
            })
        }
    }

    private fun JSONArray?.toRules(): List<LinkDispatchRule> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let { o ->
                LinkDispatchRule(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    pattern = o.optString("pattern"),
                    targetPackage = o.optString("targetPackage"),
                    matchType = enumOrDefault(o.optString("matchType"), LinkMatchType.CONTAINS),
                    template = o.optStringOrNull("template"),
                    extractPattern = o.optStringOrNull("extractPattern"),
                    isEnabled = o.optBoolean("isEnabled", true),
                    resolveShortLink = o.optBoolean("resolveShortLink", false),
                    resolveStrategy = enumOrDefault(o.optString("resolveStrategy"), LinkResolveStrategy.NONE),
                    excludeFromRecents = o.optBoolean("excludeFromRecents", false),
                    priority = o.optInt("priority", 0),
                    iconPath = o.optStringOrNull("iconPath"),
                    hitCount = o.optInt("hitCount", 0),
                    lastHitAt = o.optLong("lastHitAt", 0L),
                    component = o.optStringOrNull("component")
                )
            }
        }
    }

    private fun JSONArray?.toExtracts(): List<LinkExtractPattern> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let { o ->
                LinkExtractPattern(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    pattern = o.optString("pattern"),
                    isEnabled = o.optBoolean("isEnabled", true),
                    isBuiltin = o.optBoolean("isBuiltin", false)
                )
            }
        }
    }

    private fun JSONArray?.toExempts(): List<LinkExemptDomain> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let { o ->
                LinkExemptDomain(
                    id = o.optString("id"),
                    domain = o.optString("domain"),
                    sourcePackage = o.optString("sourcePackage"),
                    isEnabled = o.optBoolean("isEnabled", true)
                )
            }
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
}
