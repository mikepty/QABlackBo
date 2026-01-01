package com.qa.blackbox

import android.app.Application
import timber.log.Timber

class BlackBoxApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Configurar Timber para logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // En producción, usa un árbol personalizado que envíe logs a un servicio
            Timber.plant(ReleaseTree())
        }
        
        Timber.i("QA BlackBox Application initialized")
    }

    /**
     * Árbol de logging para producción (sin logs sensibles)
     */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Solo log de errores en producción
            if (priority == android.util.Log.ERROR || priority == android.util.Log.WARN) {
                // Aquí podrías enviar a Firebase Crashlytics, Sentry, etc.
                android.util.Log.println(priority, tag ?: "BlackBox", message)
            }
        }
    }
}
