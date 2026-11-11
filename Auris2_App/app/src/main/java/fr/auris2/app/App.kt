package fr.auris2.app

import android.app.Application
import android.content.ContentValues
import android.provider.MediaStore
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/** Écrit tout plantage dans Download/Auris2/crash_AAAAMMJJ_HHMMSS.txt (pas d'adb nécessaire). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val nom = "crash_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, nom)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/Auris2")
                })
                uri?.let { u -> contentResolver.openOutputStream(u)?.use { it.write("Android ${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MODEL}\nThread ${t.name}\n$sw".toByteArray()) } }
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
}
