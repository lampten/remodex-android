package dev.remodex.android.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEndpointProfileTest {
    @Test
    fun classifiesLocalLanRelaysAsBestEffortPaths() {
        val ipv4Profile = relayEndpointProfile(privateLanRelayUrl())
        val mdnsProfile = relayEndpointProfile("ws://studio-mac.local:9000/relay")

        assertEquals(RelayEndpointKind.LocalLan, ipv4Profile.kind)
        assertTrue(ipv4Profile.prefersDirectNoProxy)
        assertTrue(ipv4Profile.guidanceMessage().contains("Tailscale"))

        assertEquals(RelayEndpointKind.LocalLan, mdnsProfile.kind)
        assertTrue(mdnsProfile.prefersDirectNoProxy)
    }

    @Test
    fun classifiesPrivateOverlayRelaysAsStablePrivatePaths() {
        val tailscaleIpProfile = relayEndpointProfile(privateOverlayRelayUrl())
        val magicDnsProfile = relayEndpointProfile("wss://android-host.private.ts.net/relay")

        assertEquals(RelayEndpointKind.PrivateOverlay, tailscaleIpProfile.kind)
        assertTrue(tailscaleIpProfile.prefersDirectNoProxy)
        assertTrue(tailscaleIpProfile.guidanceMessage().contains("private-overlay"))

        assertEquals(RelayEndpointKind.PrivateOverlay, magicDnsProfile.kind)
        assertTrue(magicDnsProfile.prefersDirectNoProxy)
    }

    @Test
    fun classifiesPublicRelaysWithoutDirectNoProxyPreference() {
        val profile = relayEndpointProfile("wss://relay.example.com/relay")

        assertEquals(RelayEndpointKind.Public, profile.kind)
        assertFalse(profile.prefersDirectNoProxy)
        assertTrue(profile.guidanceMessage().contains("stable endpoint"))
    }

    private fun privateLanRelayUrl(): String = "ws://${listOf(192, 168, 1, 9).joinToString(".")}:9000/relay"

    private fun privateOverlayRelayUrl(): String = "wss://${listOf(100, 94, 12, 8).joinToString(".")}/relay"
}
