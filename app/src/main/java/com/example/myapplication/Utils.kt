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
            // 归一化亮度值为0-100
            (brightness * 100) / 2047
        } catch (e: Settings.SettingNotFoundException) {
            Log.e("ScreenBrightness", "获取屏幕亮度时出错", e)
            -1
        }
    }

    /**
     * 将PEM格式的证书和私钥转换为PKCS12格式的KeyStore
     * 增强了错误处理和日志记录
     */
    fun convertPemToPkcs12(filesDir: File): KeyStore {
        try {
            Log.d("AWS-IoT", "开始证书格式转换...")

            val certFile = File(filesDir, "cc9.cert.pem")
            val keyFile = File(filesDir, "cc9.private.key")
            val rootCAFile = File(filesDir, "root-CA.crt")

            // 检查文件是否存在
            if (!certFile.exists()) throw IOException("证书文件不存在: cc9.cert.pem")
            if (!keyFile.exists()) throw IOException("私钥文件不存在: cc9.private.key")
            if (!rootCAFile.exists()) throw IOException("根CA文件不存在: root-CA.crt")

            // 检查文件大小是否为0
            if (certFile.length() == 0L) throw IOException("证书文件为空: cc9.cert.pem")
            if (keyFile.length() == 0L) throw IOException("私钥文件为空: cc9.private.key")
            if (rootCAFile.length() == 0L) throw IOException("根CA文件为空: root-CA.crt")

            val certPem = certFile.readText()
            val keyPem = keyFile.readText()
            val rootCAPem = rootCAFile.readText()

            Log.d("AWS-IoT", "文件读取成功: 证书=${certPem.length}字节, 私钥=${keyPem.length}字节, 根CA=${rootCAPem.length}字节")

            // 创建KeyStore实例
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)

            // 创建证书工厂
            val cf = CertificateFactory.getInstance("X.509")

            // 生成证书对象
            var cert: X509Certificate? = null
            var rootCA: X509Certificate? = null

            try {
                cert = cf.generateCertificate(certPem.byteInputStream()) as X509Certificate
                Log.d("AWS-IoT", "证书解析成功: 主题=${cert.subjectX500Principal}")
            } catch (e: Exception) {
                Log.e("AWS-IoT", "证书解析失败", e)
                throw IOException("证书解析失败: ${e.message}")
            }

            try {
                rootCA = cf.generateCertificate(rootCAPem.byteInputStream()) as X509Certificate
                Log.d("AWS-IoT", "根CA解析成功: 主题=${rootCA.subjectX500Principal}")
            } catch (e: Exception) {
                Log.e("AWS-IoT", "根CA解析失败", e)
                throw IOException("根CA解析失败: ${e.message}")
            }

            // 处理私钥
            val privateKeyPem = keyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("\\s+".toRegex(), "")

            if (privateKeyPem.isEmpty()) {
                throw IOException("私钥内容为空")
            }

            // 生成私钥对象
            val privateKey: PrivateKey
            try {
                val keySpec = PKCS8EncodedKeySpec(Base64.decode(privateKeyPem, Base64.DEFAULT))
                val kf = KeyFactory.getInstance("RSA")
                privateKey = kf.generatePrivate(keySpec)
                Log.d("AWS-IoT", "私钥解析成功: 算法=${privateKey.algorithm}")
            } catch (e: Exception) {
                Log.e("AWS-IoT", "私钥解析失败", e)
                throw IOException("私钥解析失败: ${e.message}")
            }

            // 将私钥和证书存入KeyStore
            val password = "temp".toCharArray()
            keyStore.setKeyEntry(
                "iot-certificate",
                privateKey,
                password,
                arrayOf(cert, rootCA)
            )

            // 将根证书单独存储
            keyStore.setCertificateEntry("root-ca", rootCA)

            Log.d("AWS-IoT", "证书转换成功")
            return keyStore

        } catch (e: Exception) {
            Log.e("AWS-IoT", "证书转换失败", e)

            // 为了避免应用崩溃，创建一个空的KeyStore
            try {
                val emptyKeyStore = KeyStore.getInstance("PKCS12")
                emptyKeyStore.load(null, null)
                Log.w("AWS-IoT", "已创建空KeyStore作为替代")
                return emptyKeyStore
            } catch (ex: Exception) {
                Log.e("AWS-IoT", "创建空KeyStore失败", ex)
                throw ex
            }
        }
    }

    /**
     * 创建SSL上下文
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
            Log.e("AWS-IoT", "创建SSL上下文失败", e)
            throw e
        }
    }

    /**
     * 验证证书文件
     * 返回一个包含验证结果的Map
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
            Log.d("CertValidation", "$fileName 存在: $exists, 大小: ${if (exists) file.length() else 0} 字节")
        }

        return results
    }

    /**
     * 创建默认空证书文件
     * 仅用于开发/测试目的
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
                    Log.d("CertCreation", "创建默认空证书文件: $fileName")
                } catch (e: Exception) {
                    Log.e("CertCreation", "创建默认空证书文件失败: $fileName", e)
                }
            }
        }
    }
}