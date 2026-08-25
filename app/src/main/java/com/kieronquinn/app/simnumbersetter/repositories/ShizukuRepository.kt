package com.kieronquinn.app.simnumbersetter.repositories

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.kieronquinn.app.simnumbersetter.BuildConfig
import com.kieronquinn.app.simnumbersetter.IPrivilegedService
import com.kieronquinn.app.simnumbersetter.services.PrivilegedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

interface ShizukuRepository {

    suspend fun isAvailable(): Boolean
    suspend fun requestPermission(): Boolean
    suspend fun runCommand(command: Array<String>): Boolean

}

class ShizukuRepositoryImpl(
    context: Context,
    private val debugLogRepository: DebugLogRepository,
) : ShizukuRepository {

    companion object {
        private const val BINDER_TIMEOUT = 5_000L
        private const val SERVICE_TIMEOUT = 10_000L
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val SERVICE_VERSION = 1
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context, PrivilegedService::class.java),
    ).daemon(false)
        .processNameSuffix("shizuku")
        .debuggable(BuildConfig.DEBUG)
        .version(SERVICE_VERSION)

    override suspend fun isAvailable(): Boolean {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrElse {
            debugLogRepository.log("Shizuku", "pingBinder threw", it)
            false
        }
        debugLogRepository.log("Shizuku", "Initial binder alive=$binderAlive")
        if (binderAlive) return true
        val available = withTimeoutOrNull(BINDER_TIMEOUT) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : Shizuku.OnBinderReceivedListener {
                    override fun onBinderReceived() {
                        Shizuku.removeBinderReceivedListener(this)
                        if (continuation.isActive) continuation.resume(true)
                    }
                }
                Shizuku.addBinderReceivedListenerSticky(listener)
                continuation.invokeOnCancellation {
                    Shizuku.removeBinderReceivedListener(listener)
                }
            }
        } ?: false
        debugLogRepository.log("Shizuku", "Binder wait completed; available=$available")
        return available
    }

    override suspend fun requestPermission(): Boolean {
        return runCatching {
            if (Shizuku.isPreV11()) {
                debugLogRepository.log("Shizuku", "Permission unavailable: pre-v11 server")
                return false
            }
            val permission = Shizuku.checkSelfPermission()
            debugLogRepository.log("Shizuku", "Current permission result=$permission")
            if (permission == PackageManager.PERMISSION_GRANTED) return true
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                debugLogRepository.log("Shizuku", "Permission rationale required; treating as denied")
                return false
            }
            suspendCancellableCoroutine { continuation ->
                val listener = object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        if (requestCode != PERMISSION_REQUEST_CODE) return
                        Shizuku.removeRequestPermissionResultListener(this)
                        if (continuation.isActive) {
                            debugLogRepository.log(
                                "Shizuku",
                                "Permission request completed; result=$grantResult",
                            )
                            continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                        }
                    }
                }
                Shizuku.addRequestPermissionResultListener(listener)
                continuation.invokeOnCancellation {
                    Shizuku.removeRequestPermissionResultListener(listener)
                }
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        }.onFailure {
            debugLogRepository.log("Shizuku", "Permission request threw", it)
        }.getOrDefault(false)
    }

    override suspend fun runCommand(command: Array<String>): Boolean {
        debugLogRepository.log("Shizuku", "Running privileged command: ${command.joinToString(" ")}")
        val connection = connectUserService() ?: run {
            debugLogRepository.log("Shizuku", "User service connection failed or timed out")
            return false
        }
        return try {
            withContext(Dispatchers.IO) {
                val exitCode = connection.service.runCommand(command)
                debugLogRepository.log("Shizuku", "Privileged command exit code=$exitCode")
                exitCode == 0
            }
        } catch (e: Exception) {
            debugLogRepository.log("Shizuku", "Privileged command threw", e)
            false
        } finally {
            runCatching {
                Shizuku.unbindUserService(userServiceArgs, connection.connection, true)
            }
        }
    }

    private suspend fun connectUserService(): ConnectedService? {
        return withTimeoutOrNull(SERVICE_TIMEOUT) {
            suspendCancellableCoroutine { continuation ->
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        debugLogRepository.log(
                            "Shizuku",
                            "User service connected: $name, binderAlive=${binder.isBinderAlive}",
                        )
                        if (continuation.isActive) {
                            continuation.resume(
                                ConnectedService(IPrivilegedService.Stub.asInterface(binder), this),
                            )
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        debugLogRepository.log("Shizuku", "User service disconnected: $name")
                    }
                }
                continuation.invokeOnCancellation {
                    runCatching {
                        Shizuku.unbindUserService(userServiceArgs, connection, true)
                    }
                }
                runCatching {
                    Shizuku.bindUserService(userServiceArgs, connection)
                }.onFailure {
                    debugLogRepository.log("Shizuku", "bindUserService threw", it)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private data class ConnectedService(
        val service: IPrivilegedService,
        val connection: ServiceConnection,
    )

}
