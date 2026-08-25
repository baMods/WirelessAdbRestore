package com.bamods.adbrestore.adb

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AdbCrypto(private val context: Context) {

    private val keyFile = File(context.filesDir, "adb_key.pk8")
    private val certFile = File(context.filesDir, "adb_cert.der")

    var keyPair: KeyPair? = null
        private set

    var certificate: X509Certificate? = null
        private set

    init {
        loadOrGenerateKeys()
    }

    private fun loadOrGenerateKeys() {
        if (keyFile.exists() && certFile.exists()) {
            try {
                val keyBytes = keyFile.readBytes()
                val kf = KeyFactory.getInstance("RSA")
                val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                val cert = JcaX509CertificateConverter()
                    .getCertificate(org.bouncycastle.cert.X509CertificateHolder(certFile.readBytes()))
                
                keyPair = KeyPair(cert.publicKey, privKey)
                certificate = cert
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        generateNewKeyPair()
    }

    private fun generateNewKeyPair() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val notBefore = Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24)
        val notAfter = Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 10)
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val name = X500Name("CN=WirelessAdbRestore")

        val certBuilder = JcaX509v3CertificateBuilder(
            name,
            serial,
            notBefore,
            notAfter,
            name,
            kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val holder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(holder)

        keyFile.writeBytes(kp.private.encoded)
        certFile.writeBytes(holder.encoded)

        keyPair = kp
        certificate = cert
    }

    fun createSSLContext(): SSLContext {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry("adb", keyPair?.private, "password".toCharArray(), arrayOf(certificate))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "password".toCharArray())

        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = try {
            SSLContext.getInstance("TLSv1.3")
        } catch (e: Exception) {
            SSLContext.getInstance("TLS")
        }
        sslContext.init(kmf.keyManagers, trustAll, SecureRandom())
        return sslContext
    }
}
