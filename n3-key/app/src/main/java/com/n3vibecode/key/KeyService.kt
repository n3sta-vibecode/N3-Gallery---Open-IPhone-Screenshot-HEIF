package com.n3vibecode.key

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.math.BigInteger

class KeyService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled: Runnable? = null
    private lateinit var store: RuleStore
    private lateinit var actions: Actions
    private lateinit var engine: TapEngine
    private var rules = emptyMap<String, Rule>()
    private var keys = emptySet<Int>()
    private val consumed = HashSet<Int>()
    var learning: ((Int) -> Unit)? = null
        set(value) { if (::engine.isInitialized) engine.reset(); field = value }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        store = RuleStore(this)
        actions = Actions(this)
        store.prefs.registerOnSharedPreferenceChangeListener(this)
        reload()
    }
    private fun reload() {
        if (::engine.isInitialized) engine.reset()
        val all = store.read()
        rules = all.associateBy { it.id }
        keys = all.flatMap { it.trigger.split('+').map { code -> code.toInt() } }.toSet()
        engine = TapEngine(object : TapEngine.Scheduler {
            override fun after(delayMs: Long, task: () -> Unit) {
                cancel()
                scheduled = Runnable { scheduled = null; task() }.also { handler.postDelayed(it, delayMs) }
            }
            override fun cancel() { scheduled?.let { handler.removeCallbacks(it) }; scheduled = null }
        }, store.gap, all.any { it.trigger == TapEngine.CHORD }, { trigger, count ->
            if (store.enabled) {
                val rule = rules["$trigger:$count"]
                if (rule != null) actions.run(rule)
                else if (trigger != TapEngine.CHORD) actions.volumeFallback(trigger.toInt(), count)
            }
        }, { key, count -> if (store.enabled) actions.volumeFallback(key, count) })
    }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::engine.isInitialized) return false
        val key = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            if (!consumed.remove(key)) return false
            engine.up(key, event.eventTime, event.isCanceled)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (key in consumed) {
            engine.down(key, event.eventTime, event.repeatCount > 0)
            return true
        }
        val learner = learning
        if (learner != null && event.repeatCount == 0 && RuleStore.validCode(key)) {
            consumed.add(key)
            learner(key)
            return true
        }
        if (!store.enabled || key !in keys || event.repeatCount != 0) return false
        consumed.add(key)
        engine.down(key, event.eventTime)
        return true
    }
    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) { reload() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* No screen-content events requested. */ }
    override fun onInterrupt() { if (::engine.isInitialized) engine.reset() }
    override fun onDestroy() {
        if (::store.isInitialized) store.prefs.unregisterOnSharedPreferenceChangeListener(this)
        if (::engine.isInitialized) engine.reset()
        if (::actions.isInitialized) actions.close()
        learning = null
        consumed.clear()
        if (instance === this) instance = null
        super.onDestroy()
    }
    companion object { var instance: KeyService? = null; private set }
}
