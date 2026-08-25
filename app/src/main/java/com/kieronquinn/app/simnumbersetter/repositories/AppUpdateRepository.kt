package com.kieronquinn.app.simnumbersetter.repositories

import android.content.Context
import android.os.SystemClock

interface AppUpdateRepository {

    fun isRebootRequired(): Boolean

}

class AppUpdateRepositoryImpl(
    private val context: Context,
    private val debugLogRepository: DebugLogRepository,
) : AppUpdateRepository {

    override fun isRebootRequired(): Boolean {
        return runCatching {
            val lastUpdateTime = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .lastUpdateTime
            val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            (lastUpdateTime > bootTime).also { rebootRequired ->
                debugLogRepository.log(
                    "AppUpdate",
                    "Package updated since boot=$rebootRequired",
                )
            }
        }.onFailure {
            debugLogRepository.log("AppUpdate", "Unable to check package update time", it)
        }.getOrDefault(false)
    }

}
