package com.bamods.adbrestore

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.bamods.adbrestore.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PairingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.bamods.adbrestore.ACTION_PAIR") {
            val remoteInput = RemoteInput.getResultsFromIntent(intent)
            val pairingCode = remoteInput?.getCharSequence("pairing_code_input")?.toString()?.trim()

            if (!pairingCode.isNullOrEmpty()) {
                val prefs = PrefsManager(context)
                
                val host = "127.0.0.1"
                val pairingPort = prefs.lastPairingPort
                val connectPort = prefs.lastConnectPort
                
                val adbPath = context.applicationInfo.nativeLibraryDir + "/libadb.so"
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                
                // Show "Pairing in progress" notification
                val builder = NotificationCompat.Builder(context, "pairing_channel")
                    .setSmallIcon(R.drawable.ic_app_logo)
                    .setContentTitle(context.getString(R.string.notif_pairing_title))
                    .setContentText(context.getString(R.string.notif_pairing_progress))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOngoing(true)
                
                notificationManager.notify(1001, builder.build())

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val process = ProcessBuilder(adbPath, "pair", "$host:$pairingPort", pairingCode)
                            .redirectErrorStream(true)
                            .start()

                        val output = process.inputStream.bufferedReader().use { it.readText() }
                        process.waitFor()

                        withContext(Dispatchers.Main) {
                            if (output.contains("Successfully paired", ignoreCase = true) || output.contains("success", ignoreCase = true)) {
                                prefs.isPaired = true
                                
                                builder.setContentText(context.getString(R.string.notif_pairing_success))
                                    .setOngoing(false)
                                notificationManager.notify(1001, builder.build())
                                
                                // Broadcast success back to MainActivity
                                val successIntent = Intent("com.bamods.adbrestore.PAIRING_SUCCESS")
                                context.sendBroadcast(successIntent)
                            } else {
                                builder.setContentText(context.getString(R.string.notif_pairing_failed))
                                    .setOngoing(false)
                                notificationManager.notify(1001, builder.build())
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            builder.setContentText(context.getString(R.string.notif_pairing_error))
                                .setOngoing(false)
                            notificationManager.notify(1001, builder.build())
                        }
                    }
                }
            }
        }
    }
}
