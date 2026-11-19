package com.droidbridge.ui.common

import android.app.Application
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList

/**
 * DroidBridge editions follow the device's system language.
 *
 * Clear any legacy per-app override so the generated English, Simplified Chinese and Japanese
 * locale resources are selected from the current system locale instead of a stale app preference.
 */
fun Application.followSystemLocale() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val manager = getSystemService(LocaleManager::class.java) ?: return
        if (!manager.applicationLocales.isEmpty) {
            manager.applicationLocales = LocaleList.getEmptyLocaleList()
        }
    }
}
