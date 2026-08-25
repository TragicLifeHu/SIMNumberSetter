package com.kieronquinn.app.simnumbersetter.service

import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import com.android.internal.telephony.Phone
import com.android.internal.telephony.PhoneFactory
import com.kieronquinn.app.simnumbersetter.IPhoneNumberSetter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 *  Proxy Service for calls to [Phone] methods to be called via the [IPhoneNumberSetter] service.
 */
class PhoneNumberSetterService: IPhoneNumberSetter.Stub() {

    companion object {
        const val SET_RESULT_SUCCESS = 0
        const val SET_RESULT_UNSUPPORTED = 1
        const val SET_RESULT_AUTHENTICATION_REQUIRED = 2
        const val SET_RESULT_FAILED = 3
        const val SET_RESULT_TIMEOUT = 4

        private const val SET_TIMEOUT_SECONDS = 30L
    }

    override fun getActivePhoneIds(): IntArray {
        return PhoneFactory.getPhones().mapNotNull { phone ->
            phone.phoneId.takeIf {
                phone.subId != SubscriptionManager.DEFAULT_SUBSCRIPTION_ID &&
                    SubscriptionManager.isValidSubscriptionId(phone.subId)
            }
        }.toIntArray()
    }

    override fun getDefaultPhoneId(): Int {
        return PhoneFactory.getDefaultPhone().phoneId
    }

    override fun setLine1Number(phoneId: Int, number: String): Int {
        val phone = getPhone(phoneId) ?: return SET_RESULT_UNSUPPORTED
        var tag = phone.line1AlphaTag
        if(tag.isNullOrEmpty()){
            tag = "Voice Line 1"
        }
        val completed = CountDownLatch(1)
        var exception: Throwable? = null
        val callback = Handler(Looper.getMainLooper()) { message ->
            exception = message.obj?.let { asyncResult ->
                runCatching {
                    asyncResult.javaClass.getField("exception").get(asyncResult) as? Throwable
                }.getOrElse { it }
            } ?: IllegalStateException("Telephony callback did not contain an AsyncResult")
            completed.countDown()
            true
        }.obtainMessage()
        if (!phone.setLine1Number(tag, number, callback)) {
            return SET_RESULT_UNSUPPORTED
        }
        if (!completed.await(SET_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return SET_RESULT_TIMEOUT
        }
        return when {
            exception == null -> SET_RESULT_SUCCESS
            exception.isAuthenticationFailure() -> SET_RESULT_AUTHENTICATION_REQUIRED
            else -> SET_RESULT_FAILED
        }
    }

    override fun getLine1Number(phoneId: Int): String? {
        return getPhone(phoneId)?.line1Number
    }

    private fun getPhone(phoneId: Int): Phone? {
        return PhoneFactory.getPhones().getOrNull(phoneId)
    }

    private fun Throwable?.isAuthenticationFailure(): Boolean {
        val details = generateSequence(this) { it.cause }
            .joinToString(" ") { "${it.javaClass.name} ${it.message.orEmpty()}" }
            .lowercase()
        return listOf(
            "adm",
            "authentication",
            "password",
            "pin2",
            "puk2",
            "security status",
        ).any(details::contains)
    }

}
