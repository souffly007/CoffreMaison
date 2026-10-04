package fr.bonobo.coffremaison.security
import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
class AppLockManager(context: Context) {
    private val prefs=context.getSharedPreferences("coffre_lock",Context.MODE_PRIVATE)
    fun hasPin()=prefs.contains("hash")
    fun setPin(pin:CharArray){ require(pin.size==6&&pin.all(Char::isDigit)); val salt=ByteArray(16).also(SecureRandom()::nextBytes); val hash=derive(pin,salt); prefs.edit().putString("salt",Base64.encodeToString(salt,0)).putString("hash",Base64.encodeToString(hash,0)).apply(); pin.fill('\u0000');hash.fill(0) }
    fun verify(pin:CharArray):Boolean { val salt=Base64.decode(prefs.getString("salt","")!!,0); val expected=Base64.decode(prefs.getString("hash","")!!,0); val actual=derive(pin,salt); pin.fill('\u0000'); return MessageDigest.isEqual(expected,actual).also { expected.fill(0);actual.fill(0) } }
    private fun derive(pin:CharArray,salt:ByteArray)=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin,salt,180_000,256)).encoded
}
