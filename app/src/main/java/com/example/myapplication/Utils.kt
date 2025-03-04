package com.example.myapplication

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

object Utils {

    fun getScreenBrightness(contentResolver: ContentResolver): Int {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Settings.SettingNotFoundException) {
            Log.e("ScreenBrightness", "Error getting screen brightness", e)
            -1
        }
    }

    fun convertPemToPkcs12(filesDir: File): KeyStore {
        try {
            Log.d("AWS-IoT", "Starting certificate format conversion...")

            val certFile = File(filesDir, "cc9.cert.pem")
            val keyFile = File(filesDir, "cc9.private.key")
            val rootCAFile = File(filesDir, "root-CA.crt")

            val certPem = certFile.readText()
            val keyPem = keyFile.readText()
            val rootCAPem = rootCAFile.readText()

            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)

            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(certPem.byteInputStream()) as X509Certificate
            val rootCA = cf.generateCertificate(rootCAPem.byteInputStream()) as X509Certificate

            val privateKeyPem = keyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("\\s+".toRegex(), "")

            val keySpec = PKCS8EncodedKeySpec(Base64.decode(privateKeyPem, Base64.DEFAULT))
            val kf = KeyFactory.getInstance("RSA")
            val privateKey = kf.generatePrivate(keySpec)

            val password = "temp".toCharArray()
            keyStore.setKeyEntry(
                "iot-certificate",
                privateKey,
                password,
                arrayOf(cert, rootCA)
            )
            keyStore.setCertificateEntry("root-ca", rootCA)

            Log.d("AWS-IoT", "Certificate conversion successful")
            return keyStore

        } catch (e: Exception) {
            Log.e("AWS-IoT", "Certificate conversion failed", e)
            throw e
        }
    }
} 