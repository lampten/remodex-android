package dev.remodex.android.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrustedReconnectStoreTest {
    @Test
    fun treatsLiteralNullAndUndefinedNamesAsMissing() {
        assertNull(readMeaningfulOptionalString(null))
        assertNull(readMeaningfulOptionalString(""))
        assertNull(readMeaningfulOptionalString("null"))
        assertNull(readMeaningfulOptionalString(" undefined "))
    }

    @Test
    fun fallsBackToMaskedDeviceLabelWhenDisplayNameIsLiteralNull() {
        val record = TrustedReconnectRecord(
            macDeviceId = "mac-device-1234",
            macIdentityPublicKey = "public-key",
            relay = "wss://relay.example.com/relay",
            phoneDeviceId = "phone-device-1",
            phoneIdentityPrivateKey = "private",
            phoneIdentityPublicKey = "public",
            lastPairedAt = 1_900_000_000_000L,
            displayName = "null",
        )

        assertEquals("Device ...ice-1234", record.maskedMacDeviceLabel())
    }
}
