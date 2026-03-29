package dev.remodex.android.app

import dev.remodex.android.feature.pairing.PairingQrPayload
import dev.remodex.android.feature.pairing.RelayBootstrapPhase
import dev.remodex.android.feature.pairing.TrustedReconnectRecord
import dev.remodex.android.model.ConnectionPhase
import dev.remodex.android.model.ThreadDetail
import dev.remodex.android.model.ThreadStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppViewModelReconnectPolicyTest {
    private val payload = PairingQrPayload(
        version = 2,
        relay = "wss://relay.example.com/relay",
        sessionId = "session-123",
        macDeviceId = "mac-device-1",
        macIdentityPublicKey = "mac-public-key",
        expiresAt = 1_900_000_000_000L,
    )
    private val trustedRecord = TrustedReconnectRecord(
        macDeviceId = "mac-device-1",
        macIdentityPublicKey = "mac-public-key",
        relay = "wss://relay.example.com/relay",
        phoneDeviceId = "phone-device-1",
        phoneIdentityPrivateKey = "phone-private-key",
        phoneIdentityPublicKey = "phone-public-key",
        lastPairedAt = 1_900_000_000_000L,
        displayName = "Josh's Mac",
    )

    @Test
    fun allowsAutoReconnectFromHomeWhenTrustedMacExistsAndNoSessionIsLive() {
        assertTrue(
            shouldAttemptTrustedAutoReconnect(
                hasSavedTrustedMac = true,
                hasActiveSession = false,
                isRecoveringTrustedLiveSession = false,
                currentScreen = AppScreen.Home,
                stagedPairingPayload = null,
                pairingCodeInput = "",
                bootstrapPhase = RelayBootstrapPhase.Idle,
            ),
        )
    }

    @Test
    fun blocksAutoReconnectWhileManualPairingFlowIsActive() {
        assertFalse(
            shouldAttemptTrustedAutoReconnect(
                hasSavedTrustedMac = true,
                hasActiveSession = false,
                isRecoveringTrustedLiveSession = false,
                currentScreen = AppScreen.Pairing,
                stagedPairingPayload = payload,
                pairingCodeInput = "",
                bootstrapPhase = RelayBootstrapPhase.Idle,
            ),
        )
    }

    @Test
    fun blocksAutoReconnectWhenUserHasTypedPairingInput() {
        assertFalse(
            shouldAttemptTrustedAutoReconnect(
                hasSavedTrustedMac = true,
                hasActiveSession = false,
                isRecoveringTrustedLiveSession = false,
                currentScreen = AppScreen.Home,
                stagedPairingPayload = null,
                pairingCodeInput = "{\"relay\":\"wss://relay.example.com/relay\"}",
                bootstrapPhase = RelayBootstrapPhase.Idle,
            ),
        )
    }

    @Test
    fun blocksAutoReconnectWhenSessionAlreadyExistsOrReconnectIsRunning() {
        assertFalse(
            shouldAttemptTrustedAutoReconnect(
                hasSavedTrustedMac = true,
                hasActiveSession = true,
                isRecoveringTrustedLiveSession = false,
                currentScreen = AppScreen.Home,
                stagedPairingPayload = null,
                pairingCodeInput = "",
                bootstrapPhase = RelayBootstrapPhase.Idle,
            ),
        )
        assertFalse(
            shouldAttemptTrustedAutoReconnect(
                hasSavedTrustedMac = true,
                hasActiveSession = false,
                isRecoveringTrustedLiveSession = true,
                currentScreen = AppScreen.Home,
                stagedPairingPayload = null,
                pairingCodeInput = "",
                bootstrapPhase = RelayBootstrapPhase.Idle,
            ),
        )
    }

    @Test
    fun blocksAutoReconnectDuringHandshakePhases() {
        assertFalse(
            shouldAttemptTrustedAutoReconnect(
                hasSavedTrustedMac = true,
                hasActiveSession = false,
                isRecoveringTrustedLiveSession = false,
                currentScreen = AppScreen.Home,
                stagedPairingPayload = null,
                pairingCodeInput = "",
                bootstrapPhase = RelayBootstrapPhase.Connecting,
            ),
        )
        assertFalse(
            shouldAttemptTrustedAutoReconnect(
                hasSavedTrustedMac = true,
                hasActiveSession = false,
                isRecoveringTrustedLiveSession = false,
                currentScreen = AppScreen.Home,
                stagedPairingPayload = null,
                pairingCodeInput = "",
                bootstrapPhase = RelayBootstrapPhase.Handshaking,
            ),
        )
    }

    @Test
    fun keepsLiveSessionWhileClearingOnlyTheSwitchDraft() {
        assertFalse(
            shouldDisconnectActiveSessionWhenClearingPairing(
                isShowingLiveThreads = true,
                bootstrapPhase = RelayBootstrapPhase.Verified,
            ),
        )
    }

    @Test
    fun disconnectsOnlyWhenClearingAnInProgressBootstrap() {
        assertTrue(
            shouldDisconnectActiveSessionWhenClearingPairing(
                isShowingLiveThreads = false,
                bootstrapPhase = RelayBootstrapPhase.Handshaking,
            ),
        )
        assertFalse(
            shouldDisconnectActiveSessionWhenClearingPairing(
                isShowingLiveThreads = false,
                bootstrapPhase = RelayBootstrapPhase.Idle,
            ),
        )
    }

    @Test
    fun savedDeviceReconnectUsesConnectingPhaseInsteadOfSavedTrustWhileHandshakeRuns() {
        assertEquals(
            ConnectionPhase.Connecting,
            resolveBridgeConnectionPhase(
                payload = null,
                trustedReconnectRecord = trustedRecord,
                bootstrapPhase = RelayBootstrapPhase.Connecting,
                bootstrapVerification = null,
            ),
        )
        assertEquals(
            ConnectionPhase.Handshaking,
            resolveBridgeConnectionPhase(
                payload = null,
                trustedReconnectRecord = trustedRecord,
                bootstrapPhase = RelayBootstrapPhase.Handshaking,
                bootstrapVerification = null,
            ),
        )
        assertEquals(
            ConnectionPhase.TrustedMac,
            resolveBridgeConnectionPhase(
                payload = null,
                trustedReconnectRecord = trustedRecord,
                bootstrapPhase = RelayBootstrapPhase.Idle,
                bootstrapVerification = null,
            ),
        )
    }

    @Test
    fun coolsDownRepeatedAutoReconnectAttemptsForShortWindow() {
        assertTrue(
            isTrustedAutoReconnectCoolingDown(
                lastAttemptElapsedRealtimeMs = 1_000L,
                nowElapsedRealtimeMs = 4_000L,
            ),
        )
        assertFalse(
            isTrustedAutoReconnectCoolingDown(
                lastAttemptElapsedRealtimeMs = 1_000L,
                nowElapsedRealtimeMs = 6_500L,
            ),
        )
        assertFalse(
            isTrustedAutoReconnectCoolingDown(
                lastAttemptElapsedRealtimeMs = null,
                nowElapsedRealtimeMs = 6_500L,
            ),
        )
    }

    @Test
    fun liveSyncLoopRunsForForegroundLiveSessionEvenBeforeDetailSelectionExists() {
        assertTrue(
            shouldRunLiveThreadSyncLoop(
                isAppInForeground = true,
                isShowingLiveThreads = true,
                hasActiveSession = true,
                isRecoveringTrustedLiveSession = false,
            ),
        )
        assertTrue(
            shouldRunLiveThreadSyncLoop(
                isAppInForeground = true,
                isShowingLiveThreads = true,
                hasActiveSession = true,
                isRecoveringTrustedLiveSession = false,
            ),
        )

        assertFalse(
            shouldRunLiveThreadSyncLoop(
                isAppInForeground = false,
                isShowingLiveThreads = true,
                hasActiveSession = true,
                isRecoveringTrustedLiveSession = false,
            ),
        )
        assertFalse(
            shouldRunLiveThreadSyncLoop(
                isAppInForeground = true,
                isShowingLiveThreads = false,
                hasActiveSession = true,
                isRecoveringTrustedLiveSession = false,
            ),
        )
        assertFalse(
            shouldRunLiveThreadSyncLoop(
                isAppInForeground = true,
                isShowingLiveThreads = true,
                hasActiveSession = false,
                isRecoveringTrustedLiveSession = false,
            ),
        )
        assertFalse(
            shouldRunLiveThreadSyncLoop(
                isAppInForeground = true,
                isShowingLiveThreads = true,
                hasActiveSession = true,
                isRecoveringTrustedLiveSession = true,
            ),
        )
    }

    @Test
    fun usesFastLiveSyncWhileSelectedThreadIsRunningOrActionInFlight() {
        val runningDetail = ThreadDetail(
            threadId = "thread-1",
            subtitle = "Live",
            stateLabel = "Running",
            entries = emptyList(),
            status = ThreadStatus.Running,
            activeTurnId = "turn-1",
        )

        assertEquals(
            liveThreadRunningSyncIntervalMs,
            liveThreadSyncDelayMs(
                selectedThreadId = "thread-1",
                selectedDetail = runningDetail,
                liveThreadActionState = null,
            ),
        )

        assertEquals(
            liveThreadRunningSyncIntervalMs,
            liveThreadSyncDelayMs(
                selectedThreadId = "thread-1",
                selectedDetail = runningDetail.copy(
                    status = ThreadStatus.Waiting,
                    activeTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                ),
                liveThreadActionState = LiveThreadActionState("thread-1", LiveThreadAction.Sending),
            ),
        )
    }

    @Test
    fun usesSlowerLiveSyncAfterSelectedThreadSettles() {
        val settledDetail = ThreadDetail(
            threadId = "thread-1",
            subtitle = "Live",
            stateLabel = "Completed",
            entries = emptyList(),
            status = ThreadStatus.Completed,
        )

        assertEquals(
            liveThreadIdleSyncIntervalMs,
            liveThreadSyncDelayMs(
                selectedThreadId = "thread-1",
                selectedDetail = settledDetail,
                liveThreadActionState = null,
            ),
        )

        assertEquals(
            liveThreadListSyncIntervalMs,
            liveThreadSyncDelayMs(
                selectedThreadId = null,
                selectedDetail = settledDetail,
                liveThreadActionState = null,
            ),
        )
    }

    @Test
    fun showsBlockingPairingOverlayOnlyDuringConnectHandshakeBeforeThreadsLoad() {
        assertTrue(
            shouldShowPairingTransitionOverlay(
                bootstrapPhase = RelayBootstrapPhase.Connecting,
                isShowingLiveThreads = false,
            ),
        )
        assertTrue(
            shouldShowPairingTransitionOverlay(
                bootstrapPhase = RelayBootstrapPhase.Handshaking,
                isShowingLiveThreads = false,
            ),
        )
        assertFalse(
            shouldShowPairingTransitionOverlay(
                bootstrapPhase = RelayBootstrapPhase.Idle,
                isShowingLiveThreads = false,
            ),
        )
        assertFalse(
            shouldShowPairingTransitionOverlay(
                bootstrapPhase = RelayBootstrapPhase.Connecting,
                isShowingLiveThreads = true,
            ),
        )
    }
}
