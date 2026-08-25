package com.bamods.adbrestore.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.bamods.adbrestore.R
import com.bamods.adbrestore.adb.AdbMdnsDiscovery
import com.bamods.adbrestore.databinding.DialogPairingBinding

class PairingDialog(
    context: Context,
    private val onPairRequested: (pairingPort: Int, pairingCode: String, connectPort: Int) -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogPairingBinding
    private var mdnsDiscovery: AdbMdnsDiscovery? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogPairingBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        setupListeners()
        startAutoDiscovery()
    }

    private fun startAutoDiscovery() {
        mdnsDiscovery = AdbMdnsDiscovery(context).apply {
            onPairingDiscovered = { port, _ ->
                binding.etPairingPort.setText(port.toString())
                binding.pbDiscovery.visibility = View.GONE
                binding.tvDiscoveryStatus.text = "✅ تم اكتشاف منفذ الاقتران: $port (أدخل رمز الـ 6 أرقام فقط)"
                binding.cardDiscoveryStatus.setCardBackgroundColor(Color.parseColor("#1A10B981"))
                binding.etPairingCode.requestFocus()
            }

            onConnectDiscovered = { port, _ ->
                binding.etConnectPort.setText(port.toString())
            }

            startDiscovery()
        }
    }

    private fun setupListeners() {
        binding.btnOpenDevSettings.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "تعذر فتح إعدادات المطور تلقائياً", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCancelPairing.setOnClickListener {
            dismiss()
        }

        binding.btnConfirmPairing.setOnClickListener {
            val pairingPortStr = binding.etPairingPort.text?.toString()?.trim()
            val pairingCode = binding.etPairingCode.text?.toString()?.trim()
            val connectPortStr = binding.etConnectPort.text?.toString()?.trim()

            if (pairingPortStr.isNullOrEmpty() || pairingCode.isNullOrEmpty()) {
                Toast.makeText(context, "يرجى كتابة منفذ الاقتران والرمز المكون من 6 أرقام", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val pairingPort = pairingPortStr.toIntOrNull() ?: 0
            val connectPort = if (!connectPortStr.isNullOrEmpty()) connectPortStr.toIntOrNull() ?: 5555 else 5555

            if (pairingPort <= 0 || pairingPort > 65535) {
                Toast.makeText(context, "منفذ الاقتران غير صالح", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (pairingCode.length != 6) {
                Toast.makeText(context, "رمز الاقتران يجب أن يكون 6 أرقام", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dismiss()
            onPairRequested(pairingPort, pairingCode, connectPort)
        }
    }

    override fun onStop() {
        super.onStop()
        mdnsDiscovery?.stopDiscovery()
    }
}
