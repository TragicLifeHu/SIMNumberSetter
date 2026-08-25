package com.kieronquinn.app.simnumbersetter

import android.content.Intent
import com.kieronquinn.app.simnumbersetter.service.PhoneNumberSetterService
import com.kieronquinn.app.simnumbersetter.utils.extensions.checkSecurity
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 *  Xposed hook to allow binding to TelephonyDebugService (an exported, but protected service in
 *  com.android.phone), returning [IPhoneNumberSetter]. This can then be accessed via the UI
 *  and used to query and set the number.
 */
class Xposed : XposedModule() {

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != PHONE_PACKAGE) return

        val serviceClass = Class.forName(TELEPHONY_DEBUG_SERVICE, false, param.classLoader)
        val onBind = serviceClass.getDeclaredMethod("onBind", Intent::class.java)
        hook(onBind).intercept { chain ->
            val intent = chain.getArg(0) as Intent
            if (intent.checkSecurity()) {
                PhoneNumberSetterService()
            } else {
                chain.proceed()
            }
        }
    }

    private companion object {
        const val PHONE_PACKAGE = "com.android.phone"
        const val TELEPHONY_DEBUG_SERVICE = "com.android.phone.TelephonyDebugService"
    }
}
