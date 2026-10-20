package com.redeye.parentalmonitor.utils

object SettingsBackup {
    const val MAGIC = "REDEYE1:"
    private const val ITERATIONS = 100_000
    private const val KEY_ALGORITHM = "AES"
    private const val KDF_MODERN = "PBKDF2WithHmacSHA256"
    private const val KDF_LEGACY = "PBKDF2WithHmacSHA1"
    private const val MIN_PIN = 6
    private const val MIN_PIN_LEGACY = 4

    data class BackupData(val token: String, val chatId: String, val syncInterval: Int, val cameraInterval: Int, val ownerId: Long = 0L, val accessPin: String = "")

    fun export(token: String, chatId: String, syncInterval: Int, cameraInterval: Int, ownerId: Long, accessPin: String, pin: String): String {
        require(token.isNotEmpty() && chatId.isNotEmpty())
        require(pin.length >= MIN_PIN)
        val random = java.security.SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val plain = org.json.JSONObject()
            .put("v", 1)
            .put("token", token)
            .put("chat", chatId)
            .put("interval", syncInterval)
            .put("camera", cameraInterval)
            .put("owner", ownerId)
            .put("accessPin", accessPin)
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, deriveKey(pin, salt, true), javax.crypto.spec.GCMParameterSpec(128, iv))
        val out = salt + iv + cipher.doFinal(plain)
        return MAGIC + android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    fun import(blob: String, pin: String): BackupData {
        require(pin.length >= MIN_PIN_LEGACY)
        val raw = blob.trim()
        require(raw.startsWith(MAGIC))
        val all = android.util.Base64.decode(raw.removePrefix(MAGIC), android.util.Base64.DEFAULT)
        require(all.size > 16 + 12 + 16)
        val salt = all.copyOfRange(0, 16)
        val iv = all.copyOfRange(16, 28)
        val cipherText = all.copyOfRange(28, all.size)
        val plain = decrypt(cipherText, pin, salt, iv).toString(Charsets.UTF_8)
        val o = org.json.JSONObject(plain)
        require(o.optInt("v", 0) in 1..2)
        val token = o.optString("token", "")
        val chat = o.optString("chat", "")
        require(token.isNotEmpty() && chat.isNotEmpty())
        val owner = o.optLong("owner", 0L)
        require(owner >= 0L)
        val accessPin = o.optString("accessPin", "").takeIf { AccessPin.isValid(it) } ?: ""
        return BackupData(token, chat, o.optInt("interval", 5).coerceIn(1, 1440), o.optInt("camera", 1).coerceIn(0, 60), owner, accessPin)
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
        throw failure ?: javax.crypto.AEADBadTagException("gagal mendekripsi")
    }

    private fun deriveKey(pin: String, salt: ByteArray, modern: Boolean): javax.crypto.SecretKey {
        if (modern) {
            try {
                val factory = javax.crypto.SecretKeyFactory.getInstance(KDF_MODERN)
                val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
                return javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, KEY_ALGORITHM)
            } catch (_: Exception) {
                return javax.crypto.spec.SecretKeySpec(pbkdf2Sha256(pin, salt, ITERATIONS, 32), KEY_ALGORITHM)
            }
        }
        val factory = javax.crypto.SecretKeyFactory.getInstance(KDF_LEGACY)
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, KEY_ALGORITHM)
    }

    private fun pbkdf2Sha256(pin: String, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val key = javax.crypto.spec.SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val hLen = 32
        val blocks = (keyLength + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val input = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, input, 0, salt.size)
        for (i in 1..blocks) {
            input[salt.size] = (i ushr 24).toByte()
            input[salt.size + 1] = (i ushr 16).toByte()
            input[salt.size + 2] = (i ushr 8).toByte()
            input[salt.size + 3] = i.toByte()
            mac.init(key)
            var u = mac.doFinal(input)
            val t = u.copyOf()
            for (c in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOf(keyLength)
    }
}
