package com.example.myapplication

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

object Utils {

    fun getScreenBrightness(contentResolver: ContentResolver): Int {
        return try {
            val brightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            // Normalize brightness value to 0-100
            (brightness * 100) / 2047
        } catch (e: Settings.SettingNotFoundException) {
            Log.e("ScreenBrightness", "Error getting screen brightness", e)
            -1
        }
    }

    /**
     * Convert PEM format certificate and private key to PKCS12 format KeyStore
     * Enhanced error handling and logging
     */
    fun convertPemToPkcs12(filesDir: File): KeyStore {
        try {
            Log.d("AWS-IoT", "Starting certificate format conversion...")

            val certFile = File(filesDir, "cc9.cert.pem")
            val keyFile = File(filesDir, "cc9.private.key")
            val rootCAFile = File(filesDir, "root-CA.crt")

            // Check if file exists
            if (!certFile.exists()) throw IOException("Certificate file not found: cc9.cert.pem")
            if (!keyFile.exists()) throw IOException("Private key file not found: cc9.private.key")
            if (!rootCAFile.exists()) throw IOException("Root CA file not found: root-CA.crt")

            // Check if file size is 0
            if (certFile.length() == 0L) throw IOException("Certificate file is empty: cc9.cert.pem")
            if (keyFile.length() == 0L) throw IOException("Private key file is empty: cc9.private.key")
            if (rootCAFile.length() == 0L) throw IOException("Root CA file is empty: root-CA.crt")

            val certPem = certFile.readText()
            val keyPem = keyFile.readText()
            val rootCAPem = rootCAFile.readText()

            Log.d("AWS-IoT", "File read success: Certificate=${certPem.length} bytes, Private Key=${keyPem.length} bytes, Root CA=${rootCAPem.length} bytes")

            // Create KeyStore instance
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)

            // Create certificate factory
            val cf = CertificateFactory.getInstance("X.509")

            // Generate certificate object
            var cert: X509Certificate? = null
            var rootCA: X509Certificate? = null

            try {
                cert = cf.generateCertificate(certPem.byteInputStream()) as X509Certificate
                Log.d("AWS-IoT", "Certificate parsing success: Subject=${cert.subjectX500Principal}")
            } catch (e: Exception) {
                Log.e("AWS-IoT", "Certificate parsing failed", e)
                throw IOException("Certificate parsing failed: ${e.message}")
            }

            try {
                rootCA = cf.generateCertificate(rootCAPem.byteInputStream()) as X509Certificate
                Log.d("AWS-IoT", "Root CA parsing success: Subject=${rootCA.subjectX500Principal}")
            } catch (e: Exception) {
                Log.e("AWS-IoT", "Root CA parsing failed", e)
                throw IOException("Root CA parsing failed: ${e.message}")
            }

            // Process private key
            val privateKeyPem = keyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("\\s+".toRegex(), "")

            if (privateKeyPem.isEmpty()) {
                throw IOException("Private key content is empty")
            }

            // Generate private key object
            val privateKey: PrivateKey
            try {
                val keySpec = PKCS8EncodedKeySpec(Base64.decode(privateKeyPem, Base64.DEFAULT))
                val kf = KeyFactory.getInstance("RSA")
                privateKey = kf.generatePrivate(keySpec)
                Log.d("AWS-IoT", "Private key parsing success: Algorithm=${privateKey.algorithm}")
            } catch (e: Exception) {
                Log.e("AWS-IoT", "Private key parsing failed", e)
                throw IOException("Private key parsing failed: ${e.message}")
            }

            // Store private key and certificate in KeyStore
            val password = "temp".toCharArray()
            keyStore.setKeyEntry(
                "iot-certificate",
                privateKey,
                password,
                arrayOf(cert, rootCA)
            )

            // Store root certificate separately
            keyStore.setCertificateEntry("root-ca", rootCA)

            Log.d("AWS-IoT", "Certificate conversion success")
            return keyStore

        } catch (e: Exception) {
            Log.e("AWS-IoT", "Certificate conversion failed", e)

            // To avoid application crash, create an empty KeyStore
            try {
                val emptyKeyStore = KeyStore.getInstance("PKCS12")
                emptyKeyStore.load(null, null)
                Log.w("AWS-IoT", "Empty KeyStore created as alternative")
                return emptyKeyStore
            } catch (ex: Exception) {
                Log.e("AWS-IoT", "Failed to create empty KeyStore", ex)
                throw ex
            }
        }
    }

    /**
     * Create SSL context
     */
    fun createSslContextFromKeyStore(keyStore: KeyStore): SSLContext {
        try {
            val password = "temp".toCharArray()
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, password)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, null, null)

            return sslContext
        } catch (e: Exception) {
            Log.e("AWS-IoT", "Failed to create SSL context", e)
            throw e
        }
    }

    /**
     * Validate certificate files
     * Returns a Map containing validation results
     */
    fun validateCertificateFiles(filesDir: File): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        val files = listOf(
            "cc9.cert.pem",
            "cc9.private.key",
            "root-CA.crt"
        )

        for (fileName in files) {
            val file = File(filesDir, fileName)
            val exists = file.exists() && file.length() > 0
            results[fileName] = exists
            Log.d("CertValidation", "$fileName exists: $exists, Size: ${if (exists) file.length() else 0} bytes")
        }

        return results
    }

    /**
     * Create default empty certificate files
     * Only for development/test purposes
     */
    fun createEmptyDefaultCertificates(filesDir: File) {
        val files = listOf(
            "cc9.cert.pem" to "-----BEGIN CERTIFICATE-----\nEMPTY_CERT_PLACEHOLDER\n-----END CERTIFICATE-----",
            "cc9.private.key" to "-----BEGIN PRIVATE KEY-----\nEMPTY_KEY_PLACEHOLDER\n-----END PRIVATE KEY-----",
            "root-CA.crt" to "-----BEGIN CERTIFICATE-----\nEMPTY_ROOT_CA_PLACEHOLDER\n-----END CERTIFICATE-----"
        )

        for ((fileName, content) in files) {
            val file = File(filesDir, fileName)
            if (!file.exists() || file.length() == 0L) {
                try {
                    file.writeText(content)
                    Log.d("CertCreation", "Created default empty certificate file: $fileName")
                } catch (e: Exception) {
                    Log.e("CertCreation", "Failed to create default empty certificate file: $fileName", e)
                }
            }
        }
    }
}