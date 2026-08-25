package com.kieronquinn.app.simnumbersetter.ui.screens.main

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kieronquinn.app.simnumbersetter.R
import com.kieronquinn.app.simnumbersetter.repositories.AppUpdateRepository
import com.kieronquinn.app.simnumbersetter.repositories.DebugLogRepository
import com.kieronquinn.app.simnumbersetter.repositories.PermissionRepository
import com.kieronquinn.app.simnumbersetter.repositories.ServiceRepository
import com.kieronquinn.app.simnumbersetter.repositories.ShizukuRepository
import com.kieronquinn.app.simnumbersetter.service.PhoneNumberSetterService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.lang.Exception

abstract class MainViewModel : ViewModel() {

    abstract val state: StateFlow<State>
    abstract val number: StateFlow<String?>
    abstract val messages: SharedFlow<Message>
    abstract fun onNumberChanged(newNumber: String)
    abstract fun onPhoneSelected(phoneId: Int)
    abstract fun onRefreshClicked()
    abstract fun onSaveClicked()
    abstract fun onMenuItemClicked(context: Context, id: Int)
    abstract fun prepareDebugLogExport(): String
    abstract fun consumeDebugLogExport(): String?
    abstract fun onDebugLogExportResult(error: Throwable?)

    sealed class State {
        data class Loading(val loadType: LoadType) : State()
        data class Loaded(
            internal val number: String,
            internal val phoneId: Int,
            internal val activePhoneIds: List<Int>,
            internal val phoneNumbers: Map<Int, String>,
        ) : State()
        data class Error(val errorType: ErrorType) : State()
    }

    enum class ErrorType(@param:StringRes val messageRes: Int) {
        NO_SHIZUKU(R.string.error_no_shizuku),
        NO_SHIZUKU_PERMISSION(R.string.error_no_shizuku_permission),
        NO_PERMISSION(R.string.error_no_permission),
        XPOSED_REBOOT_REQUIRED(R.string.error_xposed_reboot_required),
        NO_XPOSED_SERVICE(R.string.error_no_xposed_service),
        XPOSED_CALL_FAILED(R.string.error_xposed_call_failed),
        NO_ACTIVE_SIM(R.string.error_no_active_sim),
    }

    enum class Message(
        @param:StringRes val titleRes: Int,
        @param:StringRes val contentRes: Int,
    ) {
        SAVE_FAILED(R.string.save_failed_title, R.string.save_failed_message),
    }

    enum class LoadType(@param:StringRes val contentRes: Int) {
        LOADING(R.string.loading_loading),
        REFRESHING(R.string.loading_refreshing),
        SAVING(R.string.loading_saving),
    }

}

