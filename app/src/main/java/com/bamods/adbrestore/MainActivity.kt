package com.bamods.adbrestore

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import java.io.File
import java.io.FileOutputStream
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
                handlePickedFile(uri)
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
        binding.tvConnectionDetails.text = "آخر منفذ تم حفظه: ${prefs.lastConnectPort}"

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

        // Install Old WhatsApp (Basheer_WApp)
        binding.btnInstallOldWa.setOnClickListener {
            installOldWhatsApp()
        }

        // Grant WhatsApp Permissions button
        binding.btnGrantPermissions.setOnClickListener {
            grantWhatsAppPermissions()
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

        // Open Confirm Screen manually button
        binding.btnOpenConfirmScreen.setOnClickListener {
            appendLog("[Action] فتح شاشة تأكيد الاستعادة يدوياً...")
            executeAdbCommand("am start -n com.android.backupconfirm/.BackupRestoreConfirmation")
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

    private fun handlePickedFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    appendLog("[File] جاري نسخ وتجهيز ملف النسخة الاحتياطية المحدد...")
                }
                val destSdcard = File(Environment.getExternalStorageDirectory(), "wa.ab")
                val destDownload = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "wa.ab")
                val destCache = File(cacheDir, "wa.ab")

                contentResolver.openInputStream(uri)?.use { input ->
                    destCache.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                try {
                    destCache.copyTo(destSdcard, overwrite = true)
                } catch (ignored: Exception) {}

                try {
                    destCache.copyTo(destDownload, overwrite = true)
                } catch (ignored: Exception) {}

                val finalPath = if (destSdcard.exists()) "/sdcard/wa.ab" else "/sdcard/Download/wa.ab"
                withContext(Dispatchers.Main) {
                    binding.tvFilePath.text = finalPath
                    prefs.lastWaPath = finalPath
                    appendLog("[File] تم حفظ وتجهيز الملف بنجاح في: $finalPath (الحجم: ${destCache.length() / 1024} KB)")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("[Error] تعذر قراءة الملف: ${e.message}")
                }
            }
        }
    }

    private fun showPairingDialog() {
        PairingDialog(this) { host, pairingPort, pairingCode, connectPort ->
            startPairingProcess(host, pairingPort, pairingCode, connectPort)
        }.show()
    }

    private fun startPairingProcess(host: String, pairingPort: Int, pairingCode: String, connectPort: Int) {
        setConnectionState(ConnectionState.PAIRING, "جاري الاقتران عبر $host:$pairingPort...")
        appendLog("[Pairing] بدء الاقتران بالهدف $host:$pairingPort مع الرمز $pairingCode...")

        lifecycleScope.launch {
            val result = pairing.pair(host, pairingPort, pairingCode) { logMsg ->
                runOnUiThread { appendLog(logMsg) }
            }
            if (result.isSuccess) {
                prefs.isPaired = true
                prefs.lastPairingPort = pairingPort
                prefs.lastConnectPort = connectPort
                appendLog("[Success] اكتمل الاقتران بنجاح! جاري الاتصال بالـ ADB عبر المنفذ $connectPort...")
                connectAdb(connectPort, host)
            } else {
                setConnectionState(ConnectionState.ERROR, "فشل الاقتران: ${result.exceptionOrNull()?.message}")
                appendLog("[Error] فشل الاقتران: ${result.exceptionOrNull()?.localizedMessage}")
                appendLog("[Tip] تنبيه هام: يجب إبقاء نافذة رمز الاقتران في خيارات المطور مفتوحة على الشاشة أثناء إدخال الرمز، لأن أندرويد يلغي الجلسة بمجرد إغلاق النافذة.")
            }
        }
    }

    private fun connectAdb(port: Int, host: String = "127.0.0.1") {
        setConnectionState(ConnectionState.CONNECTING, "جاري الاتصال بـ ADB (Port: $port)...")
        appendLog("[ADB] محاولة الاتصال بـ $host:$port...")

        lifecycleScope.launch {
            val result = adbConnection.connect(host, port, useTls = true)
            if (result.isSuccess) {
                setConnectionState(ConnectionState.CONNECTED, "متصل بـ ADB بنجاح (Port: $port)")
                appendLog("[Success] تم الاتصال بخادم ADB الداخلي بنجاح! جاهز لتنفيذ الأوامر.")
            } else {
                // Try non-TLS fallback
                val fallback = adbConnection.connect(host, port, useTls = false)
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

    private fun installOldWhatsApp() {
        AlertDialog.Builder(this)
            .setTitle("تثبيت واتساب القديم (Basheer_WApp)")
            .setMessage("سيتم استخراج نسخة واتساب القديمة المدمجة وتثبيتها إجبارياً عبر ADB.\n\nتدعم هذه العملية تجاوز حظر أندرويد 14+ للإصدارات القديمة، كما تدعم أندرويد 10 وأقل بدون فقدان البيانات.")
            .setPositiveButton("بدء التثبيت الآن") { _, _ ->
                lifecycleScope.launch {
                    appendLog("[Install] جاري استخراج ملف Basheer_WApp.apk من التطبيق...")
                    val apkFile = extractApkFromAssets()
                    if (apkFile == null || !apkFile.exists()) {
                        appendLog("[Error] فشل استخراج ملف Basheer_WApp.apk")
                        return@launch
                    }

                    appendLog("[Install] تم استخراج ملف الـ APK (${apkFile.length() / 1024} KB). جاري النقل والتثبيت عبر ADB...")

                    // Copy to /data/local/tmp/
                    executeAdbCommand("cp /sdcard/Download/Basheer_WApp.apk /data/local/tmp/Basheer_WApp.apk 2>/dev/null || cat /sdcard/Download/Basheer_WApp.apk > /data/local/tmp/Basheer_WApp.apk; chmod 666 /data/local/tmp/Basheer_WApp.apk")

                    // Smart install command supporting Android 14+ bypass and Android 10-
                    val installCmd = if (Build.VERSION.SDK_INT >= 34) {
                        "pm install -r -d -t -g --bypass-low-target-sdk-block /data/local/tmp/Basheer_WApp.apk || pm install -r -d -t -g /data/local/tmp/Basheer_WApp.apk || pm install -r -d /data/local/tmp/Basheer_WApp.apk"
                    } else {
                        "pm install -r -d -t -g /data/local/tmp/Basheer_WApp.apk || pm install -r -d /data/local/tmp/Basheer_WApp.apk"
                    }

                    appendLog("[Install] تنفيذ أمر التثبيت الذكي: $installCmd")
                    executeAdbCommand(installCmd)

                    // Auto grant permissions after install
                    grantWhatsAppPermissions()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
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
            e.printStackTrace()
            null
        }
    }

    private fun grantWhatsAppPermissions() {
        appendLog("[Permissions] جاري منح كافة الأذونات المطلوبة لواتساب...")
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
            echo "تم تعيين أذونات com.whatsapp بنجاح."
        """.trimIndent().replace("\n", " ")

        executeAdbCommand(permissionsCmd)
    }

    private fun executeRestoreWhatsApp(filePath: String) {
        AlertDialog.Builder(this)
            .setTitle("تأكيد استعادة واتساب")
            .setMessage("مسار النسخة: $filePath\n\nخطوات الاستعادة:\n1. تأكد من تثبيت واتساب القديم أولاً (Basheer_WApp).\n2. بعد الضغط على (بدء الاستعادة)، ستظهر نافذة بيضاء من نظام أندرويد تطلب تأكيد الاستعادة.\n3. اضغط فوراً على (استعادة بياناتي / Restore My Data) بدون كتابة كلمة سر.\n4. إذا لم تظهر النافذة تلقائياً، اضغط على زر (فتح شاشة تأكيد الاستعادة).")
            .setPositiveButton("بدء الاستعادة الآن") { _, _ ->
                lifecycleScope.launch {
                    // Prepare files in /data/local/tmp and /sdcard
                    appendLog("[Restore] تجهيز أذونات ملف النسخة الاحتياطية $filePath...")
                    executeAdbCommand("cp $filePath /data/local/tmp/wa.ab 2>/dev/null; chmod 666 $filePath; chmod 666 /data/local/tmp/wa.ab 2>/dev/null")

                    // Run bu restore
                    val restoreCmd = "bu restore $filePath"
                    appendLog("[Action] إرسال أمر الاستعادة: $restoreCmd")
                    executeAdbCommand(restoreCmd)

                    // Trigger confirmation screen
                    executeAdbCommand("am start -n com.android.backupconfirm/.BackupRestoreConfirmation")
                    
                    Toast.makeText(this@MainActivity, "يرجى النظر إلى الشاشة والضغط على (استعادة بياناتي)", Toast.LENGTH_LONG).show()
                }
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
        } else {
            val perms = arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val needed = perms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
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
