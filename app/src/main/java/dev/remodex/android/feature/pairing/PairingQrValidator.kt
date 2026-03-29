package dev.remodex.android.feature.pairing

import androidx.compose.runtime.Immutable
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

private const val pairingQrVersion = 2
private const val pairingClockSkewToleranceMs = 60_000L
private val pairingPayloadKeys = setOf(
    "relay",
    "sessionId",
    "macDeviceId",
    "macIdentityPublicKey",
    "expiresAt",
    "v",
)
private val pairingExpiryFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")

@Immutable
data class PairingQrPayload(
    val version: Int,
    val relay: String,
    val sessionId: String,
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val expiresAt: Long,
)

sealed interface PairingQrValidationResult {
    data class Success(val payload: PairingQrPayload) : PairingQrValidationResult

    data class Invalid(val message: String) : PairingQrValidationResult

    data class UpdateRequired(val message: String) : PairingQrValidationResult
}

enum class PairingStatusTone {
    Success,
    Error,
    UpdateRequired,
}

@Immutable
data class PairingStatusMessage(
    val tone: PairingStatusTone,
    val title: String,
    val body: String,
)

fun validatePairingQrCode(
    rawCode: String,
    nowMs: Long = System.currentTimeMillis(),
): PairingQrValidationResult {
    val normalizedCode = rawCode.trim()
    if (normalizedCode.isEmpty()) {
        return PairingQrValidationResult.Invalid(
            "Paste the pairing code from the Mac bridge first.",
        )
    }

    val jsonObject = try {
        JSONObject(normalizedCode)
    } catch (_: Exception) {
        return PairingQrValidationResult.Invalid(
            "Not a valid secure pairing code. Make sure the full JSON payload came from the latest Remodex bridge.",
        )
    }

    val looksLikePairingPayload = looksLikeRemodexPairingPayload(jsonObject)
    val version = readInt(jsonObject, "v")
    if (looksLikePairingPayload && version != pairingQrVersion) {
        return PairingQrValidationResult.UpdateRequired(
            "This pairing code came from a different bridge version. Update Remodex on your Mac and generate a new code.",
        )
    }

    val relay = readRequiredString(jsonObject, "relay")
        ?: return PairingQrValidationResult.Invalid(
            "Pairing code is missing the relay URL. Generate a new code from the Mac bridge.",
        )
    val sessionId = readRequiredString(jsonObject, "sessionId")
        ?: return PairingQrValidationResult.Invalid(
            "Pairing code is missing the session ID. Generate a new code from the Mac bridge.",
        )
    val macDeviceId = readRequiredString(jsonObject, "macDeviceId")
        ?: return PairingQrValidationResult.Invalid(
            "Pairing code is missing the Mac device ID. Generate a new code from the Mac bridge.",
        )
    val macIdentityPublicKey = readRequiredString(jsonObject, "macIdentityPublicKey")
        ?: return PairingQrValidationResult.Invalid(
            "Pairing code is missing the Mac identity key. Generate a new code from the Mac bridge.",
        )
    val expiresAt = readLong(jsonObject, "expiresAt")
        ?: return PairingQrValidationResult.Invalid(
            "Pairing code is missing its expiry time. Generate a new code from the Mac bridge.",
        )

    if (!isValidRelayUrl(relay)) {
        return PairingQrValidationResult.Invalid(
            "Pairing code contains an invalid relay URL. Generate a new code from the Mac bridge.",
        )
    }

    if (!isValidBase64(macIdentityPublicKey)) {
        return PairingQrValidationResult.Invalid(
            "Pairing code contains an unreadable Mac identity key. Generate a new code from the Mac bridge.",
        )
    }

    if (expiresAt + pairingClockSkewToleranceMs < nowMs) {
        return PairingQrValidationResult.Invalid(
            "This pairing code has expired. Generate a new one from the Mac bridge.",
        )
    }

    return PairingQrValidationResult.Success(
        PairingQrPayload(
            version = version ?: pairingQrVersion,
            relay = relay,
            sessionId = sessionId,
            macDeviceId = macDeviceId,
            macIdentityPublicKey = macIdentityPublicKey,
            expiresAt = expiresAt,
        ),
    )
}

fun PairingQrPayload.relayDisplayLabel(): String {
    val relayUri = runCatching { URI(relay) }.getOrNull() ?: return relay
    val host = relayUri.host ?: return relay
    val path = relayUri.path?.takeIf { it.isNotBlank() && it != "/" }.orEmpty()
    val port = relayUri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
    return "$host$port$path"
}

fun PairingQrPayload.maskedSessionLabel(): String = maskIdentifier(sessionId)

fun PairingQrPayload.maskedMacDeviceLabel(): String = "Device ${maskIdentifier(macDeviceId)}"

fun PairingQrPayload.publicKeyFingerprint(): String {
    val publicKeyBytes = Base64.getDecoder().decode(macIdentityPublicKey)
    val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
    return digest
        .take(6)
        .joinToString(separator = "") { byte -> "%02X".format(byte) }
}

fun PairingQrPayload.expiryLabel(zoneId: ZoneId = ZoneId.systemDefault()): String {
    return pairingExpiryFormatter.format(Instant.ofEpochMilli(expiresAt).atZone(zoneId))
}

private fun looksLikeRemodexPairingPayload(jsonObject: JSONObject): Boolean {
    val iterator = jsonObject.keys()
    while (iterator.hasNext()) {
        if (iterator.next() in pairingPayloadKeys) {
            return true
        }
    }
    return false
}

private fun readRequiredString(jsonObject: JSONObject, key: String): String? {
    val value = jsonObject.optString(key, "").trim()
    return value.ifEmpty { null }
}

private fun readInt(jsonObject: JSONObject, key: String): Int? {
    val value = jsonObject.opt(key)
    return when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}

private fun readLong(jsonObject: JSONObject, key: String): Long? {
    val value = jsonObject.opt(key)
    return when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}

private fun isValidRelayUrl(relay: String): Boolean {
    val relayUri = runCatching { URI(relay) }.getOrNull() ?: return false
    val scheme = relayUri.scheme?.lowercase()
    return (scheme == "ws" || scheme == "wss") && !relayUri.host.isNullOrBlank()
}

private fun isValidBase64(value: String): Boolean {
    return runCatching { Base64.getDecoder().decode(value) }.isSuccess
}

private fun maskIdentifier(value: String, visibleTail: Int = 8): String {
    return if (value.length <= visibleTail) {
        value
    } else {
        "...${value.takeLast(visibleTail)}"
    }
}
