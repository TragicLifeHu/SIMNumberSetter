package com.kieronquinn.app.simnumbersetter.utils.extensions

import android.view.View
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce

private const val CLICK_DEBOUNCE = 250L

@OptIn(FlowPreview::class)
fun View.onClicked() = callbackFlow<View> {
    setOnClickListener {
        trySend(it)
    }
    awaitClose {
        setOnClickListener(null)
    }
}.debounce(CLICK_DEBOUNCE)

fun View.hideIme(window: Window) {
    WindowCompat.getInsetsController(window, this).hide(WindowInsetsCompat.Type.ime())
}
