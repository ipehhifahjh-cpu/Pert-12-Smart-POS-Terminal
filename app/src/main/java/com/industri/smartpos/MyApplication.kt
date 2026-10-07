package com.industri.smartpos

import android.app.Application
import timber.log.Timber

/**
 * Inisialisasi konfigurasi aplikasi dan logging Timber
 * Sesuai ketentuan modul Pertemuan 12: Timber.plant(Timber.DebugTree()) khusus saat BuildConfig.DEBUG
 */
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Timber Logging Setup khusus kondisi DEBUG
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("Timber logging berhasil diinisialisasi dalam mode DEBUG.")
        }
    }
}
