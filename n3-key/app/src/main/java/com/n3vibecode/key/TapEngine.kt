package com.n3vibecode.key

import java.math.BigInteger

/** No Android dependency. Constant gesture state, one timer; counts are not Int-limited. */
class TapEngine(
    private val scheduler: Scheduler,
    private val gapMs: Long,
    private val chordEnabled: Boolean,
    private val emit: (String, BigInteger) -> Unit,
    private val fallback: (Int, BigInteger) -> Unit
) {
    interface Scheduler {
        fun after(delayMs: Long, task: () -> Unit)
        fun cancel()
    }
    private data class Press(val at: Long, var long: Boolean = false)
    private val down = HashMap<Int, Press>()
    private var chord = false
    private var chordLong = false
    private var pending: String? = null
    private var count = BigInteger.ZERO

    fun down(code: Int, time: Long, repeat: Boolean = false) {
        val previous = down[code]
        if (previous != null) {
            if (repeat) {
                if (chord) chordLong = true else {
                    if (!previous.long) flush()
                    previous.long = true
                    fallback(code, BigInteger.ONE)
                }
            }
            return
        }
        if (repeat) return // A held key from before a reset is not a new short click.
        // The next press must START inside the gap. Wait for its release before resolving.
        scheduler.cancel()
        down[code] = Press(time)
        val other = if (code == UP) DOWN else if (code == DOWN) UP else -1
        val partner = down[other]
        if (chordEnabled && partner != null && !partner.long && time - partner.at <= CHORD_MS) {
            chord = true
            chordLong = false
        }
    }

    fun up(code: Int, time: Long, canceled: Boolean = false) {
        val press = down.remove(code) ?: return
        if (chord && (code == UP || code == DOWN)) {
            if (canceled || time - press.at > SHORT_MS) chordLong = true
            if (!down.containsKey(UP) && !down.containsKey(DOWN)) {
                chord = false
                if (!chordLong) tap(CHORD) else arm()
            }
            return
        }
        if (!canceled && !press.long) {
            if (time - press.at <= SHORT_MS) tap(code.toString())
            else { flush(); fallback(code, BigInteger.ONE) }
        }
        arm()
    }

    private fun tap(trigger: String) {
        if (pending != null && pending != trigger) flush()
        pending = trigger
        count = count.add(BigInteger.ONE)
        arm()
    }
    private fun arm() {
        scheduler.cancel()
        if (pending != null && down.isEmpty()) scheduler.after(gapMs) { flush() }
    }
    private fun flush() {
        scheduler.cancel()
        val trigger = pending ?: return
        val taps = count
        pending = null
        count = BigInteger.ZERO
        emit(trigger, taps)
    }
    fun reset() {
        scheduler.cancel()
        down.clear()
        chord = false
        chordLong = false
        pending = null
        count = BigInteger.ZERO
    }
    companion object {
        const val UP = 24
        const val DOWN = 25
        const val CHORD = "24+25"
        const val CHORD_MS = 150L
        const val SHORT_MS = 500L
    }
}
