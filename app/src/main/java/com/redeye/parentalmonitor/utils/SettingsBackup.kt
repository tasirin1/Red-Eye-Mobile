package com.redeye.parentalmonitor.utils

object SettingsBackup {
    const val MAGIC = "REDEYE1:"
    private const val ITERATIONS = 100_000
    private const val KEY_ALGORITHM = "AES"
    private const val KDF_MODERN = "PBKDF2WithHmacSHA256"
    private const val KDF_LEGACY = "PBKDF2WithHmacSHA1"

    data class BackupData(val token: String, val chatId: String, val syncInterval: Int, val cameraInterval: Int)

    fun export(token: String, chatId: String, syncInterval: Int, cameraInterval: Int, pin: String): String {
        require(token.isNotEmpty() && chatId.isNotEmpty())
        require(pin.length >= 4)
        val random = java.security.SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val plain = org.json.JSONObject()
            .put("v", 1)
            .put("token", token)
            .put("chat", chatId)
            .put("interval", syncInterval)
            .put("camera", cameraInterval)
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, deriveKey(pin, salt, true), javax.crypto.spec.GCMParameterSpec(128, iv))
        val out = salt + iv + cipher.doFinal(plain)
        return MAGIC + android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    fun import(blob: String, pin: String): BackupData {
        require(pin.length >= 4)
        val raw = blob.trim()
        require(raw.startsWith(MAGIC))
        val all = android.util.Base64.decode(raw.removePrefix(MAGIC), android.util.Base64.DEFAULT)
        require(all.size > 16 + 12 + 16)
        val salt = all.copyOfRange(0, 16)
        val iv = all.copyOfRange(16, 28)
        val cipherText = all.copyOfRange(28, all.size)
        val plain = decrypt(cipherText, pin, salt, iv).toString(Charsets.UTF_8)
        val o = org.json.JSONObject(plain)
        require(o.optInt("v", 0) == 1)
        val token = o.optString("token", "")
        val chat = o.optString("chat", "")
        require(token.isNotEmpty() && chat.isNotEmpty())
        return BackupData(token, chat, o.optInt("interval", 5).coerceIn(1, 1440), o.optInt("camera", 1).coerceIn(0, 60))
    }

    private fun decrypt(cipherText: ByteArray, pin: String, salt: ByteArray, iv: ByteArray): ByteArray {
        var failure: Exception? = null
        for (modern in booleanArrayOf(true, false)) {
            try {
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, deriveKey(pin, salt, modern), javax.crypto.spec.GCMParameterSpec(128, iv))
                return cipher.doFinal(cipherText)
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
        }
        throw failure ?: javax.crypto.AEADBadTagException("decrypt failed")
    }

    private fun deriveKey(pin: String, salt: ByteArray, modern: Boolean): javax.crypto.SecretKey {
        val factory = try {
            javax.crypto.SecretKeyFactory.getInstance(if (modern) KDF_MODERN else KDF_LEGACY)
        } catch (e: Exception) {
            if (!modern) throw e
            javax.crypto.SecretKeyFactory.getInstance(KDF_LEGACY)
        }
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, KEY_ALGORITHM)
    }
}
