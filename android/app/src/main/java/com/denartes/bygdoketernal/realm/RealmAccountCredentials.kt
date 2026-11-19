package com.denartes.bygdoketernal.realm

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

internal data class RealmAccountCredentials(
    val saltHex: String,
    val verifierHex: String
)

internal object RealmAccountCredentialGenerator {
    private val modulus = BigInteger(
        "894B645E89E1535BBDAD5B8B290650530801B18EBFBF5E8FAB3C82872A3E9BB7",
        16
    )
    private val generator = BigInteger.valueOf(7)

    fun generate(username: String, password: String): RealmAccountCredentials {
        val salt = ByteArray(32).also(SecureRandom()::nextBytes)
        val normalizedUsername = username.uppercase(Locale.ROOT)
        val normalizedPassword = password.uppercase(Locale.ROOT)
        val identityHash = sha1("$normalizedUsername:$normalizedPassword".toByteArray(Charsets.UTF_8))
        val exponent = BigInteger(1, sha1(salt + identityHash))
        val verifier = generator.modPow(exponent, modulus)
        return RealmAccountCredentials(salt.toHex(), verifier.toFixedHex(32))
    }

    private fun sha1(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(value)

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private fun BigInteger.toFixedHex(size: Int): String {
        val bytes = toByteArray().let { if (it.size > size) it.takeLast(size).toByteArray() else it }
        return ByteArray(size - bytes.size).toHex() + bytes.toHex()
    }
}