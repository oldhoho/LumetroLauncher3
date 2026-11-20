package ru.queuejw.lumetro.link

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses rules exported from LinkGo (`{"linkgo_rules":[...]}`) into the local model.
 *
 * Three LinkGo concepts need translation:
 *
 * 1. `targetPackage` may be `pkg/activity`; the activity becomes a component launch.
 * 2. Templates use LinkGo placeholders — `{url}`, `{1}` capture groups and the transform
 *    suffixes `_url_dec` / `_b64_enc` / `_url_dec_b64_dec`.
 * 3. A few rules act as pure resolvers: they have no target app and only follow a short link,
 *    then re-run dispatch. Those keep an empty package and never launch an app directly.
 */
object LinkGoRuleConverter {

    /** Parses an exported LinkGo file body. Accepts the wrapped or bare array form. */
    fun parse(json: String): List<LinkDispatchRule> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array = runCatching {
            if (trimmed.startsWith('[')) {
                JSONArray(trimmed)
            } else {
                JSONObject(trimmed).optJSONArray("linkgo_rules") ?: JSONArray()
            }
        }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { convert(it, index) }
        }
    }

    /** Loads the bundled LinkGo rule set from assets. */
    fun loadBuiltin(context: Context): List<LinkDispatchRule> = runCatching {
        val json = context.assets.open("linkgo_builtin_rules.json")
            .bufferedReader().use { it.readText() }
        parse(json)
    }.getOrDefault(emptyList())

    private fun convert(rule: JSONObject, index: Int): LinkDispatchRule {
        val (pkg, component) = splitTarget(rule.optString("targetPackage"))
        return LinkDispatchRule(
            name = rule.optString("name"),
            pattern = rule.optString("pattern"),
            targetPackage = pkg,
            component = component,
            matchType = parseEnum(rule.optString("matchType", "REGEX"), LinkMatchType.CONTAINS),
            template = rule.optString("template").takeIf { it.isNotEmpty() },
            extractPattern = rule.optString("extractPattern").takeIf { it.isNotEmpty() },
            isEnabled = rule.optBoolean("isEnabled", true),
            resolveShortLink = rule.optBoolean("resolveShortLink", false),
            resolveStrategy = parseEnum(rule.optString("resolveStrategy", "NONE"), LinkResolveStrategy.NONE),
            excludeFromRecents = rule.optBoolean("excludeFromRecents", false),
            priority = index
        )
    }

    /** `pkg/activity` form pins a component; a bare package lets the app resolve it. */
    fun splitTarget(target: String): Pair<String, String?> {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return "" to null
        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash == trimmed.length - 1) return trimmed to null
        return trimmed.substring(0, slash) to trimmed.substring(slash + 1)
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
}
