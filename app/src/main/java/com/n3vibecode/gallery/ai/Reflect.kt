package com.n3vibecode.gallery.ai

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Reflection-Hilfen für die Modellanbindung – **absichtlich ohne Android-Abhängigkeit**,
 * damit sie sich mit normalen Unit-Tests prüfen lässt (`app/src/test/.../ReflectTest.kt`).
 *
 * Warum eigene Suche statt `getMethod`?
 *
 *  • MediaPipe übergibt `AutoValue_BaseOptions`-Objekte, die Methoden verlangen aber
 *    `BaseOptions`. `getDeclaredMethod` vergleicht **exakt** und findet deshalb nichts –
 *    das war der Fehler in 1.35 und 1.36 („setBaseOptions(AutoValue_BaseOptions) an Builder“).
 *  • `build()` steckt bei AutoValue nicht im abstrakten Builder, sondern in der erzeugten
 *    `AutoValue_…Builder`. Deshalb wird die ganze Oberklassen-Kette abgesucht.
 */
internal object Reflect {

    /**
     * Sucht eine Methode, deren Parameter zu den **übergebenen Werten** passen
     * (Zuweisbarkeit, nicht Gleichheit).
     *
     * @param target Klasse, in der gesucht wird (inkl. Oberklassen)
     * @param argTypes Klassen der Argumente; `null` steht für „Wert ist null“
     */
    fun findMethod(target: Class<*>, name: String, argTypes: List<Class<*>?>): Method? {
        var c: Class<*>? = target
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != name) continue
                if (m.parameterTypes.size != argTypes.size) continue
                if (!fits(m.parameterTypes, argTypes)) continue
                runCatching { m.isAccessible = true }
                return m
            }
            c = c.superclass
        }
        return null
    }

    /** Passen die deklarierten Parametertypen zu den übergebenen Werten? */
    fun fits(declared: Array<Class<*>>, argTypes: List<Class<*>?>): Boolean {
        if (declared.size != argTypes.size) return false
        for (i in declared.indices) {
            val param = if (declared[i].isPrimitive) boxed(declared[i]) else declared[i]
            val arg = argTypes[i]
            if (arg == null) {
                // null passt nur auf Nicht-Primitive
                if (declared[i].isPrimitive) return false
            } else if (!param.isAssignableFrom(arg)) {
                return false
            }
        }
        return true
    }

    /** `int` → `Integer` usw. Reflection liefert für primitive Parameter eigene Klassen. */
    fun boxed(c: Class<*>): Class<*> = when (c) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> c
    }

    /** Methodenaufruf, der die Ursache durchreicht (statt `InvocationTargetException`). */
    fun invoke(target: Any, method: Method, vararg args: Any?): Any? = try {
        method.invoke(target, *args)
    } catch (e: InvocationTargetException) {
        throw e.cause ?: e
    }

    /** Aufruf über Namen – sucht die passende Methode über [findMethod]. */
    fun call(target: Any, name: String, vararg args: Any?): Any? {
        val m = findMethod(target.javaClass, name, args.map { it?.javaClass })
            ?: throw NoSuchMethodException("$name an ${target.javaClass.simpleName}")
        return invoke(target, m, *args)
    }

    /** Statischer Aufruf, z. B. `BaseOptions.builder()`. */
    fun callStatic(target: Class<*>, name: String, vararg args: Any?): Any? {
        val m = findMethod(target, name, args.map { it?.javaClass })
            ?: throw NoSuchMethodException("$name an ${target.simpleName}")
        return try {
            m.invoke(null, *args)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    /** Feld in der Klassenhierarchie lesen (für Kotlin-Properties ohne Getter-Namen). */
    fun field(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f: Field? = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                runCatching { f.isAccessible = true }
                return runCatching { f.get(target) }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    /**
     * Fehler lesbar machen.
     *
     * `NoClassDefFoundError` und `UnsatisfiedLinkError` sind `Error`, keine `Exception` –
     * in 1.35/1.36 wurden sie nicht gefangen und kamen als nackter Klassenname an.
     */
    fun human(t: Throwable?): String {
        if (t == null) return "unbekannter Fehler"
        var root: Throwable = t
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val msg = root.message?.takeIf { it.isNotBlank() }
        return when (root) {
            is NoClassDefFoundError ->
                "Klasse fehlt: ${msg ?: root.javaClass.simpleName} (Bibliotheksversion passt nicht)"
            is ClassNotFoundException ->
                "Klasse nicht gefunden: ${msg ?: root.javaClass.simpleName}"
            is UnsatisfiedLinkError -> "Natives Modul fehlt (${msg ?: "unbekannt"})"
            is OutOfMemoryError -> "Zu wenig Arbeitsspeicher für das Modell"
            is NoSuchMethodException -> "API nicht gefunden ($msg)"
            is NoSuchFieldException -> "Feld nicht gefunden ($msg)"
            else -> msg ?: root.javaClass.simpleName
        }
    }
}
