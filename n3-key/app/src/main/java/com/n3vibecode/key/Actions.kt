package com.n3vibecode.key

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.widget.Toast
import java.math.BigInteger

internal class Actions(private val service: KeyService) {
    private val audio = service.getSystemService(AudioManager::class.java)
    private var camera: CameraManager? = null
    private var torchId: String? = null
    private var torchOn = false
    private var observing = false
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == torchId) torchOn = enabled }
        override fun onTorchModeUnavailable(cameraId: String) { if (cameraId == torchId) torchOn = false }
    }
    fun run(rule: Rule) {
        try {
            when (rule.action) {
                "none" -> Unit
                "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME)
                "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK)
                "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
                "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "quick" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                "lock" -> global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                "screenshot" -> if (Build.VERSION.SDK_INT >= 30) global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) else message("Screenshots benötigen Android 11 oder neuer.")
                "play" -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "next" -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
                "previous" -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "up" -> volumeFallback(TapEngine.UP, BigInteger.ONE)
                "down" -> volumeFallback(TapEngine.DOWN, BigInteger.ONE)
                "torch" -> torch()
                "camera" -> service.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "app" -> {
                    val intent = service.packageManager.getLaunchIntentForPackage(rule.app)
                    if (intent != null) service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else message("App nicht mehr installiert oder nicht startbar.")
                }
            }
        } catch (_: SecurityException) {
            message("Android hat diese Aktion gesperrt. Berechtigungen prüfen.")
        } catch (_: Exception) { message("Aktion auf diesem Gerät gerade nicht verfügbar.") }
    }
    private fun global(action: Int) { if (!service.performGlobalAction(action)) message("Systemaktion momentan nicht verfügbar.") }
    private fun media(code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }
    fun volumeFallback(key: Int, count: BigInteger) {
        if (key != TapEngine.UP && key != TapEngine.DOWN) return
        // Bound work to the volume range, even for arbitrary-sized click counts.
        val stream = if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION)
            AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        val steps = count.min(BigInteger.valueOf(audio.getStreamMaxVolume(stream).toLong())).toInt()
        try {
            repeat(steps) {
                audio.adjustStreamVolume(stream, if (key == TapEngine.UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            }
        } catch (_: SecurityException) { message("Lautstärke ist vom System gesperrt.") }
    }
    private fun torch() {
        if (service.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            message("Für die Taschenlampe bitte in N3 Key die Kameraberechtigung erlauben.")
            return
        }
        val manager = camera ?: service.getSystemService(CameraManager::class.java).also { camera = it }
        if (torchId == null) torchId = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        val id = torchId ?: run { message("Keine Taschenlampe verfügbar."); return }
        if (!observing) {
            manager.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
            observing = true
            // Receive the system's initial state before toggling; never guess it.
            Handler(Looper.getMainLooper()).post { try { manager.setTorchMode(id, !torchOn) } catch (_: Exception) { message("Taschenlampe gerade nicht verfügbar.") } }
        } else manager.setTorchMode(id, !torchOn)
    }
    fun close() { if (observing) camera?.unregisterTorchCallback(torchCallback) }
    private fun message(text: String) { Toast.makeText(service, text, Toast.LENGTH_LONG).show() }
    companion object {
        val labels = linkedMapOf(
            "torch" to "Taschenlampe umschalten", "play" to "Musik · Play / Pause",
            "next" to "Nächster Titel", "previous" to "Vorheriger Titel",
            "app" to "App öffnen", "camera" to "Kamera öffnen",
            "screenshot" to "Screenshot", "notifications" to "Benachrichtigungen",
            "quick" to "Schnelleinstellungen", "home" to "Startbildschirm",
            "back" to "Zurück", "recents" to "Letzte Apps", "lock" to "Bildschirm sperren",
            "up" to "Medienlautstärke +", "down" to "Medienlautstärke −", "none" to "Keine Aktion · Taste blockieren"
        )
    }
}
