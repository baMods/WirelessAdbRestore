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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // المسار المخصص لملف adb التنفيذي الذي يستخرجه نظام أندرويد تلقائياً (بفضل extractNativeLibs=true)
    private val adbPath: String
        get() = applicationInfo.nativeLibraryDir + "/libadb.so"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initServices()
        checkPermissions()
        setupUI()
        
        // عند الفتح، إعطاء صلاحيات التشغيل لملف الـ adb
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", adbPath)).waitFor()
            } catch (ignored: Exception) {}
        }
    }

    private fun initServices() {
        prefs = PrefsManager(this)
    }

    private fun setupUI() {
        binding.tvConnectionDetails.text = "آخر منفذ تم حفظه: ${prefs.lastConnectPort}"

        binding.btnPairing.setOnClickListener {
            showPairingDialog()
        }

        binding.btnQuickConnect.setOnClickListener {
            connectAdb(prefs.lastConnectPort, prefs.lastHost)
        }

        binding.btnInstallOldWa.setOnClickListener {
            installOldWhatsApp()
        }

        binding.btnGrantPermissions.setOnClickListener {
            grantWhatsAppPermissions()
        }

        binding.btnRestoreWhatsApp.setOnClickListener {
            executeRestoreWhatsApp()
        }

        binding.btnOpenConfirmScreen.setOnClickListener {
            appendLog("[Action] فتح شاشة تأكيد الاستعادة يدوياً...")
            executeAdbCommand("am start -n com.android.backupconfirm/.BackupRestoreConfirmation")
        }

        binding.btnBackupWhatsApp.setOnClickListener {
            executeAdbCommand("bu backup -f /sdcard/wa.ab com.whatsapp")
        }

        binding.btnRunCommand.setOnClickListener {
            val cmd = binding.etCustomCommand.text?.toString()?.trim()
            if (!cmd.isNullOrEmpty()) {
                executeAdbCommand(cmd)
                binding.etCustomCommand.text?.clear()
            }
        }

        binding.btnClearLogs.setOnClickListener {
            binding.tvTerminalLogs.text = ""
        }

        binding.btnCopyLogs.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ADB Logs", binding.tvTerminalLogs.text))
            Toast.makeText(this, "تم نسخ السجل إلى الحافظة", Toast.LENGTH_SHORT).show()
        }
    }



    private fun showPairingDialog() {
        PairingDialog(this) { host, pairingPort, pairingCode, connectPort ->
            startPairingProcess(host, pairingPort, pairingCode, connectPort)
        }.show()
    }

    private fun startPairingProcess(host: String, pairingPort: Int, pairingCode: String, connectPort: Int) {
        setConnectionState(ConnectionState.PAIRING, "جاري الاقتران عبر $host:$pairingPort...")
        appendLog("[Pairing] بدء الاقتران عبر ADB الأصلي بالهدف $host:$pairingPort مع الرمز $pairingCode...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val process = ProcessBuilder(adbPath, "pair", "$host:$pairingPort", pairingCode)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()

                withContext(Dispatchers.Main) {
                    appendLog(output)
                    if (output.contains("Successfully paired", ignoreCase = true) || output.contains("success", ignoreCase = true)) {
                        prefs.isPaired = true
                        prefs.lastPairingPort = pairingPort
                        prefs.lastConnectPort = connectPort
                        prefs.lastHost = host
                        appendLog("[Success] اكتمل الاقتران بنجاح! جاري الاتصال بالـ ADB عبر المنفذ $connectPort...")
                        connectAdb(connectPort, host)
                    } else {
                        setConnectionState(ConnectionState.ERROR, "فشل الاقتران.")
                        appendLog("[Error] فشل الاقتران. تأكد من إبقاء نافذة إعدادات المطور مفتوحة.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setConnectionState(ConnectionState.ERROR, "خطأ: ${e.message}")
                    appendLog("[Error] ${e.localizedMessage}")
                }
            }
        }
    }

    private fun connectAdb(port: Int, host: String = "127.0.0.1") {
        setConnectionState(ConnectionState.CONNECTING, "جاري الاتصال بـ ADB (Port: $port)...")
        appendLog("[ADB] محاولة الاتصال بـ $host:$port...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val target = "$host:$port"
                val process = ProcessBuilder(adbPath, "connect", target)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()

                withContext(Dispatchers.Main) {
                    appendLog(output)
                    if (output.contains("connected to", ignoreCase = true) || output.contains("already connected", ignoreCase = true)) {
                        prefs.lastConnectPort = port
                        prefs.lastHost = host
                        setConnectionState(ConnectionState.CONNECTED, "متصل بـ ADB بنجاح (Port: $port)")
                        appendLog("[Success] تم الاتصال والتأكيد بنجاح! جاهز لتنفيذ الأوامر.")
                    } else {
                        setConnectionState(ConnectionState.DISCONNECTED, "غير متصل")
                        appendLog("[Error] تعذر الاتصال بـ ADB. قم بإلغاء إقران الجهاز من الإعدادات وحاول مجدداً.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setConnectionState(ConnectionState.DISCONNECTED, "غير متصل")
                    appendLog("[Error] ${e.localizedMessage}")
                }
            }
        }
    }

    private fun installOldWhatsApp() {
        AlertDialog.Builder(this)
            .setTitle("تثبيت واتساب القديم (Basheer_WApp)")
            .setMessage("سيتم استخراج نسخة واتساب القديمة المدمجة وتثبيتها إجبارياً عبر ADB.\n\nتدعم هذه العملية تجاوز حظر أندرويد 14+ للإصدارات القديمة، كما تدعم أندرويد 10 وأقل بدون فقدان البيانات.")
            .setPositiveButton("بدء التثبيت الآن") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    withContext(Dispatchers.Main) { appendLog("[Install] جاري استخراج ملف Basheer_WApp.apk من التطبيق...") }
                    val apkFile = extractApkFromAssets()
                    if (apkFile == null || !apkFile.exists()) {
                        withContext(Dispatchers.Main) { appendLog("[Error] فشل استخراج ملف Basheer_WApp.apk") }
                        return@launch
                    }

                    withContext(Dispatchers.Main) { appendLog("[Install] تم استخراج ملف الـ APK (${apkFile.length() / 1024} KB). جاري النقل والتثبيت عبر ADB...") }

                    executeAdbCommand("cp /sdcard/Download/Basheer_WApp.apk /data/local/tmp/Basheer_WApp.apk 2>/dev/null || cat /sdcard/Download/Basheer_WApp.apk > /data/local/tmp/Basheer_WApp.apk; chmod 666 /data/local/tmp/Basheer_WApp.apk")

                    val installCmd = if (Build.VERSION.SDK_INT >= 34) {
                        "pm install -r -d -t -g --bypass-low-target-sdk-block /data/local/tmp/Basheer_WApp.apk || pm install -r -d -t -g /data/local/tmp/Basheer_WApp.apk || pm install -r -d /data/local/tmp/Basheer_WApp.apk"
                    } else {
                        "pm install -r -d -t -g /data/local/tmp/Basheer_WApp.apk || pm install -r -d /data/local/tmp/Basheer_WApp.apk"
                    }

                    withContext(Dispatchers.Main) { appendLog("[Install] تنفيذ أمر التثبيت الذكي...") }
                    executeAdbCommand(installCmd)

                    withContext(Dispatchers.Main) { grantWhatsAppPermissions() }
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

    private fun executeRestoreWhatsApp() {
        AlertDialog.Builder(this)
            .setTitle("تأكيد استعادة واتساب")
            .setMessage("خطوات الاستعادة:\n1. تأكد من تثبيت واتساب القديم أولاً.\n2. بعد الضغط على (بدء الاستعادة)، سيتم استخراج الملف المدمج وإرساله.\n3. ستظهر نافذة بيضاء تطلب تأكيد الاستعادة، اضغط فوراً على (استعادة بياناتي) بدون كتابة كلمة سر.")
            .setPositiveButton("بدء الاستعادة الآن") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        withContext(Dispatchers.Main) { appendLog("[Restore] جاري استخراج ملف wa.ab المدمج (قد يستغرق بعض الوقت)...") }
                        
                        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!downloadDir.exists()) downloadDir.mkdirs()
                        val waFile = File(downloadDir, "wa.ab")
                        
                        assets.open("wa.ab").use { input ->
                            FileOutputStream(waFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        val filePath = waFile.absolutePath
                        withContext(Dispatchers.Main) { appendLog("[Restore] تجهيز أذونات ملف النسخة الاحتياطية...") }
                        executeAdbCommand("cp $filePath /data/local/tmp/wa.ab 2>/dev/null; chmod 666 $filePath; chmod 666 /data/local/tmp/wa.ab 2>/dev/null")

                        val restoreCmd = "cat \"$filePath\" | bu restore"
                        withContext(Dispatchers.Main) { appendLog("[Action] إرسال أمر الاستعادة...") }
                        executeAdbCommand(restoreCmd)

                        executeAdbCommand("am start -n com.android.backupconfirm/.BackupRestoreConfirmation")
                        
                        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "يرجى النظر إلى الشاشة والضغط على (استعادة بياناتي)", Toast.LENGTH_LONG).show() }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) { appendLog("[Error] فشل استخراج ملف الاستعادة: ${e.message}") }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun executeAdbCommand(cmd: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            appendLog("\n> $cmd")
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val target = "${prefs.lastHost}:${prefs.lastConnectPort}"
                
                val process = ProcessBuilder("sh", "-c", "$adbPath -s $target shell \"$cmd\"")
                    .redirectErrorStream(true)
                    .start()

                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    withContext(Dispatchers.Main) {
                        appendLog(line ?: "")
                    }
                }
                process.waitFor()
                withContext(Dispatchers.Main) { appendLog("[Done] اكتمل التنفيذ.") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[Error] ${e.message}") }
            }
        }
    }

    private fun appendLog(message: String) {
        if (message.isBlank()) return
        val time = timeFormat.format(Date())
        val currentText = binding.tvTerminalLogs.text.toString()
        val newText = if (currentText.isEmpty()) "[$time] $message" else "$currentText\n[$time] $message"
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
    }

    private enum class ConnectionState {
        DISCONNECTED,
        PAIRING,
        CONNECTING,
        CONNECTED,
        ERROR
    }
}
