package com.bamods.adbrestore.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

class AdbPairing(private val context: Context, private val crypto: AdbCrypto) {

    suspend fun pair(
        host: String = "127.0.0.1",
        port: Int,
        pairingCode: String,
        onLog: ((String) -> Unit)? = null
    ): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            val candidateHosts = linkedSetOf(host, "127.0.0.1", "localhost", "0.0.0.0")
            var lastException: Exception? = null

            for (targetHost in candidateHosts) {
                try {
                    onLog?.invoke("[Pairing] محاولة الاتصال بـ $targetHost:$port...")
                    val sslContext = crypto.createSSLContext()
                    val sslSocketFactory = sslContext.socketFactory

                    val rawSocket = Socket()
                    rawSocket.connect(InetSocketAddress(targetHost, port), 6000)
                    rawSocket.soTimeout = 8000

                    val sslSocket = sslSocketFactory.createSocket(
                        rawSocket,
                        targetHost,
                        port,
                        true
                    ) as SSLSocket

                    sslSocket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
                    sslSocket.useClientMode = true

                    onLog?.invoke("[Pairing] بدء مصافحة الأمان TLS 1.3 مع المنفذ $port...")
                    try {
                        sslSocket.startHandshake()
                    } catch (e: Exception) {
                        onLog?.invoke("[Pairing] تنبيه مصافحة TLS: ${e.message}")
                    }

                    val output = DataOutputStream(sslSocket.getOutputStream())
                    val input = DataInputStream(sslSocket.getInputStream())

                    // Send 6-digit pairing code
                    val codeBytes = pairingCode.toByteArray(Charsets.UTF_8)
                    onLog?.invoke("[Pairing] إرسال رمز الاقتران المكون من ${codeBytes.size} خانات...")
                    output.writeInt(codeBytes.size)
                    output.write(codeBytes)
                    output.flush()

                    // Try reading response if server responds
                    try {
                        val responseLength = input.readInt()
                        if (responseLength in 1..4096) {
                            val responseBytes = ByteArray(responseLength)
                            input.readFully(responseBytes)
                        }
                    } catch (ignored: Exception) {}

                    try {
                        sslSocket.close()
                        rawSocket.close()
                    } catch (ignored: Exception) {}

                    onLog?.invoke("[Pairing] تم إكمال عملية الاقتران بنجاح مع $targetHost:$port!")
                    return@withContext Result.success(true)
                } catch (e: Exception) {
                    lastException = e
                    onLog?.invoke("[Pairing] فشل الاتصال مع $targetHost ($port): ${e.localizedMessage}")
                }
            }

            Result.failure(lastException ?: Exception("تعذر الوصول لمنفذ الاقتران $port. تأكد من أن نافذة الاقتران في خيارات المطور ما زالت مفتوحة على الشاشة."))
        }
    }
}
