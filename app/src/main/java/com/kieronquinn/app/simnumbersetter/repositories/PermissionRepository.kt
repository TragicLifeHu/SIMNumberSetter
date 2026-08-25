package com.kieronquinn.app.simnumbersetter.repositories

import android.content.Context
import android.content.pm.PackageManager
import com.kieronquinn.app.simnumbersetter.BuildConfig
import com.kieronquinn.app.simnumbersetter.repositories.PermissionRepository.Companion.PERMISSION_DUMP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface PermissionRepository {

    companion object {
        internal const val PERMISSION_DUMP = "android.permission.DUMP"
    }

    fun hasDumpPermission(): Boolean

    suspend fun grantDumpPermission(): Boolean

}

class PermissionRepositoryImpl(
    private val context: Context,
    private val shizukuRepository: ShizukuRepository,
    private val debugLogRepository: DebugLogRepository,
): PermissionRepository {

    override fun hasDumpPermission(): Boolean {
        return context.checkCallingOrSelfPermission(PERMISSION_DUMP) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun runGrantCommand() {
        shizukuRepository.runCommand(
            arrayOf("pm", "grant", BuildConfig.APPLICATION_ID, PERMISSION_DUMP),
        )
    }

    override suspend fun grantDumpPermission(): Boolean {
        return withContext(Dispatchers.IO) {
            val alreadyGranted = hasDumpPermission()
            debugLogRepository.log("Permission", "DUMP permission initially granted=$alreadyGranted")
            if (!alreadyGranted) {
                runGrantCommand()
                hasDumpPermission().also {
                    debugLogRepository.log("Permission", "DUMP permission after grant=$it")
                }
            } else true
        }
    }

}
