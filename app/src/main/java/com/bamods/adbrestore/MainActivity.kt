package com.bamods.adbrestore

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bamods.adbrestore.adb.AdbConnection
import com.bamods.adbrestore.adb.AdbCrypto
import com.bamods.adbrestore.adb.AdbPairing
import com.bamods.adbrestore.databinding.ActivityMainBinding
import com.bamods.adbrestore.ui.PairingDialog
import com.bamods.adbrestore.utils.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager
    private lateinit var crypto: AdbCrypto
    private lateinit var pairing: AdbPairing
    private lateinit var adbConnection: AdbConnection

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                val path = uri.path?.let { p ->
                    if (p.contains(":")) "/sdcard/" + p.substringAfter(":") else p
                } ?: "/sdcard/wa.ab"
                binding.tvFilePath.text = path
                prefs.lastWaPath = path
                appendLog("[File] تم اختيار مسار الملف: $path")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initServices()
        checkPermissions()
        setupUI()
    }

    private fun initServices() {
        prefs = PrefsManager(this)
        crypto = AdbCrypto(this)
        pairing = AdbPairing(this, crypto)
        adbConnection = AdbConnection(this, crypto)
    }

    private fun setupUI() {
        binding.tvFilePath.text = prefs.lastWaPath
        binding.tvConnectionDetails.text = "Last Port: ${prefs.lastConnectPort}"

        // Pairing button
        binding.btnPairing.setOnClickListener {
            showPairingDialog()
        }

        // Quick connect button
        binding.btnQuickConnect.setOnClickListener {
            connectAdb(prefs.lastConnectPort)
        }

        // Select file button
        binding.btnSelectFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            filePickerLauncher.launch(Intent.createChooser(intent, "اختر ملف wa.ab"))
        }

        // One-Click Restore WhatsApp button
        binding.btnRestoreWhatsApp.setOnClickListener {
            val filePath = binding.tvFilePath.text.toString().trim()
            if (filePath.isEmpty()) {
                Toast.makeText(this, "يرجى تحديد مسار ملف wa.ab", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            executeRestoreWhatsApp(filePath)
        }

        // Backup WhatsApp button
        binding.btnBackupWhatsApp.setOnClickListener {
            executeAdbCommand("bu backup -f /sdcard/wa.ab com.whatsapp")
        }

        // Run custom command
        binding.btnRunCommand.setOnClickListener {
            val cmd = binding.etCustomCommand.text?.toString()?.trim()
            if (!cmd.isNullOrEmpty()) {
                executeAdbCommand(cmd)
                binding.etCustomCommand.text?.clear()
            }
        }

        // Clear logs
        binding.btnClearLogs.setOnClickListener {
            binding.tvTerminalLogs.text = ""
        }

        // Copy logs
        binding.btnCopyLogs.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ADB Logs", binding.tvTerminalLogs.text))
            Toast.makeText(this, "تم نسخ السجل إلى الحافظة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPairingDialog() {
        PairingDialog(this) { pairingPort, pairingCode, connectPort ->
            startPairingProcess(pairingPort, pairingCode, connectPort)
        }.show()
    }

    private fun startPairingProcess(pairingPort: Int, pairingCode: String, connectPort: Int) {
        setConnectionState(ConnectionState.PAIRING, "جاري الاقتران عبر المنفذ $pairingPort...")
        appendLog("[Pairing] بدء الاقتران بالمنفذ $pairingPort مع الرمز ******")

        lifecycleScope.launch {
            val result = pairing.pair("127.0.0.1", pairingPort, pairingCode)
            if (result.isSuccess) {
                prefs.isPaired = true
                prefs.lastPairingPort = pairingPort
                prefs.lastConnectPort = connectPort
                appendLog("[Success] اكتمل الاقتران بنجاح! جاري الاتصال بالـ ADB عبر المنفذ $connectPort...")
                connectAdb(connectPort)
            } else {
                setConnectionState(ConnectionState.ERROR, "فشل الاقتران: ${result.exceptionOrNull()?.message}")
                appendLog("[Error] فشل الاقتران: ${result.exceptionOrNull()?.localizedMessage}")
            }
        }
    }

    private fun connectAdb(port: Int) {
        setConnectionState(ConnectionState.CONNECTING, "جاري الاتصال بـ ADB (Port: $port)...")
        appendLog("[ADB] محاولة الاتصال بـ 127.0.0.1:$port...")

        lifecycleScope.launch {
            val result = adbConnection.connect("127.0.0.1", port, useTls = true)
            if (result.isSuccess) {
                setConnectionState(ConnectionState.CONNECTED, "متصل بـ ADB بنجاح (Port: $port)")
                appendLog("[Success] تم الاتصال بخادم ADB الداخلي بنجاح! يمكنك الآن تنفيذ الأوامر بنقرة واحدة.")
            } else {
                // Try non-TLS fallback
                val fallback = adbConnection.connect("127.0.0.1", port, useTls = false)
                if (fallback.isSuccess) {
                    setConnectionState(ConnectionState.CONNECTED, "متصل بـ ADB (Normal Mode)")
                    appendLog("[Success] تم الاتصال بخادم ADB بنجاح.")
                } else {
                    setConnectionState(ConnectionState.DISCONNECTED, "غير متصل: ${result.exceptionOrNull()?.message}")
                    appendLog("[Error] تعذر الاتصال بـ ADB. تأكد من تشغيل 'تصحيح الأخطاء اللاسلكي' وصحة المنفذ.")
                }
            }
        }
    }

    private fun executeRestoreWhatsApp(filePath: String) {
        AlertDialog.Builder(this)
            .setTitle("تأكيد استعادة واتساب")
            .setMessage("سيتم إرسال أمر استعادة $filePath.\n\nبعد الضغط على متابعة، ستظهر لك نافذة أمان أندرويد على الشاشة، اضغط فيها على (استعادة بياناتي / Restore My Data).")
            .setPositiveButton("متابعة واستعادة") { _, _ ->
                val command = "bu restore $filePath"
                appendLog("[Action] بدء استعادة: $command")
                executeAdbCommand(command)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun executeAdbCommand(cmd: String) {
        appendLog("\n> $cmd")
        lifecycleScope.launch {
            val result = adbConnection.executeCommand(cmd) { output ->
                runOnUiThread {
                    appendLog(output)
                }
            }
            if (result.isFailure) {
                appendLog("[Error] خطأ أثناء التنفيذ: ${result.exceptionOrNull()?.message}")
            } else {
                appendLog("[Done] اكتمل تنفيذ الأمر.")
            }
        }
    }

    private fun appendLog(message: String) {
        val time = timeFormat.format(Date())
        val currentText = binding.tvTerminalLogs.text.toString()
        val newText = "$currentText\n[$time] $message"
        binding.tvTerminalLogs.text = newText
        binding.svTerminal.post {
            binding.svTerminal.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun setConnectionState(state: ConnectionState, message: String) {
        binding.tvStatus.text = message
        when (state) {
            ConnectionState.CONNECTED -> {
                binding.viewStatusDot.setBackgroundResource(R.drawable.shape_dot_connected)
                binding.tvConnectionDetails.text = "الحالة: متصل وجاهز لتنفيذ الأوامر"
            }
            ConnectionState.PAIRING, ConnectionState.CONNECTING -> {
                binding.viewStatusDot.setBackgroundResource(R.drawable.shape_dot_pairing)
                binding.tvConnectionDetails.text = "الحالة: جاري المزامنة..."
            }
            ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
                binding.viewStatusDot.setBackgroundResource(R.drawable.shape_dot_disconnected)
                binding.tvConnectionDetails.text = "الحالة: غير متصل"
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (ignored: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        adbConnection.disconnect()
    }

    private enum class ConnectionState {
        DISCONNECTED,
        PAIRING,
        CONNECTING,
        CONNECTED,
        ERROR
    }
}
