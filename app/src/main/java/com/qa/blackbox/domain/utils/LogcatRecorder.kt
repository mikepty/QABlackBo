package com.qa.blackbox.domain.utils

import android.content.Context
import android.content.pm.PackageManager
import com.qa.blackbox.domain.models.LogcatEntry
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import timber.log.Timber
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

/**
 * Captura logs del sistema usando Shizuku (modo privilegiado)
 * Fallback: Comandos locales limitados si Shizuku no está disponible
 */
class LogcatRecorder(private val context: Context) {

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
        private val LOGCAT_PATTERN = Pattern.compile(
            """^(\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.+?):\s(.+)$"""
        )
    }

    private var isRecording = false
    private var recordingJob: Job? = null
    private var logWriter: BufferedWriter? = null
    private var recordingStartTime: Long = 0L
    private var targetPackageFilter: String? = null

    private val logcatProcess: Process? = null

    /**
     * Verifica si Shizuku está disponible y tiene permisos
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && checkShizukuPermission()
        } catch (e: Exception) {
            Timber.w(e, "Shizuku not available")
            false
        }
    }

    private fun checkShizukuPermission(): Boolean {
        return if (Shizuku.isPreV11()) {
            false
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Solicita permisos de Shizuku
     */
    fun requestShizukuPermission() {
        if (!Shizuku.isPreV11() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * Inicia la captura de logs
     */
    suspend fun startRecording(
        outputFile: File,
        sessionStartTime: Long,
        packageFilter: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (isRecording) {
            Timber.w("Already recording logcat")
            return@withContext false
        }

        try {
            recordingStartTime = sessionStartTime
            targetPackageFilter = packageFilter
            logWriter = BufferedWriter(FileWriter(outputFile))

            // Escribir header
            logWriter?.apply {
                write("# QA BlackBox Logcat Recording\n")
                write("# Session Start: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(sessionStartTime))}\n")
                packageFilter?.let { write("# Package Filter: $it\n") }
                write("#\n")
                flush()
            }

            isRecording = true

            // Intentar usar Shizuku primero
            val success = if (isShizukuAvailable()) {
                startShizukuLogcat()
            } else {
                Timber.w("Shizuku not available, using local logcat (limited)")
                startLocalLogcat()
            }

            if (success) {
                Timber.i("Logcat recording started")
            } else {
                cleanup()
            }

            success

        } catch (e: Exception) {
            Timber.e(e, "Failed to start logcat recording")
            cleanup()
            false
        }
    }

    private suspend fun startShizukuLogcat(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Construir comando logcat con filtros
            val command = buildString {
                append("logcat -v time")
                targetPackageFilter?.let { append(" --pid=\$(pidof -s $it)") }
                append(" *:V") // Todos los niveles
            }

            Timber.d("Executing Shizuku command: $command")

            // Ejecutar comando a través de Shizuku
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?

                    while (isActive && reader.readLine().also { line = it } != null) {
                        line?.let { processLogLine(it) }
                    }

                } catch (e: Exception) {
                    if (isActive) {
                        Timber.e(e, "Error reading Shizuku logcat stream")
                    }
                } finally {
                    try {
                        process.destroy()
                    } catch (e: Exception) {
                        Timber.w(e, "Error destroying process")
                    }
                }
            }

            true

        } catch (e: Exception) {
            Timber.e(e, "Failed to start Shizuku logcat")
            false
        }
    }

    private suspend fun startLocalLogcat(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Comando local sin filtro de PID (limitado, pero funcional)
            val command = arrayOf("logcat", "-v", "time")

            val process = Runtime.getRuntime().exec(command)

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?

                    while (isActive && reader.readLine().also { line = it } != null) {
                        line?.let { 
                            // Filtrar por paquete si está especificado
                            if (targetPackageFilter == null || it.contains(targetPackageFilter!!)) {
                                processLogLine(it)
                            }
                        }
                    }

                } catch (e: Exception) {
                    if (isActive) {
                        Timber.e(e, "Error reading local logcat stream")
                    }
                } finally {
                    try {
                        process.destroy()
                    } catch (e: Exception) {
                        Timber.w(e, "Error destroying process")
                    }
                }
            }

            true

        } catch (e: Exception) {
            Timber.e(e, "Failed to start local logcat")
            false
        }
    }

    private suspend fun processLogLine(line: String) = withContext(Dispatchers.IO) {
        try {
            val matcher = LOGCAT_PATTERN.matcher(line)

            if (matcher.matches()) {
                val timestamp = matcher.group(1) ?: return@withContext
                val pid = matcher.group(2)?.toIntOrNull() ?: 0
                val tid = matcher.group(3)?.toIntOrNull() ?: 0
                val level = matcher.group(4) ?: "V"
                val tag = matcher.group(5) ?: "Unknown"
                val message = matcher.group(6) ?: ""

                // Calcular tiempo relativo
                val relativeTime = System.currentTimeMillis() - recordingStartTime

                // Formatear y escribir
                val formattedLine = buildString {
                    append("[+${String.format("%06d", relativeTime)}ms] ")
                    append("$timestamp ")
                    append("$pid/$tid ")
                    append("$level/$tag: ")
                    append(message)
                }

                logWriter?.apply {
                    write(formattedLine)
                    newLine()
                    
                    // Flush cada 10 líneas para balance entre rendimiento y pérdida de datos
                    if (relativeTime % 10 == 0L) {
                        flush()
                    }
                }

            } else {
                // Línea no parseada (ej. stack traces)
                logWriter?.apply {
                    write(line)
                    newLine()
                }
            }

        } catch (e: Exception) {
            Timber.w(e, "Error processing log line")
        }
    }

    /**
     * Detiene la captura
     */
    suspend fun stopRecording() = withContext(Dispatchers.IO) {
        if (!isRecording) {
            Timber.w("Not recording")
            return@withContext
        }

        try {
            recordingJob?.cancelAndJoin()

            logWriter?.apply {
                write("\n# Session End: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
                flush()
                close()
            }

            Timber.i("Logcat recording stopped")

        } catch (e: Exception) {
            Timber.e(e, "Error stopping logcat recording")
        } finally {
            cleanup()
        }
    }

    private fun cleanup() {
        isRecording = false
        recordingJob = null
        logWriter = null
        recordingStartTime = 0L
        targetPackageFilter = null
    }

    /**
     * Parsea una línea de logcat a objeto estructurado
     */
    fun parseLogcatLine(line: String, baseTimestamp: Long): LogcatEntry? {
        val matcher = LOGCAT_PATTERN.matcher(line)
        if (!matcher.matches()) return null

        return try {
            LogcatEntry(
                timestamp = System.currentTimeMillis(), // Aproximado
                relativeTime = System.currentTimeMillis() - baseTimestamp,
                level = matcher.group(4) ?: "V",
                tag = matcher.group(5) ?: "Unknown",
                pid = matcher.group(2)?.toIntOrNull() ?: 0,
                message = matcher.group(6) ?: ""
            )
        } catch (e: Exception) {
            Timber.w(e, "Error parsing logcat line")
            null
        }
    }
}
