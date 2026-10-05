package ani.sanin.parsers

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MKissa's "aa-crypto" client scheme.
 *
 * The stream endpoint rejects any request whose GraphQL variables are not accompanied by an
 * `aa-crypto` header, so a native client has to reproduce the site's key handshake byte for byte:
 *
 *  1. scrape the site's crypto chunk and recover `buildId` + four base64 seed fragments,
 *  2. expand those into a 32 byte mask,
 *  3. HMAC the mask to prove it to `/client-crypto/v1/bootstrap`, which returns `partB`,
 *  4. `key = partB XOR mask`, then sign each GraphQL call with AES-GCM under that key.
 *
 * Every constant used below is read back out of the site's own config object at runtime
 * (see [MkissaBundle.parseConfig]); the literals in [Config] are only the values observed on the
 * build that was current when this was written, and act as a fallback if scraping fails.
 */
internal object MkissaCrypto {

    const val SEED_COUNT = 4
    const val HEADER_SIZE = 13
    const val EPOCH_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    const val EPOCH_GRACE_MS = 24L * 60 * 60 * 1000

    private const val KEY_SIZE = 32
    private const val SEED_SIZE = KEY_SIZE / SEED_COUNT
    private const val IV_SIZE = 12
    private const val TAG_LENGTH = 128
    private const val WINDOW_MS = 5 * 60 * 1000L

    /**
     * The site's `$f` config block. [parts] names the boot-token message fields in the order the
     * site joins them, which is *not* the order the Kotlin reference extension assumed.
     */
    data class Config(
        val saltMul: Int = 91,
        val saltAdd: Int = 128,
        val fragMul: Int = 63,
        val fragAdd: Int = 120,
        val bootPrefix: String = "utdUiYT:",
        val join: String = ":",
        val parts: List<String> = listOf("host", "lane", "group", "buildId", "epoch"),
    )

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** Expands `buildId` + seed fragments into the 32 byte mask used for both HMAC steps. */
    fun deriveMask(buildId: String, seeds: List<String>, cfg: Config): ByteArray? {
        if (buildId.isEmpty() || seeds.size != SEED_COUNT) return null

        val stream = ByteArray(KEY_SIZE) { i ->
            (buildId[i % buildId.length].code xor ((i * cfg.saltMul + cfg.saltAdd) and 0xFF)).toByte()
        }

        val mask = ByteArray(KEY_SIZE)
        for ((index, seed) in seeds.withIndex()) {
            val bytes = runCatching { Base64.decode(seed, Base64.DEFAULT) }.getOrNull() ?: return null
            if (bytes.size < SEED_SIZE) return null
            val base = index * SEED_SIZE
            for (offset in 0 until SEED_SIZE) {
                mask[base + offset] = (
                    (bytes[offset].toInt() and 0xFF) xor
                        (stream[base + offset].toInt() and 0xFF) xor
                        ((index * cfg.fragMul + offset * cfg.fragAdd) and 0xFF)
                    ).toByte()
            }
        }
        if (mask.all { it == 0.toByte() }) return null
        // The site also carries an `envXor` byte, but it is only folded in when the browser
        // environment probe fails; tokens built with it are rejected server side.
        return mask
    }

    fun deriveKey(mask: ByteArray, partB: ByteArray): SecretKeySpec {
        val keyBytes = ByteArray(KEY_SIZE) { i ->
            ((partB[i].toInt() and 0xFF) xor (mask[i % mask.size].toInt() and 0xFF)).toByte()
        }
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Proves possession of the mask. Field order and delimiter come from the site's config so a
     * rotated build keeps working; `www.` is stripped from the host exactly like the site does.
     */
    fun bootToken(
        mask: ByteArray,
        buildId: String,
        epoch: Long,
        keyGroup: String,
        refererHost: String,
        lane: String,
        cfg: Config,
    ): String {
        val host = refererHost.trim().removePrefix("www.")
        val inner = hmac(mask, cfg.bootPrefix + buildId)
        val fields = mapOf(
            "host" to host,
            "lane" to lane.trim(),
            "group" to keyGroup,
            "buildId" to buildId,
            "epoch" to epoch.toString(),
        )
        val message = cfg.parts.joinToString(cfg.join) { fields[it] ?: "" }
        return hmac(inner, message).toHex()
    }

    /** Epochs the server will currently accept, newest first. */
    fun epochCandidates(now: Long = System.currentTimeMillis()): List<Long> {
        val current = now / EPOCH_WINDOW_MS
        val inGrace = now - current * EPOCH_WINDOW_MS < EPOCH_GRACE_MS && current > 0
        return if (inGrace) listOf(current - 1, current) else listOf(current)
    }

    /** Fallbacks tried when the server answers 403/404, i.e. our epoch guess was off. */
    fun skewedEpochCandidates(now: Long = System.currentTimeMillis()): List<Long> {
        val current = now / EPOCH_WINDOW_MS
        return (listOf(current + 1, current - 1) - epochCandidates(now)).filter { it > 0 }
    }

    /** Builds the base64 `[version][iv][ciphertext+tag]` blob sent as `aaReq`. */
    fun buildAaReq(
        key: SecretKeySpec,
        epoch: Long,
        buildId: String,
        queryHash: String,
        lane: String,
    ): String {
        val ts = System.currentTimeMillis() / WINDOW_MS * WINDOW_MS
        val iv = MessageDigest.getInstance("SHA-256")
            .digest("$epoch:$buildId:$queryHash:$ts:$lane".toByteArray(Charsets.UTF_8))
            .copyOfRange(0, IV_SIZE)
        val payload = buildString {
            append('{')
            append("\"v\":1,")
            append("\"ts\":").append(ts).append(',')
            append("\"epoch\":").append(epoch).append(',')
            append("\"buildId\":\"").append(buildId).append("\",")
            append("\"qh\":\"").append(queryHash).append("\",")
            append("\"k\":\"").append(lane).append('"')
            append('}')
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(HEADER_SIZE + ciphertext.size)
        blob[0] = 1
        System.arraycopy(iv, 0, blob, 1, IV_SIZE)
        System.arraycopy(ciphertext, 0, blob, HEADER_SIZE, ciphertext.size)
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    fun decrypt(base64Payload: String, key: SecretKeySpec): String? {
        val blob = runCatching { Base64.decode(base64Payload, Base64.DEFAULT) }.getOrNull() ?: return null
        if (blob.size < HEADER_SIZE) return null
        val iv = blob.sliceArray(1 until HEADER_SIZE)
        val ciphertext = blob.sliceArray(HEADER_SIZE until blob.size)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun hmac(key: ByteArray, message: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message.toByteArray(Charsets.UTF_8))
        }
}
