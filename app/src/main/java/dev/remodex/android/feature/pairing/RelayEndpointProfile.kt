package dev.remodex.android.feature.pairing

import java.net.URI
import java.util.Locale

enum class RelayEndpointKind {
    LocalLan,
    PrivateOverlay,
    Public,
    Unknown,
}

data class RelayEndpointProfile(
    val relay: String,
    val kind: RelayEndpointKind,
    val host: String?,
) {
    val prefersDirectNoProxy: Boolean
        get() = kind == RelayEndpointKind.LocalLan || kind == RelayEndpointKind.PrivateOverlay

    fun guidanceMessage(savedTrust: Boolean = false): String {
        return when (kind) {
            RelayEndpointKind.LocalLan -> if (savedTrust) {
                "This saved relay looks like a same-network LAN path. Trusted reconnect can work while the phone stays on that network, but daily use is more reliable through a stable relay path such as Tailscale."
            } else {
                "This relay looks like a same-network LAN path. Pairing can work there, but daily reconnects are more reliable through a stable relay path such as Tailscale."
            }

            RelayEndpointKind.PrivateOverlay -> if (savedTrust) {
                "This saved relay looks like a private-overlay path, such as Tailscale. Trusted reconnect should stay usable across normal office and home network switching as long as the phone can still reach that private network."
            } else {
                "This relay looks like a private-overlay path, such as Tailscale. Android can keep using it as a stable reconnect route when the phone moves between networks."
            }

            RelayEndpointKind.Public -> if (savedTrust) {
                "This saved relay is a stable endpoint for trusted reconnect. Android can keep using it across normal network changes as long as the phone can still reach it."
            } else {
                "This relay is a stable endpoint. Android can keep using trusted reconnect through it across normal network changes as long as the phone can still reach it."
            }

            RelayEndpointKind.Unknown -> if (savedTrust) {
                "This saved relay will be treated as a generic endpoint. Trusted reconnect should still use it whenever the phone can reach it."
            } else {
                "This relay target is staged inside the app shell. Android will treat it as a generic endpoint and use trusted reconnect whenever it is reachable."
            }
        }
    }
}

fun relayEndpointProfile(relay: String): RelayEndpointProfile {
    val uri = runCatching { URI(relay.trim()) }.getOrNull()
    val host = uri?.host?.trim()?.lowercase(Locale.US)

    val kind = when {
        host.isNullOrEmpty() -> RelayEndpointKind.Unknown
        host.endsWith(".local") -> RelayEndpointKind.LocalLan
        isPrivateIpv4Host(host) -> RelayEndpointKind.LocalLan
        isLocalIpv6Host(host) -> RelayEndpointKind.LocalLan
        isCarrierGradePrivateIpv4Host(host) -> RelayEndpointKind.PrivateOverlay
        isTailscaleMagicDnsHost(host) -> RelayEndpointKind.PrivateOverlay
        else -> RelayEndpointKind.Public
    }

    return RelayEndpointProfile(
        relay = relay,
        kind = kind,
        host = host,
    )
}

fun PairingQrPayload.relayEndpointProfile(): RelayEndpointProfile = relayEndpointProfile(relay)

fun TrustedReconnectRecord.relayEndpointProfile(): RelayEndpointProfile = relayEndpointProfile(relay)

fun PairingQrPayload.relayGuidanceMessage(): String = relayEndpointProfile().guidanceMessage()

fun TrustedReconnectRecord.relayGuidanceMessage(): String = relayEndpointProfile().guidanceMessage(savedTrust = true)

private fun isPrivateIpv4Host(host: String): Boolean {
    val octets = host.split('.').mapNotNull { it.toIntOrNull() }
    if (octets.size != 4) {
        return false
    }

    return when (octets[0]) {
        10 -> true
        172 -> octets[1] in 16..31
        192 -> octets[1] == 168
        169 -> octets[1] == 254
        else -> false
    }
}

private fun isCarrierGradePrivateIpv4Host(host: String): Boolean {
    val octets = host.split('.').mapNotNull { it.toIntOrNull() }
    if (octets.size != 4) {
        return false
    }

    return octets[0] == 100 && octets[1] in 64..127
}

private fun isTailscaleMagicDnsHost(host: String): Boolean {
    return host.endsWith(".ts.net") || host.endsWith(".beta.tailscale.net")
}

private fun isLocalIpv6Host(host: String): Boolean {
    val normalized = host.trim('[', ']').lowercase(Locale.US)
    return normalized.startsWith("fe80:")
        || normalized.startsWith("fc")
        || normalized.startsWith("fd")
}
