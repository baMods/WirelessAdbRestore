package com.bamods.adbrestore.utils

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("adb_restore_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_CONNECT_PORT = "last_connect_port"
        private const val KEY_LAST_PAIRING_PORT = "last_pairing_port"
        private const val KEY_LAST_WA_PATH = "last_wa_path"
        private const val KEY_IS_PAIRED = "is_paired"
    }

    var lastConnectPort: Int
        get() = prefs.getInt(KEY_LAST_CONNECT_PORT, 5555)
        set(value) = prefs.edit().putInt(KEY_LAST_CONNECT_PORT, value).apply()

    var lastPairingPort: Int
        get() = prefs.getInt(KEY_LAST_PAIRING_PORT, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_PAIRING_PORT, value).apply()

    var lastWaPath: String
        get() = prefs.getString(KEY_LAST_WA_PATH, "/sdcard/wa.ab") ?: "/sdcard/wa.ab"
        set(value) = prefs.edit().putString(KEY_LAST_WA_PATH, value).apply()

    var isPaired: Boolean
        get() = prefs.getBoolean(KEY_IS_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_PAIRED, value).apply()
}
