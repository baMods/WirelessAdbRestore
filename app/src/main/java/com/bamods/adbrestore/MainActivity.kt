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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)
        
        setupUI()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", adbPath)).waitFor()
            } catch (ignored: Exception) {}
        }
    }

    private fun setupUI() {
        binding.btnPairing.setOnClickListener {
            showPairingDialog()
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

    private fun showPairingDialog() {
        PairingDialog(this) { host, pairingPort, pairingCode, connectPort ->
            startPairingProcess(host, pairingPort, pairingCode, connectPort)
        }.show()
    }

    private fun startPairingProcess(host: String, pairingPort: Int, pairingCode: String, connectPort: Int) {
        setConnectionState(ConnectionState.PAIRING, getString(R.string.status_disconnected))

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val process = ProcessBuilder(adbPath, "pair", "$host:$pairingPort", pairingCode)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()

                withContext(Dispatchers.Main) {
                    if (output.contains("Successfully paired", ignoreCase = true) || output.contains("success", ignoreCase = true)) {
                        prefs.isPaired = true
                        prefs.lastPairingPort = pairingPort
                        prefs.lastConnectPort = connectPort
                        prefs.lastHost = host
                        connectAdb(connectPort, host)
                    } else {
                        setConnectionState(ConnectionState.ERROR, getString(R.string.status_disconnected))
                        Toast.makeText(this@MainActivity, "فشل الاقتران", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setConnectionState(ConnectionState.ERROR, getString(R.string.status_disconnected))
                }
            }
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
