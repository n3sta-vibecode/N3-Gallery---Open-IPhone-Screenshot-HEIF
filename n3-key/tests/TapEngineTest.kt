package com.n3vibecode.key

import java.math.BigInteger

private class Clock : TapEngine.Scheduler {
    var now = 0L
    private var due = Long.MAX_VALUE
    private var task: (() -> Unit)? = null
    override fun after(delayMs: Long, task: () -> Unit) { this.task = task; due = now + delayMs }
    override fun cancel() { task = null; due = Long.MAX_VALUE }
    fun advance(ms: Long) {
        val end = now + ms
        if (task != null && due <= end) {
            now = due
            val callback = task
            cancel()
            callback?.invoke()
        }
        now = end
    }
}
private class Fixture(chords: Boolean = true) {
    val clock = Clock()
    val events = mutableListOf<String>()
    val fallback = mutableListOf<String>()
    val engine = TapEngine(clock, 450, chords,
        { key, count -> events.add("$key:$count") }, { key, count -> fallback.add("$key:$count") })
    fun down(key: Int, repeat: Boolean = false) = engine.down(key, clock.now, repeat)
    fun up(key: Int, canceled: Boolean = false) = engine.up(key, clock.now, canceled)
    fun tap(key: Int = 24) { down(key); clock.advance(40); up(key) }
    fun chord() { down(24); clock.advance(50); down(25); clock.advance(40); up(24); up(25) }
    fun finish() = clock.advance(451)
}
private var passed = 0
private fun test(name: String, block: Fixture.() -> Unit) {
    Fixture().block(); passed++; println("PASS $name")
}
fun main() {
    test("single waits for timeout") { tap(); clock.advance(449); check(events.isEmpty()); clock.advance(1); check(events == listOf("24:1")) }
    test("double resolves exactly, not as singles") { tap(); clock.advance(100); tap(); finish(); check(events == listOf("24:2")) }
    test("triple and five clicks") { repeat(3) { tap() }; finish(); repeat(5) { tap(25) }; finish(); check(events == listOf("24:3", "25:5")) }
    test("2000 taps without a fixed tap cap") { repeat(2000) { tap() }; finish(); check(events == listOf("24:2000")) }
    test("counts beyond Long parse as BigInteger") { check(BigInteger("9223372036854775808").add(BigInteger.ONE).toString() == "9223372036854775809") }
    test("next down suspends pending timeout until release") { tap(); clock.advance(440); down(24); clock.advance(100); check(events.isEmpty()); up(24); finish(); check(events == listOf("24:2")) }
    test("separate groups after idle gap") { tap(); finish(); tap(); finish(); check(events == listOf("24:1", "24:1")) }
    test("chord suppresses singles") { chord(); finish(); check(events == listOf("24+25:1")); check(fallback.isEmpty()) }
    test("chord waits for both releases") { down(24); down(25); up(24); clock.advance(400); check(events.isEmpty()); up(25); finish(); check(events == listOf("24+25:1")) }
    test("repeated chords") { repeat(5) { chord() }; finish(); check(events == listOf("24+25:5")) }
    test("reverse chord order") { down(25); clock.advance(20); down(24); up(25); up(24); finish(); check(events == listOf("24+25:1")) }
    test("chord boundary accepted at 150ms") { down(24); clock.advance(150); down(25); up(24); up(25); finish(); check(events == listOf("24+25:1")) }
    test("too slow is not a chord") { down(24); clock.advance(151); down(25); up(24); up(25); finish(); check(events == listOf("24:1", "25:1")) }
    test("non-overlapping presses are not a chord") { tap(24); tap(25); finish(); check(events == listOf("24:1", "25:1")) }
    test("single then chord produces both sequences") { tap(); chord(); finish(); check(events == listOf("24:1", "24+25:1")) }
    test("custom action key can repeat") { repeat(7) { tap(1015) }; finish(); check(events == listOf("1015:7")) }
    test("auto repeat is not a multi click") { down(24); clock.advance(600); down(24, true); down(24, true); up(24); finish(); check(events.isEmpty()); check(fallback == listOf("24:1", "24:1")) }
    test("long press without repeats falls back once") { down(24); clock.advance(501); up(24); finish(); check(events.isEmpty()); check(fallback == listOf("24:1")) }
    test("500ms still a short press") { down(24); clock.advance(500); up(24); finish(); check(events == listOf("24:1")) }
    test("long chord does not trigger") { down(24); down(25); clock.advance(501); up(24); up(25); finish(); check(events.isEmpty()); check(fallback.isEmpty()) }
    test("canceled chord does not trigger") { down(24); down(25); up(24, true); up(25); finish(); check(events.isEmpty()) }
    test("canceled key does not trigger") { down(24); up(24, true); finish(); check(events.isEmpty()) }
    test("unmatched release ignored") { up(24); finish(); check(events.isEmpty()) }
    test("disable / reload cancels pending action") { tap(); engine.reset(); finish(); check(events.isEmpty()); tap(); finish(); check(events == listOf("24:1")) }
    test("reset while keys are held") { down(24); down(25); engine.reset(); up(24); up(25); finish(); check(events.isEmpty()); chord(); finish(); check(events == listOf("24+25:1")) }
    test("duplicate down without repeat ignored") { down(24); down(24); up(24); finish(); check(events == listOf("24:1")) }
    test("repeat without owned down ignored") { down(24, true); up(24); finish(); check(events.isEmpty()) }
    val noChord = Fixture(false)
    noChord.chord(); noChord.finish(); check(noChord.events == listOf("24:1", "25:1")); passed++; println("PASS disabled chord detection")
    println("$passed tests passed")
}
