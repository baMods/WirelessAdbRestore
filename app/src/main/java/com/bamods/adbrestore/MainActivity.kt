package com.bamods.adbrestore

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bamods.adbrestore.databinding.ActivityMainBinding
import com.bamods.adbrestore.ui.PairingDialog
import com.bamods.adbrestore.utils.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager

    private val adbPath: String
        get() = applicationInfo.nativeLibraryDir + "/libadb.so"

    private fun checkPermissions() {
        // Request Notification Permission for Android 13+ (Required for Shizuku-style pairing)
        if (Build.VERSION.SDK_INT >= 33) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
            }
        }

        // Request File Management Permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (ignored: Exception) {}
            }
        } else {
            val perms = arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val needed = perms.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                androidx.core.app.ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pairingReceiver)
        } catch (e: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)
        
        checkPermissions()
        setupUI()

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pairingReceiver, android.content.IntentFilter("com.bamods.adbrestore.PAIRING_SUCCESS"), android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pairingReceiver, android.content.IntentFilter("com.bamods.adbrestore.PAIRING_SUCCESS"))
        }
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", adbPath)).waitFor()
            } catch (ignored: Exception) {}
        }
    }

    private val pairingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == "com.bamods.adbrestore.PAIRING_SUCCESS") {
                Toast.makeText(this@MainActivity, getString(R.string.notif_pairing_success), Toast.LENGTH_SHORT).show()
                connectAdb(prefs.lastConnectPort, prefs.lastHost)
            }
        }
    }

    private fun setupUI() {
        binding.btnPairing.setOnClickListener {
            startShizukuPairing()
        }

        binding.btnQuickConnect.setOnClickListener {
            connectAdb(prefs.lastConnectPort, prefs.lastHost)
        }

        binding.btnInstallOldWa.setOnClickListener {
            installOldWhatsApp()
        }

        binding.btnRestoreWhatsApp.setOnClickListener {
            executeRestoreWhatsApp()
        }

        // Social Links
        binding.btnYoutube.setOnClickListener {
            openUrl("https://youtube.com/@basheer-tech")
        }
        binding.btnFacebook.setOnClickListener {
            openUrl("https://www.facebook.com/5basheer")
        }
        binding.btnTelegram.setOnClickListener {
            openUrl("https://t.me/bawaplus")
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Pairing Channel"
            val descriptionText = "Notifications for Wireless ADB Pairing"
            val importance = android.app.NotificationManager.IMPORTANCE_HIGH
            val channel = android.app.NotificationChannel("pairing_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: android.app.NotificationManager =
                getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startShizukuPairing() {
        createNotificationChannel()

        // 1. Start Auto-discovery for pairing port
        com.bamods.adbrestore.adb.AdbMdnsDiscovery(this).startDiscovery(object : com.bamods.adbrestore.adb.AdbMdnsDiscovery.DiscoveryListener {
            override fun onServiceFound(port: Int) {
                prefs.lastPairingPort = port
            }
        })

        // 2. Build the Notification with Direct Reply
        val replyLabel = getString(R.string.hint_pairing_code)
        val remoteInput: androidx.core.app.RemoteInput = androidx.core.app.RemoteInput.Builder("pairing_code_input")
            .setLabel(replyLabel)
            .build()

        val replyPendingIntent: android.app.PendingIntent =
            android.app.PendingIntent.getBroadcast(
                this,
                0,
                Intent(this, PairingReceiver::class.java).setAction("com.bamods.adbrestore.ACTION_PAIR"),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )

        val action: androidx.core.app.NotificationCompat.Action =
            androidx.core.app.NotificationCompat.Action.Builder(
                0,
                getString(R.string.action_reply),
                replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                .build()

        val builder = androidx.core.app.NotificationCompat.Builder(this, "pairing_channel")
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(getString(R.string.notif_pairing_title))
            .setContentText(getString(R.string.notif_pairing_desc))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .addAction(action)
            .setOngoing(true)
            .setAutoCancel(false)

        val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(1001, builder.build())

        // 3. Open Developer Options automatically
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            Toast.makeText(this, "افتح خيارات الاقتران اللاسلكي وأدخل الرمز في الإشعار", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "يرجى تفعيل خيارات المطور أولاً", Toast.LENGTH_LONG).show()
        }
    }

    private fun connectAdb(port: Int, host: String = "127.0.0.1") {
        setConnectionState(ConnectionState.CONNECTING, getString(R.string.status_disconnected))

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val target = "$host:$port"
                val process = ProcessBuilder(adbPath, "connect", target)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()

                withContext(Dispatchers.Main) {
                    if (output.contains("connected to", ignoreCase = true) || output.contains("already connected", ignoreCase = true)) {
                        prefs.lastConnectPort = port
                        prefs.lastHost = host
                        setConnectionState(ConnectionState.CONNECTED, "Connected")
                    } else {
                        setConnectionState(ConnectionState.DISCONNECTED, getString(R.string.status_disconnected))
                        Toast.makeText(this@MainActivity, "فشل الاتصال", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setConnectionState(ConnectionState.DISCONNECTED, getString(R.string.status_disconnected))
                }
            }
        }
    }

    private fun installOldWhatsApp() {
        lifecycleScope.launch(Dispatchers.IO) {
            val apkFile = extractApkFromAssets()
            if (apkFile == null || !apkFile.exists()) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "خطأ في الاستخراج", Toast.LENGTH_SHORT).show() }
                return@launch
            }

            executeAdbCommand("cp /sdcard/Download/Basheer_WApp.apk /data/local/tmp/Basheer_WApp.apk 2>/dev/null || cat /sdcard/Download/Basheer_WApp.apk > /data/local/tmp/Basheer_WApp.apk; chmod 666 /data/local/tmp/Basheer_WApp.apk")

            val installCmd = if (Build.VERSION.SDK_INT >= 34) {
                "pm install -r -d -t -g --bypass-low-target-sdk-block /data/local/tmp/Basheer_WApp.apk || pm install -r -d -t -g /data/local/tmp/Basheer_WApp.apk || pm install -r -d /data/local/tmp/Basheer_WApp.apk"
            } else {
                "pm install -r -d -t -g /data/local/tmp/Basheer_WApp.apk || pm install -r -d /data/local/tmp/Basheer_WApp.apk"
            }

            executeAdbCommand(installCmd)
            grantWhatsAppPermissions()
            
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "تم الانتهاء من التثبيت والإعداد", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun extractApkFromAssets(): File? = withContext(Dispatchers.IO) {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val targetFile = File(downloadDir, "Basheer_WApp.apk")

            assets.open("Basheer_WApp.apk").use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile
        } catch (e: Exception) {
            null
        }
    }

    private fun grantWhatsAppPermissions() {
        val permissionsCmd = """
            pm grant com.whatsapp android.permission.READ_EXTERNAL_STORAGE 2>/dev/null;
            pm grant com.whatsapp android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null;
            pm grant com.whatsapp android.permission.READ_MEDIA_IMAGES 2>/dev/null;
            pm grant com.whatsapp android.permission.READ_MEDIA_VIDEO 2>/dev/null;
            pm grant com.whatsapp android.permission.READ_MEDIA_AUDIO 2>/dev/null;
            pm grant com.whatsapp android.permission.READ_CONTACTS 2>/dev/null;
            pm grant com.whatsapp android.permission.WRITE_CONTACTS 2>/dev/null;
            pm grant com.whatsapp android.permission.POST_NOTIFICATIONS 2>/dev/null;
            appops set com.whatsapp MANAGE_EXTERNAL_STORAGE allow 2>/dev/null;
            appops set com.whatsapp READ_EXTERNAL_STORAGE allow 2>/dev/null;
            appops set com.whatsapp WRITE_EXTERNAL_STORAGE allow 2>/dev/null;
        """.trimIndent().replace("\n", " ")

        executeAdbCommand(permissionsCmd)
    }

    private fun executeRestoreWhatsApp() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val waFile = File(downloadDir, "wa.ab")
                
                assets.open("wa.ab").use { input ->
                    FileOutputStream(waFile).use { output ->
                        input.copyTo(output)
                    }
                }
                
                val filePath = waFile.absolutePath
                executeAdbCommand("cp $filePath /data/local/tmp/wa.ab 2>/dev/null; chmod 666 $filePath; chmod 666 /data/local/tmp/wa.ab 2>/dev/null")

                val restoreCmd = "cat \"$filePath\" | bu restore"
                executeAdbCommand(restoreCmd)

                executeAdbCommand("am start -n com.android.backupconfirm/.BackupRestoreConfirmation")
                
                withContext(Dispatchers.Main) {
                    showUpdateDialog()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showUpdateDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_update_title))
            .setMessage(getString(R.string.dialog_update_desc))
            .setPositiveButton(getString(R.string.btn_yes)) { dialog, _ ->
                dialog.dismiss()
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.whatsapp"))
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.whatsapp"))
                    startActivity(intent)
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun executeAdbCommand(cmd: String) {
        try {
            val target = "${prefs.lastHost}:${prefs.lastConnectPort}"
            val process = ProcessBuilder("sh", "-c", "$adbPath -s $target shell \"$cmd\"")
                .redirectErrorStream(true)
                .start()
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setConnectionState(state: ConnectionState, message: String) {
        binding.tvStatus.text = message
        when (state) {
            ConnectionState.CONNECTED -> {
                binding.viewStatusDot.setBackgroundResource(R.drawable.shape_dot_connected)
            }
            ConnectionState.PAIRING, ConnectionState.CONNECTING -> {
                binding.viewStatusDot.setBackgroundResource(R.drawable.shape_dot_pairing)
            }
            ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
                binding.viewStatusDot.setBackgroundResource(R.drawable.shape_dot_disconnected)
            }
        }
    }

    private enum class ConnectionState {
        DISCONNECTED,
        PAIRING,
        CONNECTING,
        CONNECTED,
        ERROR
    }
}
