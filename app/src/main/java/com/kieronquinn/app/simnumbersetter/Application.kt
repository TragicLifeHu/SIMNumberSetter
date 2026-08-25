package com.kieronquinn.app.simnumbersetter

import android.app.Application
import com.kieronquinn.app.simnumbersetter.repositories.*
import com.kieronquinn.app.simnumbersetter.ui.screens.main.MainViewModel
import com.kieronquinn.app.simnumbersetter.ui.screens.main.MainViewModelImpl
import com.google.android.material.color.DynamicColors
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

class Application: Application() {

    private val repositories = module {
        single<DebugLogRepository> { DebugLogRepositoryImpl(get()) }
        single<AppUpdateRepository> { AppUpdateRepositoryImpl(get(), get()) }
        single<PermissionRepository> { PermissionRepositoryImpl(get(), get(), get()) }
        single<ShizukuRepository> { ShizukuRepositoryImpl(get(), get()) }
        single<ServiceRepository> { ServiceRepositoryImpl(get(), get()) }
    }

    private val viewModels = module {
        viewModel<MainViewModel> { MainViewModelImpl(get(), get(), get(), get(), get()) }
    }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        startKoin {
            androidContext(this@Application)
            modules(repositories, viewModels)
        }
        org.koin.java.KoinJavaComponent.get<DebugLogRepository>(DebugLogRepository::class.java)
            .log("App", "Application started")
    }

}
