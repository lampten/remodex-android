package dev.remodex.android.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PairingQrValidatorTest {
    private val nowMs = 1_900_000_000_000L
    private val publicKey = Base64.getEncoder().encodeToString("bridge-public-key".toByteArray())

    @Test
    fun acceptsValidPairingPayload() {
        val result = validatePairingQrCode(
            rawCode = pairingPayloadJson(expiresAt = nowMs + 120_000),
            nowMs = nowMs,
        )

        assertTrue(result is PairingQrValidationResult.Success)
        val payload = (result as PairingQrValidationResult.Success).payload
        assertEquals(2, payload.version)
        assertEquals("session-123", payload.sessionId)
        assertEquals("Device ...device-1", payload.maskedMacDeviceLabel())
        assertEquals("example.com/relay", payload.relayDisplayLabel())
        assertEquals("...sion-123", payload.maskedSessionLabel())
    }

    @Test
    fun rejectsExpiredPayload() {
        val result = validatePairingQrCode(
            rawCode = pairingPayloadJson(expiresAt = nowMs - 61_000),
            nowMs = nowMs,
        )

        assertTrue(result is PairingQrValidationResult.Invalid)
        assertTrue((result as PairingQrValidationResult.Invalid).message.contains("expired", ignoreCase = true))
    }

    @Test
    fun rejectsMissingSessionId() {
        val result = validatePairingQrCode(
            rawCode = pairingPayloadJson(sessionId = ""),
            nowMs = nowMs,
        )

        assertTrue(result is PairingQrValidationResult.Invalid)
        assertTrue((result as PairingQrValidationResult.Invalid).message.contains("session ID"))
    }

    @Test
    fun flagsVersionMismatchAsUpdateRequired() {
        val result = validatePairingQrCode(
            rawCode = pairingPayloadJson(version = 1),
            nowMs = nowMs,
        )

        assertTrue(result is PairingQrValidationResult.UpdateRequired)
    }

    @Test
    fun rejectsInvalidRelayUrl() {
        val result = validatePairingQrCode(
            rawCode = pairingPayloadJson(relay = "https://example.com/relay"),
            nowMs = nowMs,
        )

        assertTrue(result is PairingQrValidationResult.Invalid)
        assertTrue((result as PairingQrValidationResult.Invalid).message.contains("relay URL"))
    }

    @Test
    fun rejectsNonJsonInput() {
        val result = validatePairingQrCode(
            rawCode = "not-json",
            nowMs = nowMs,
        )

        assertTrue(result is PairingQrValidationResult.Invalid)
        assertTrue((result as PairingQrValidationResult.Invalid).message.contains("valid secure pairing code"))
    }

    private fun pairingPayloadJson(
        version: Int = 2,
        relay: String = "wss://example.com/relay",
        sessionId: String = "session-123",
        macDeviceId: String = "mac-device-1",
        expiresAt: Long = nowMs + 120_000,
    ): String {
        return """
            {
              "v": $version,
              "relay": "$relay",
              "sessionId": "$sessionId",
              "macDeviceId": "$macDeviceId",
              "macIdentityPublicKey": "$publicKey",
              "expiresAt": $expiresAt
            }
        """.trimIndent()
    }
}
