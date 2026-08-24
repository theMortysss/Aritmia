package my.diplom.aritmia.security

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2 password storage with backward-compatible verification for legacy plaintext rows. */
object PasswordHasher {
    private const val PREFIX = "pbkdf2_sha256"
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = derive(password, salt, ITERATIONS)
        return listOf(
            PREFIX,
            ITERATIONS.toString(),
            Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(derived, Base64.NO_WRAP)
        ).joinToString("\$")
    }

    fun verify(password: String, stored: String): Boolean {
        if (!isHashed(stored)) {
            // Поддержка записей, созданных старой версией приложения.
            return constantTimeEquals(password.toByteArray(), stored.toByteArray())
        }

        val parts = stored.split('$')
        if (parts.size != 4) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { Base64.decode(parts[2], Base64.NO_WRAP) }.getOrNull() ?: return false
        val expected = runCatching { Base64.decode(parts[3], Base64.NO_WRAP) }.getOrNull() ?: return false
        val actual = derive(password, salt, iterations)
        return constantTimeEquals(actual, expected)
    }

    fun needsRehash(stored: String): Boolean = !isHashed(stored) ||
        stored.split('$').getOrNull(1)?.toIntOrNull() != ITERATIONS

    private fun isHashed(value: String): Boolean = value.startsWith("$PREFIX\$")

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
