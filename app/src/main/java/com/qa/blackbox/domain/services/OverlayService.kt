
package com.qa.blackbox.domain.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.qa.blackbox.R
import timber.log.Timber

/**
 * Servicio que muestra un botón flotante para controlar la grabación
 * Usa TYPE_APPLICATION_OVERLAY (Android 8+)
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.qa.blackbox.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.qa.blackbox.HIDE_OVERLAY"
        
        private const val INITIAL_X = 0
        private const val INITIAL_Y = 200
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isShowing = false
    private var isRecording = false

    private val recordingStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val recording = intent?.getBooleanExtra("is_recording", false) ?: false
            updateRecordingState(recording)
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Registrar receiver para actualizar el estado visual
        val filter = IntentFilter("com.qa.blackbox.RECORDING_STATUS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(recordingStatusReceiver, filter)
        }
        
        Timber.d("OverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> hideOverlay()
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (isShowing) {
            Timber.w("Overlay already showing")
            return
        }

        try {
            // Inflar layout del botón flotante
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_control_button, null)
            
            val recordButton = overlayView?.findViewById<ImageView>(R.id.btn_record)
            
            // Configurar parámetros de ventana
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = INITIAL_X
                y = INITIAL_Y
            }

            // Variables para manejar drag
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isDragging = false

            overlayView?.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        
                        // Considerar drag si se movió más de 10px
                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            isDragging = true
                            params.x = initialX + deltaX.toInt()
                            params.y = initialY + deltaY.toInt()
                            windowManager?.updateViewLayout(overlayView, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            // Fue un click, no un drag
                            toggleRecording()
                        }
                        true
                    }
                    else -> false
                }
            }

            // Agregar vista a WindowManager
            windowManager?.addView(overlayView, params)
            isShowing = true
            
            updateButtonAppearance(recordButton)
            
            Timber.i("Overlay shown")

        } catch (e: Exception) {
            Timber.e(e, "Failed to show overlay")
        }
    }

    private fun hideOverlay() {
        if (!isShowing || overlayView == null) {
            return
        }

        try {
            windowManager?.removeView(overlayView)
            overlayView = null
            isShowing = false
            Timber.i("Overlay hidden")
        } catch (e: Exception) {
            Timber.e(e, "Failed to hide overlay")
        }
    }

    private fun toggleRecording() {
        if (isRecording) {
            // Detener grabación
            val intent = Intent(this, ScreenRecordingService::class.java).apply {
                action = ScreenRecordingService.ACTION_STOP
            }
            startService(intent)
        } else {
            // El inicio se maneja desde la actividad principal debido a MediaProjection
            Timber.w("Start recording should be initiated from MainActivity")
            
            // Enviar broadcast para abrir la app
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent?.let { startActivity(it) }
        }
    }

    private fun updateRecordingState(recording: Boolean) {
        isRecording = recording
        val recordButton = overlayView?.findViewById<ImageView>(R.id.btn_record)
        updateButtonAppearance(recordButton)
    }

    private fun updateButtonAppearance(button: ImageView?) {
        button?.apply {
            if (isRecording) {
                setImageResource(R.drawable.ic_stop)
                setColorFilter(ContextCompat.getColor(context, android.R.color.holo_red_dark))
            } else {
                setImageResource(R.drawable.ic_record)
                setColorFilter(ContextCompat.getColor(context, android.R.color.holo_red_light))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideOverlay()
        try {
            unregisterReceiver(recordingStatusReceiver)
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering receiver")
        }
        super.onDestroy()
        Timber.d("OverlayService destroyed")
    }
}
