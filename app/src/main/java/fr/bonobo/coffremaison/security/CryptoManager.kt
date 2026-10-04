package fr.bonobo.coffremaison.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Encrypts project content with a non-exportable Android Keystore key. */
class CryptoManager {
    private val alias = "coffre_maison_content_key"
    private fun key(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? javax.crypto.SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }
    fun encrypt(value: String): String { val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key()); return Base64.encodeToString(c.iv + c.doFinal(value.encodeToByteArray()), Base64.NO_WRAP) }
    fun decrypt(value: String): String { val raw = Base64.decode(value, Base64.NO_WRAP); val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12))); return c.doFinal(raw.copyOfRange(12, raw.size)).decodeToString() }
    fun encryptBytes(value: ByteArray): ByteArray { val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key()); return c.iv + c.doFinal(value) }
    fun decryptBytes(value: ByteArray): ByteArray { val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, 12))); return c.doFinal(value.copyOfRange(12, value.size)) }
}
