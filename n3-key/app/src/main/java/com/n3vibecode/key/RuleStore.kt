package com.n3vibecode.key

import android.content.Context
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

internal data class Rule(val trigger: String, val count: BigInteger, val action: String, val app: String = "", val appLabel: String = "") {
    val id: String get() = "$trigger:$count"
    fun label(): String = if (action == "app") appLabel.ifBlank { app } else Actions.labels[action] ?: action
}

internal class RuleStore(context: Context) {
    val prefs = context.getSharedPreferences("n3_key", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var gap: Long
        get() = prefs.getLong("gap", 450L).coerceIn(250L, 1500L)
        set(value) { prefs.edit().putLong("gap", value.coerceIn(250L, 1500L)).apply() }
    fun read(): List<Rule> {
        return try {
            val data = JSONArray(prefs.getString("rules", "[]"))
            (0 until data.length()).mapNotNull { i ->
                try {
                    val o = data.getJSONObject(i)
                    val trigger = o.getString("key")
                    val count = BigInteger(o.getString("count"))
                    val action = o.getString("action")
                    if (count.signum() <= 0 || action !in Actions.labels || !validTrigger(trigger)) null
                    else Rule(trigger, count, action, o.optString("app"), o.optString("label"))
                } catch (_: Exception) { null }
            }.distinctBy { it.id }
        } catch (_: Exception) { emptyList() }
    }
    fun save(rules: List<Rule>) {
        val array = JSONArray()
        rules.forEach { r ->
            array.put(JSONObject().put("key", r.trigger).put("count", r.count.toString())
                .put("action", r.action).put("app", r.app).put("label", r.appLabel))
        }
        prefs.edit().putString("rules", array.toString()).apply()
    }
    fun put(rule: Rule, originalId: String? = null) {
        save(read().filter { it.id != rule.id && it.id != originalId } + rule)
    }
    companion object {
        fun validCode(code: Int): Boolean = code > 0 && code !in intArrayOf(
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
            KeyEvent.KEYCODE_SOFT_SLEEP, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST)
        fun validTrigger(trigger: String): Boolean = trigger == TapEngine.CHORD || trigger.toIntOrNull()?.let { validCode(it) } == true
        fun keyName(trigger: String): String = when (trigger) {
            "24" -> "Lauter"
            "25" -> "Leiser"
            TapEngine.CHORD -> "Lauter + Leiser"
            else -> "Taste ${trigger} · ${KeyEvent.keyCodeToString(trigger.toIntOrNull() ?: 0).removePrefix("KEYCODE_")}"
        }
    }
}
