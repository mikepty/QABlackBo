package com.qa.blackbox.domain.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Estado de la máquina de estados de MediaRecorder
 * Crítico para evitar IllegalStateException
 */
enum class RecorderState {
    IDLE,           // Initial state
    INITIALIZED,    // After setOutputFile()
    PREPARED,       // After prepare()
    RECORDING,      // After start()
    STOPPED,        // After stop()
    RELEASED,       // After release()
    ERROR           // Any error state
}

/**
 * Modelo de sesión de grabación completa
 */
@Parcelize
data class RecordingSession(
    val id: String,
    val startTimestamp: Long,
    val endTimestamp: Long? = null,
    val videoPath: String,
    val logPath: String,
    val eventsPath: String,
    val targetPackage: String? = null,
    val duration: Long = 0L
) : Parcelable

/**
 * Evento de interacción capturado por AccessibilityService
 */
data class AccessibilityEventLog(
    val timestamp: Long,           // Timestamp absoluto
    val relativeTime: Long,        // Milisegundos desde startTime
    val eventType: String,         // "CLICK", "TEXT_CHANGED", "SCROLL", etc.
    val packageName: String,
    val className: String?,
    val viewId: String?,           // Resource ID (ej. "com.app:id/btn_login")
    val coordinates: Pair<Int, Int>?, // X, Y
    val text: String?,             // Texto o "[REDACTED]" si es password
    val isPassword: Boolean = false,
    val additionalData: Map<String, String> = emptyMap()
)

/**
 * Línea de logcat filtrada
 */
data class LogcatEntry(
    val timestamp: Long,
    val relativeTime: Long,
    val level: String,             // V, D, I, W, E, F
    val tag: String,
    val pid: Int,
    val message: String
)

/**
 * Estado del sistema de grabación
 */
sealed class RecordingStatus {
    object Idle : RecordingStatus()
    object Initializing : RecordingStatus()
    data class Recording(val sessionId: String, val elapsedTime: Long) : RecordingStatus()
    object Stopping : RecordingStatus()
    data class Completed(val session: RecordingSession) : RecordingStatus()
    data class Error(val message: String, val exception: Throwable? = null) : RecordingStatus()
}

/**
 * Configuración de grabación
 */
data class RecordingConfig(
    val videoQuality: VideoQuality = VideoQuality.HIGH,
    val captureAudio: Boolean = false,
    val fps: Int = 30,
    val bitrate: Int = 8_000_000,
    val enableLogcat: Boolean = true,
    val targetPackageFilter: String? = null
)

enum class VideoQuality {
    LOW,    // 720p
    MEDIUM, // 1080p
    HIGH    // Native resolution
}
