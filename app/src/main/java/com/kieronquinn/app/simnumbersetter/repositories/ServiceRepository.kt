package com.kieronquinn.app.simnumbersetter.repositories

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.kieronquinn.app.simnumbersetter.IPhoneNumberSetter
import com.kieronquinn.app.simnumbersetter.utils.extensions.applySecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.IllegalArgumentException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

interface ServiceRepository {

    sealed interface Result<out T> {
        data class Success<T>(val value: T) : Result<T>
        data object Unavailable : Result<Nothing>
        data class CallFailed(val exception: Exception) : Result<Nothing>
    }

    suspend fun <T> runWithService(block: suspend (IPhoneNumberSetter) -> T): Result<T>
    fun <T> runWithServiceIfAvailable(block: (IPhoneNumberSetter) -> T?): T?
    fun unbindServiceIfNeeded()

}

class ServiceRepositoryImpl(
    private val context: Context,
    private val debugLogRepository: DebugLogRepository,
): ServiceRepository {

    companion object {
        private val SERVICE_TIMEOUT = TimeUnit.SECONDS.toMillis(10)
    }

    private var serviceInstance: IPhoneNumberSetter? = null
    private var serviceConnection: ServiceConnection? = null
    private val getServiceMutex = Mutex()

    private val serviceIntent = Intent("com.android.phone.TelephonyDebugService").apply {
        `package` = "com.android.phone"
        applySecurity(context)
    }

    override suspend fun <T> runWithService(block: suspend (IPhoneNumberSetter) -> T): ServiceRepository.Result<T> {
        val service = withContext(Dispatchers.IO) {
            getServiceLocked()
        } ?: return ServiceRepository.Result.Unavailable
        return try {
            ServiceRepository.Result.Success(block(service))
        } catch (e: Exception) {
            debugLogRepository.log("XposedService", "Binder call threw", e)
            ServiceRepository.Result.CallFailed(e)
        }
    }

    override fun <T> runWithServiceIfAvailable(block: (IPhoneNumberSetter) -> T?): T? {
        return serviceInstance?.let {
            block(it)
        }
    }

    override fun unbindServiceIfNeeded() {
        serviceConnection?.let {
            try {
                context.unbindService(it)
                debugLogRepository.log("XposedService", "Service unbound")
            } catch (e: IllegalArgumentException) {
                debugLogRepository.log("XposedService", "Service was already unbound", e)
            }
        }
        serviceConnection = null
        serviceInstance = null
    }

    private suspend fun getServiceLocked(): IPhoneNumberSetter? = getServiceMutex.withLock {
        serviceInstance?.takeIf { it.asBinder().isBinderAlive }?.let {
            debugLogRepository.log("XposedService", "Reusing live binder")
            return@withLock it
        }
        serviceInstance = null

        val resolvedService = runCatching {
            context.packageManager.resolveService(serviceIntent, 0)?.serviceInfo
        }.onFailure {
            debugLogRepository.log("XposedService", "Service resolution threw", it)
        }.getOrNull()
        debugLogRepository.log(
            "XposedService",
            if (resolvedService == null) {
                "TelephonyDebugService did not resolve"
            } else {
                "Resolved ${resolvedService.packageName}/${resolvedService.name}; " +
                    "exported=${resolvedService.exported}, permission=${resolvedService.permission}"
            },
        )

        val result = withContext(Dispatchers.Main.immediate) {
            connectService()
        }
        if (result == null) {
            debugLogRepository.log("XposedService", "Connection failed or timed out after ${SERVICE_TIMEOUT}ms")
        }
        result
    }

    private suspend fun connectService(): IPhoneNumberSetter? {
        return withTimeoutOrNull(SERVICE_TIMEOUT) {
            suspendCancellableCoroutine { continuation ->
                var bound = false
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(component: ComponentName, binder: IBinder) {
                        debugLogRepository.log(
                            "XposedService",
                            "Connected to $component; alive=${binder.isBinderAlive}, " +
                                "descriptor=${runCatching { binder.interfaceDescriptor }.getOrNull()}",
                        )
                        if (!continuation.isActive) {
                            runCatching { context.unbindService(this) }
                            return
                        }
                        val service = IPhoneNumberSetter.Stub.asInterface(binder)
                        serviceInstance = service
                        this@ServiceRepositoryImpl.serviceConnection = this
                        continuation.resume(service)
                    }

                    override fun onServiceDisconnected(component: ComponentName) {
                        debugLogRepository.log("XposedService", "Disconnected from $component")
                        serviceInstance = null
                        this@ServiceRepositoryImpl.serviceConnection = null
                    }

                    override fun onBindingDied(component: ComponentName) {
                        debugLogRepository.log("XposedService", "Binding died for $component")
                        serviceInstance = null
                        this@ServiceRepositoryImpl.serviceConnection = null
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onNullBinding(component: ComponentName) {
                        debugLogRepository.log("XposedService", "Received null binding from $component")
                        runCatching { context.unbindService(this) }
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                continuation.invokeOnCancellation {
                    if (bound && this@ServiceRepositoryImpl.serviceConnection !== connection) {
                        runCatching { context.unbindService(connection) }
                    }
                }
                try {
                    bound = context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
                    debugLogRepository.log("XposedService", "bindService returned $bound")
                    if (!bound && continuation.isActive) continuation.resume(null)
                } catch (e: Exception) {
                    debugLogRepository.log("XposedService", "bindService threw", e)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

}
