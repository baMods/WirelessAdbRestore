package com.bamods.adbrestore.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

class AdbConnection(
    private val context: Context,
    private val crypto: AdbCrypto
) {
    companion object {
        const val A_SYNC = 0x434e5953
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257

        const val ADB_VERSION = 0x01000000
        const val MAX_PAYLOAD = 1024 * 1024
    }

    private var socket: Socket? = null
    private var inStream: InputStream? = null
    private var outStream: OutputStream? = null
    private var isConnected = false

    suspend fun connect(host: String = "127.0.0.1", port: Int, useTls: Boolean = true): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                disconnect()

                if (useTls) {
                    val sslContext = crypto.createSSLContext()
                    val sslSocketFactory = sslContext.socketFactory
                    val raw = Socket()
                    raw.connect(InetSocketAddress(host, port), 8000)
                    val sslSocket = sslSocketFactory.createSocket(raw, host, port, true) as SSLSocket
                    sslSocket.startHandshake()
                    socket = sslSocket
                } else {
                    val raw = Socket()
                    raw.connect(InetSocketAddress(host, port), 8000)
                    socket = raw
                }

                inStream = socket?.getInputStream()
                outStream = socket?.getOutputStream()

                // Send CNXN packet
                val systemInfo = "host::com.bamods.adbrestore\u0000".toByteArray(Charsets.UTF_8)
                writeMessage(A_CNXN, ADB_VERSION, MAX_PAYLOAD, systemInfo)

                // Read response
                val header = readHeader()
                if (header != null) {
                    isConnected = true
                    Result.success(true)
                } else {
                    Result.failure(Exception("لم يتم استلام استجابة من خادم ADB"))
                }
            } catch (e: Exception) {
                disconnect()
                Result.failure(e)
            }
        }
    }

    suspend fun executeCommand(command: String, onOutputLine: (String) -> Unit): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                if (!isConnected || outStream == null || inStream == null) {
                    return@withContext Result.failure(Exception("ADB غير متصل"))
                }

                val localId = 1
                val cmdPayload = "shell:$command\u0000".toByteArray(Charsets.UTF_8)
                writeMessage(A_OPEN, localId, 0, cmdPayload)

                val resultSb = StringBuilder()
                var streamActive = true

                while (streamActive && isConnected) {
                    val header = readHeader() ?: break
                    val cmd = header.command
                    val arg0 = header.arg0
                    val arg1 = header.arg1
                    val dataLength = header.dataLength

                    when (cmd) {
                        A_OKAY -> {
                            // Remote opened stream
                        }
                        A_WRTE -> {
                            val data = ByteArray(dataLength)
                            readFully(data)
                            // Acknowledge WRTE
                            writeMessage(A_OKAY, localId, arg0, ByteArray(0))

                            val text = String(data, Charsets.UTF_8)
                            resultSb.append(text)
                            onOutputLine(text)
                        }
                        A_CLSE -> {
                            writeMessage(A_CLSE, localId, arg0, ByteArray(0))
                            streamActive = false
                        }
                    }
                }

                Result.success(resultSb.toString())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun disconnect() {
        try {
            inStream?.close()
            outStream?.close()
            socket?.close()
        } catch (ignored: Exception) {}
        isConnected = false
        inStream = null
        outStream = null
        socket = null
    }

    private fun writeMessage(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val magic = command xor -0x1
        val buffer = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(data.size)
        buffer.putInt(checksum(data))
        buffer.putInt(magic)
        buffer.put(data)

        outStream?.write(buffer.array())
        outStream?.flush()
    }

    private data class AdbHeader(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val dataChecksum: Int,
        val magic: Int
    )

    private fun readHeader(): AdbHeader? {
        val headerBytes = ByteArray(24)
        if (!readFully(headerBytes)) return null
        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        return AdbHeader(
            command = buffer.int,
            arg0 = buffer.int,
            arg1 = buffer.int,
            dataLength = buffer.int,
            dataChecksum = buffer.int,
            magic = buffer.int
        )
    }

    private fun readFully(buffer: ByteArray): Boolean {
        var bytesRead = 0
        while (bytesRead < buffer.size) {
            val count = inStream?.read(buffer, bytesRead, buffer.size - bytesRead) ?: -1
            if (count < 0) return false
            bytesRead += count
        }
        return true
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) {
            sum += (b.toInt() and 0xFF)
        }
        return sum
    }
}
