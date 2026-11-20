package ru.queuejw.lumetro.link

import android.content.Context
import ru.queuejw.lumetro.utils.readKey
import ru.queuejw.lumetro.utils.saveKey
import ru.queuejw.lumetro.utils.settingsStore

/**
 * Persists the link routing table in the shared settings store.
 *
 * Each collection is stored as a single JSON string so a partial write can never leave the
 * rule table half-updated.
 */
object LinkRuleRepository {
    private const val KEY_RULES = "link_dispatch_rules"
    private const val KEY_EXTRACTS = "link_extract_patterns"
    private const val KEY_EXEMPTS = "link_exempt_domains"
    private const val KEY_SEEDED = "link_rules_seeded"

    suspend fun loadRules(context: Context): List<LinkDispatchRule> {
        val json = context.settingsStore.readKey(KEY_RULES, "")
        if (json.isEmpty()) return emptyList()
        return LinkConfigCodec.rulesFromJson(json).orEmpty().sortedBy { it.priority }
    }

    suspend fun updateRules(
        context: Context,
        update: (List<LinkDispatchRule>) -> List<LinkDispatchRule>
    ) {
        val next = update(loadRules(context)).sortedBy(LinkDispatchRule::priority)
        context.settingsStore.saveKey(KEY_RULES, LinkConfigCodec.rulesToJson(next))
    }

    suspend fun updateExtractPatterns(
        context: Context,
        update: (List<LinkExtractPattern>) -> List<LinkExtractPattern>
    ) {
        seedBuiltinExtractsIfNeeded(context)
        val next = update(loadExtractPatterns(context))
        context.settingsStore.saveKey(KEY_EXTRACTS, LinkConfigCodec.extractsToJson(next))
    }

    suspend fun updateExemptDomains(
        context: Context,
        update: (List<LinkExemptDomain>) -> List<LinkExemptDomain>
    ) {
        val next = update(loadExemptDomains(context))
        context.settingsStore.saveKey(KEY_EXEMPTS, LinkConfigCodec.exemptsToJson(next))
    }

    suspend fun snapshot(context: Context): LinkConfigBackup {
        seedBuiltinExtractsIfNeeded(context)
        return LinkConfigBackup(
            rules = loadRules(context),
            exempts = loadExemptDomains(context),
            extracts = loadExtractPatterns(context)
        )
    }

    suspend fun importConfig(context: Context, incoming: LinkConfigBackup): LinkImportResult {
        seedBuiltinExtractsIfNeeded(context)
        val (merged, added) = LinkConfigCodec.merge(
            loadRules(context),
            loadExemptDomains(context),
            loadExtractPatterns(context),
            incoming
        )
        context.settingsStore.saveKey(KEY_RULES, LinkConfigCodec.rulesToJson(merged.rules))
        context.settingsStore.saveKey(KEY_EXEMPTS, LinkConfigCodec.exemptsToJson(merged.exempts))
        context.settingsStore.saveKey(KEY_EXTRACTS, LinkConfigCodec.extractsToJson(merged.extracts))
        return added
    }

    suspend fun loadExtractPatterns(context: Context): List<LinkExtractPattern> {
        seedBuiltinExtractsIfNeeded(context)
        val json = context.settingsStore.readKey(KEY_EXTRACTS, "")
        if (json.isEmpty()) return LinkExtractPattern.builtinDefaults()
        return LinkConfigCodec.extractsFromJson(json) ?: LinkExtractPattern.builtinDefaults()
    }

    suspend fun loadExemptDomains(context: Context): List<LinkExemptDomain> {
        val json = context.settingsStore.readKey(KEY_EXEMPTS, "")
        if (json.isEmpty()) return emptyList()
        return LinkConfigCodec.exemptsFromJson(json).orEmpty()
    }

    /**
     * Writes the built-in extraction patterns once, so users can edit or delete them later
     * instead of having them reappear on every launch.
     */
    private suspend fun seedBuiltinExtractsIfNeeded(context: Context) {
        if (context.settingsStore.readKey(KEY_SEEDED, false)) return
        if (context.settingsStore.readKey(KEY_EXTRACTS, "").isEmpty()) {
            context.settingsStore.saveKey(
                KEY_EXTRACTS,
                LinkConfigCodec.extractsToJson(LinkExtractPattern.builtinDefaults())
            )
        }
        context.settingsStore.saveKey(KEY_SEEDED, true)
    }
}
