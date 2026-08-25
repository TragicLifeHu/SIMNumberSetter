package com.kieronquinn.app.simnumbersetter.repositories

import android.content.Context
import android.os.Build
import android.util.Log
import com.kieronquinn.app.simnumbersetter.BuildConfig
import java.io.File
import java.time.Instant

interface DebugLogRepository {

    fun log(component: String, message: String, throwable: Throwable? = null)
    fun export(): String

}

class DebugLogRepositoryImpl(context: Context) : DebugLogRepository {

    companion object {
        private const val TAG = "SIMNumberSetter"
        private const val MAX_LOG_BYTES = 128 * 1024L
    }

    private val logFile = File(context.filesDir, "debug.log")
    private val lock = Any()

    override fun log(component: String, message: String, throwable: Throwable?) {
        val entry = buildString {
            append(Instant.now())
            append(" [")
            append(component)
            append("] ")
            append(message)
            throwable?.let {
                append(" (")
                append(it.javaClass.name)
                it.message?.let { errorMessage ->
                    append(": ")
                    append(errorMessage.replace('\n', ' '))
                }
                append(')')
            }
            appendLine()
        }
        Log.d(TAG, entry.trimEnd(), throwable)
        synchronized(lock) {
            runCatching {
                if (logFile.length() > MAX_LOG_BYTES) {
                    logFile.writeText("Log truncated at ${Instant.now()}\n")
                }
                logFile.appendText(entry)
            }.onFailure {
                Log.w(TAG, "Unable to write debug log", it)
            }
        }
    }

    override fun export(): String = buildString {
        appendLine("SIM Number Setter debug log")
        appendLine("Exported: ${Instant.now()}")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), debug=${BuildConfig.DEBUG}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("---")
        append(
            synchronized(lock) {
                runCatching { logFile.readText() }.getOrElse {
                    "Unable to read debug log: ${it.javaClass.name}: ${it.message}"
                }
            },
        )
    }

}
