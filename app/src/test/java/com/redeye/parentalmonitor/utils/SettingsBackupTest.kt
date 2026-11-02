package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

class SettingsBackupTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun pbkdf2TulisanTanganCocokVektorDikenal() {
        // Vektor PBKDF2-HMAC-SHA256 yang dipublikasikan luas (password/salt),
        // diverifikasi silang terhadap implementasi independen (Python
        // hashlib.pbkdf2_hmac) sebelum asersi ini ditulis — issue #637.
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(SettingsBackup.pbkdf2Sha256("password", "salt".toByteArray(Charsets.UTF_8), 1, 32))
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            hex(SettingsBackup.pbkdf2Sha256("password", "salt".toByteArray(Charsets.UTF_8), 2, 32))
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(SettingsBackup.pbkdf2Sha256("password", "salt".toByteArray(Charsets.UTF_8), 4096, 32))
        )
    }

    @Test
    fun pbkdf2TulisanTanganCocokJalurPabrik() {
        // deriveKey modern memakai SecretKeyFactory bila tersedia dan jatuh ke
        // pbkdf2Sha256 tulisan tangan bila tidak — keduanya harus identik,
        // supaya cadangan tetap terbaca di perangkat tanpa pabrik PBKDF2.
        val salt = ByteArray(16) { it.toByte() }
        val viaFactory = SettingsBackup.deriveKey("pin-cadangan-kuat", salt, true, 1_000).encoded
        val viaManual = SettingsBackup.pbkdf2Sha256("pin-cadangan-kuat", salt, 1_000, 32)
        assertArrayEquals(viaManual, viaFactory)
    }

    @Test
    fun decryptMemulihkanCadanganKunciTulisanTangan() {
        // #686: simulasi cadangan yang terenkripsi di perangkat yang
        // SecretKeyFactory-nya gagal (kunci tulisan tangan UTF-8) — decrypt()
        // harus menemukannya lewat kandidat tulisan tangan juga.
        val salt = ByteArray(16) { (it * 7 + 1).toByte() }
        val iv = ByteArray(12) { (it * 3 + 2).toByte() }
        val plain = "{\"v\":1,\"token\":\"abc\"}".toByteArray(Charsets.UTF_8)
        val manualKey = javax.crypto.spec.SecretKeySpec(
            SettingsBackup.pbkdf2Sha256("pin-non-ascii-é", salt, 1_000, 32), "AES"
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, manualKey, GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(plain)
        val restored = SettingsBackup.decrypt(cipherText, "pin-non-ascii-é", salt, iv, 1_000)
        assertArrayEquals(plain, restored)
    }

    @Test
    fun decryptMemulihkanTeksDanMenolakPinSalah() {
        // Putaran penuh inti kripto tanpa lapisan Base64/JSON Android:
        // enkripsi dengan deriveKey modern, dekripsi lewat decrypt() dwi-KDF.
        val salt = ByteArray(16) { (it * 7 + 1).toByte() }
        val iv = ByteArray(12) { (it * 3 + 2).toByte() }
        val plain = "{\"v\":1,\"token\":\"abc\"}".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SettingsBackup.deriveKey("pin-cadangan-kuat", salt, true, 1_000), GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(plain)
        val restored = SettingsBackup.decrypt(cipherText, "pin-cadangan-kuat", salt, iv, 1_000)
        assertArrayEquals(plain, restored)
        try {
            SettingsBackup.decrypt(cipherText, "pin-yang-salah-total", salt, iv, 1_000)
            fail("PIN salah harus menggagalkan dekripsi")
        } catch (e: Exception) {
            assertTrue(e is javax.crypto.AEADBadTagException || e.cause is javax.crypto.AEADBadTagException || e.message != null)
        }
    }

    @Test
    fun decryptMenolakCipherTextRusak() {
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SettingsBackup.deriveKey("pin-cadangan-kuat", salt, true, 1_000), GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal("halo".toByteArray(Charsets.UTF_8))
        cipherText[0] = (cipherText[0].toInt() xor 0xFF).toByte()
        try {
            SettingsBackup.decrypt(cipherText, "pin-cadangan-kuat", salt, iv, 1_000)
            fail("ciphertext rusak harus menggagalkan dekripsi")
        } catch (e: Exception) {
            // Diharapkan: tag GCM tidak cocok di kedua KDF.
        }
    }
}