class MainViewModelImpl(
    private val appUpdateRepository: AppUpdateRepository,
    private val shizukuRepository: ShizukuRepository,
    private val permissionRepository: PermissionRepository,
    private val serviceRepository: ServiceRepository,
    private val debugLogRepository: DebugLogRepository,
) : MainViewModel() {

    companion object {
        private const val URL_GITHUB = "https://kieronquinn.co.uk/redirect/SNS/github"
        private const val URL_DONATE = "https://kieronquinn.co.uk/redirect/SNS/donate"
        private const val SAVE_VERIFICATION_DELAY_MILLIS = 1000L
    }

    private val customNumber = MutableStateFlow<String?>(null)
    private val mutableMessages = MutableSharedFlow<Message>()
    private var selectedPhoneId: Int? = null
    private var pendingDebugLogExport: String? = null

    override val state = MutableStateFlow<State>(State.Loading(LoadType.LOADING))
    override val messages = mutableMessages.asSharedFlow()
    override val number = combine(customNumber, state) { n, s ->
        when {
            n != null -> n
            s is State.Loaded -> s.number
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    override fun onNumberChanged(newNumber: String) {
        viewModelScope.launch {
            customNumber.emit(newNumber)
        }
    }

    override fun onSaveClicked() {
        save(number.value ?: "")
    }

    override fun onMenuItemClicked(context: Context, id: Int) {
        val url = when(id) {
            R.id.menu_github -> URL_GITHUB
            R.id.menu_donate -> URL_DONATE
            else -> return
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
            })
        }catch (e: ActivityNotFoundException) {
            //Nothing can be done
        }
    }

    override fun onPhoneSelected(phoneId: Int) {
        if (phoneId == selectedPhoneId) return
        selectedPhoneId = phoneId
        viewModelScope.launch {
            customNumber.emit(null)
            load()
        }
    }

    override fun onRefreshClicked() {
        viewModelScope.launch {
            customNumber.emit(null)
            load(LoadType.REFRESHING)
        }
    }

    override fun prepareDebugLogExport(): String {
        debugLogRepository.log("UI", "Debug log export requested")
        val timestamp = java.time.Instant.now().toString().replace(':', '-')
        pendingDebugLogExport = debugLogRepository.export()
        return "$timestamp.log"
    }

    override fun consumeDebugLogExport(): String? {
        return pendingDebugLogExport.also { pendingDebugLogExport = null }
    }

    override fun onDebugLogExportResult(error: Throwable?) {
        if (error == null) {
            debugLogRepository.log("UI", "Debug log exported successfully")
        } else {
            debugLogRepository.log("UI", "Debug log export failed", error)
        }
    }

    private fun load(loadType: LoadType = LoadType.LOADING) = viewModelScope.launch {
        debugLogRepository.log("Main", "Starting load")
        state.emit(State.Loading(loadType))
        if (appUpdateRepository.isRebootRequired()) {
            debugLogRepository.log("Main", "Load stopped: app was installed or updated since boot")
            state.emit(State.Error(ErrorType.XPOSED_REBOOT_REQUIRED))
            return@launch
        }
        if (!shizukuRepository.isAvailable()) {
            debugLogRepository.log("Main", "Load stopped: Shizuku unavailable")
            state.emit(State.Error(ErrorType.NO_SHIZUKU))
            return@launch
        }
        if (!shizukuRepository.requestPermission()) {
            debugLogRepository.log("Main", "Load stopped: Shizuku permission unavailable")
            state.emit(State.Error(ErrorType.NO_SHIZUKU_PERMISSION))
            return@launch
        }
        if (!permissionRepository.grantDumpPermission()) {
            debugLogRepository.log("Main", "Load stopped: DUMP permission unavailable")
            state.emit(State.Error(ErrorType.NO_PERMISSION))
            return@launch
        }
        val sim = when (val result = serviceRepository.runWithService { service ->
            val activePhoneIds = service.activePhoneIds.toList()
            if (activePhoneIds.isEmpty()) return@runWithService null
            val phoneId = (selectedPhoneId ?: service.defaultPhoneId)
                .takeIf { it in activePhoneIds } ?: activePhoneIds.first()
            val numbers = activePhoneIds.associateWith {
                service.getLine1Number(it).orEmpty()
            }
            SimState(phoneId, numbers)
        }) {
            ServiceRepository.Result.Unavailable -> {
                debugLogRepository.log("Main", "Load stopped: Xposed service unavailable")
                state.emit(State.Error(ErrorType.NO_XPOSED_SERVICE))
                return@launch
            }
            is ServiceRepository.Result.CallFailed -> {
                debugLogRepository.log("Main", "Load stopped: Xposed binder call failed")
                state.emit(State.Error(ErrorType.XPOSED_CALL_FAILED))
                return@launch
            }
            is ServiceRepository.Result.Success -> result.value ?: run {
                debugLogRepository.log("Main", "Load stopped: no active SIM")
                state.emit(State.Error(ErrorType.NO_ACTIVE_SIM))
                return@launch
            }
        }
        selectedPhoneId = sim.phoneId
        debugLogRepository.log(
            "Main",
            "Load completed for active phone IDs ${sim.activePhoneIds}; phoneId=${sim.phoneId}",
        )
        state.emit(
            State.Loaded(sim.number, sim.phoneId, sim.activePhoneIds, sim.phoneNumbers),
        )
    }

    private fun save(number: String) = viewModelScope.launch {
        val previousState = state.value as? State.Loaded ?: return@launch
        state.emit(State.Loading(LoadType.SAVING))
        val phoneId = selectedPhoneId ?: 0
        val writeResult = serviceRepository.runWithService {
            it.setLine1Number(phoneId, number)
        }
        debugLogRepository.log(
            "Main",
            "SIM write call completed with result ${writeResult.resultCodeOrNull()}; " +
                "verifying stored number regardless of reported result",
        )
        delay(SAVE_VERIFICATION_DELAY_MILLIS)
        val refreshedSimResult = serviceRepository.runWithService { service ->
            val activePhoneIds = service.activePhoneIds.toList()
            if (activePhoneIds.isEmpty()) return@runWithService null
            val refreshedPhoneId = phoneId.takeIf { it in activePhoneIds }
                ?: activePhoneIds.first()
            val numbers = activePhoneIds.associateWith {
                service.getLine1Number(it).orEmpty()
            }
            SimState(refreshedPhoneId, numbers)
        }
        if (refreshedSimResult is ServiceRepository.Result.Success) {
            val refreshedSim = refreshedSimResult.value
            if (refreshedSim == null) {
                customNumber.emit(null)
                debugLogRepository.log("Main", "No active SIM remained after save")
                state.emit(State.Error(ErrorType.NO_ACTIVE_SIM))
                return@launch
            }
            selectedPhoneId = refreshedSim.phoneId
            if (refreshedSim.number == number) {
                customNumber.emit(null)
                debugLogRepository.log(
                    "Main",
                    "Save verified successfully from SIM after callback result ${writeResult.resultCodeOrNull()}",
                )
                state.emit(
                    State.Loaded(
                        refreshedSim.number,
                        refreshedSim.phoneId,
                        refreshedSim.activePhoneIds,
                        refreshedSim.phoneNumbers,
                    ),
                )
                return@launch
            }
            debugLogRepository.log("Main", "Save verification did not match the submitted number")
            state.emit(
                State.Loaded(
                    refreshedSim.number,
                    refreshedSim.phoneId,
                    refreshedSim.activePhoneIds,
                    refreshedSim.phoneNumbers,
                ),
            )
        } else {
            debugLogRepository.log("Main", "Unable to re-query SIM number after save")
            state.emit(previousState)
        }

        if (number == previousState.number) {
            debugLogRepository.log(
                "Main",
                "Submitted number matched the original value; write result is inconclusive",
            )
            return@launch
        }
        debugLogRepository.log(
            "Main",
            "SIM did not retain the number update; showing non-blocking possible-causes message",
        )
        mutableMessages.emit(Message.SAVE_FAILED)
    }

    private fun ServiceRepository.Result<Int>.resultCodeOrNull(): Int? {
        return (this as? ServiceRepository.Result.Success)?.value
    }

    init {
        load()
    }

    override fun onCleared() {
        serviceRepository.unbindServiceIfNeeded()
        super.onCleared()
    }

    private data class SimState(
        val phoneId: Int,
        val phoneNumbers: Map<Int, String>,
    ) {
        val activePhoneIds = phoneNumbers.keys.toList()
        val number = phoneNumbers[phoneId].orEmpty()
    }

}
