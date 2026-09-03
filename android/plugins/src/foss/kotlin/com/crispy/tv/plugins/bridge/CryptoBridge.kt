package com.crispy.tv.plugins.bridge

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object CryptoBridge {

    fun digestHex(algorithm: String, dataHex: String): String {
        val digest = java.security.MessageDigest.getInstance(normalizeHash(algorithm))
        return digest.digest(HexCodec.decode(dataHex)).toHex()
    }

    fun hmacHex(algorithm: String, keyHex: String, dataHex: String): String {
        val mac = Mac.getInstance("Hmac" + normalizeHash(algorithm).replace("-", ""))
        mac.init(SecretKeySpec(HexCodec.decode(keyHex), "Hmac" + normalizeHash(algorithm)))
        return mac.doFinal(HexCodec.decode(dataHex)).toHex()
    }

    fun aesEncryptHex(mode: String, keyHex: String, ivHex: String, dataHex: String): String {
        val cipher = Cipher.getInstance(cipherTransformation(mode))
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(keyHex), ivParameter(ivHex))
        return cipher.doFinal(HexCodec.decode(dataHex)).toHex()
    }

    fun aesDecryptHex(mode: String, keyHex: String, ivHex: String, dataHex: String): String {
        val cipher = Cipher.getInstance(cipherTransformation(mode))
        cipher.init(Cipher.DECRYPT_MODE, secretKey(keyHex), ivParameter(ivHex))
        return cipher.doFinal(HexCodec.decode(dataHex)).toHex()
    }

    fun utf8ToHex(text: String): String = text.toByteArray(Charsets.UTF_8).toHex()

    fun utf8BytesJson(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val array = org.json.JSONArray()
        bytes.forEach { array.put(it.toInt() and 0xFF) }
        return array.toString()
    }

    fun hexToUtf8(hex: String): String = String(HexCodec.decode(hex), Charsets.UTF_8)

    fun base64EncodeHex(hex: String): String =
        Base64.getEncoder().encodeToString(HexCodec.decode(hex))

    fun base64DecodeHex(base64: String): String =
        Base64.getDecoder().decode(base64.trim()).toHex()

    fun base64EncodeText(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    fun base64DecodeText(base64: String): String =
        String(Base64.getDecoder().decode(base64.trim()), Charsets.UTF_8)

    private fun secretKey(keyHex: String): SecretKeySpec =
        SecretKeySpec(HexCodec.decode(keyHex), "AES")

    private fun ivParameter(ivHex: String): IvParameterSpec =
        IvParameterSpec(HexCodec.decode(ivHex))

    private fun cipherTransformation(mode: String): String {
        val normalized = mode.uppercase().replace("_", "-")
        val noPadding = normalized.endsWith("-NOPADDING")
        val core = normalized.removeSuffix("-NOPADDING")
        val transformation =
            when {
                core.contains("ECB") -> "AES/ECB/NoPadding"
                core.contains("GCM") -> "AES/GCM/NoPadding"
                else -> "AES/CBC/" + if (noPadding) "NoPadding" else "PKCS5Padding"
            }
        return transformation
    }

    private fun normalizeHash(algorithm: String): String {
        val name = algorithm.uppercase().replace("-", "").replace("/", "")
        return when (name) {
            "SHA1" -> "SHA-1"
            "SHA256" -> "SHA-256"
            "SHA384" -> "SHA-384"
            "SHA512" -> "SHA-512"
            "MD5" -> "MD5"
            else -> throw PluginExecutionBlockedException("Unsupported hash algorithm: $algorithm")
        }
    }

    private fun ByteArray.toHex(): String = HexCodec.encode(this)
}

internal object HexCodec {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            out[index * 2] = DIGITS[value ushr 4]
            out[index * 2 + 1] = DIGITS[value and 0x0F]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        val cleaned = hex.replace("[^0-9a-fA-F]".toRegex(), "")
        val normalized = if (cleaned.length % 2 == 1) "0$cleaned" else cleaned
        val out = ByteArray(normalized.length / 2)
        for (index in normalized.indices step 2) {
            out[index / 2] =
                ((Character.digit(normalized[index], 16) shl 4) + Character.digit(normalized[index + 1], 16)).toByte()
        }
        return out
    }
}
