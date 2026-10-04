package fr.bonobo.coffremaison.security

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PortableBackupCrypto {
    private val magic = "CMBK2".encodeToByteArray()
    private const val iterations = 180_000

    fun encrypt(plain: ByteArray, password: String): ByteArray {
        require(password.length >= 8) { "Le mot de passe de sauvegarde doit contenir au moins 8 caractères" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return ByteArrayOutputStream().apply {
            write(magic)
            write(salt)
            write(iv)
            write(cipher.doFinal(plain))
        }.toByteArray()
    }

    fun decrypt(data: ByteArray, password: String): ByteArray {
        require(data.size > magic.size + 16 + 12 && data.copyOfRange(0, magic.size).contentEquals(magic)) { "Format de sauvegarde inconnu" }
        val saltStart = magic.size
        val ivStart = saltStart + 16
        val salt = data.copyOfRange(saltStart, ivStart)
        val iv = data.copyOfRange(ivStart, ivStart + 12)
        val encrypted = data.copyOfRange(ivStart + 12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
