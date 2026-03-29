package dev.remodex.android.feature.pairing

import dev.remodex.android.model.ThreadStatus
import dev.remodex.android.model.AccessMode
import dev.remodex.android.model.TimelineEntryKind
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

class RelayBootstrapServiceTest {
    private val fixedNow = Instant.parse("2026-03-26T20:00:00Z")

    @Test
    fun completesSecureBootstrapAgainstMatchingBridgeMessages() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(payload = payload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )
        val phases = mutableListOf<RelayBootstrapPhase>()

        val verification = service.bootstrap(
            payload = payload,
            onPhaseChanged = { phases += it },
        )

        assertEquals("wss://example.com/relay/session-123", factory.connectedUrl)
        assertEquals("iphone", factory.connectedHeaders["x-role"])
        assertEquals(listOf(
            RelayBootstrapPhase.Connecting,
            RelayBootstrapPhase.Handshaking,
            RelayBootstrapPhase.Verified,
        ), phases)
        assertEquals("...sion-123", verification.sessionLabel)
        assertEquals("example.com/relay", verification.relayLabel)
        assertEquals("mac-device-1", verification.macDeviceId)
        assertEquals(1, verification.keyEpoch)
        assertEquals("Initialize complete", verification.initializeLabel)
        assertEquals(fixedNow, verification.verifiedAt)
        assertTrue(factory.didReceiveResumeState)
        assertTrue(factory.didReceiveInitializeRequest)
        assertTrue(factory.didReceiveInitializedNotification)
        val liveThreads = service.fetchThreadList()
        assertEquals(1, liveThreads.size)
        assertEquals("live-thread-1", liveThreads.first().id)
        assertEquals("Live bridge thread", liveThreads.first().title)
        assertEquals("~/work/live-project", liveThreads.first().projectPath)
        assertEquals(ThreadStatus.Running, liveThreads.first().status)
        assertTrue(factory.didReceiveThreadListRequest)
        val liveDetail = service.fetchThreadDetail("live-thread-1")
        assertEquals("live-thread-1", liveDetail.threadId)
        assertEquals("cli • ~/work/live-project", liveDetail.subtitle)
        assertTrue(liveDetail.stateLabel.contains("active turn ready", ignoreCase = true))
        assertEquals(3, liveDetail.entries.size)
        assertEquals("turn-1", liveDetail.activeTurnId)
        assertTrue(liveDetail.isRunning)
        assertEquals("You", liveDetail.entries[0].speaker)
        assertEquals("Codex", liveDetail.entries[1].speaker)
        assertTrue(liveDetail.entries[2].body.contains("Loaded the real thread detail"))
        assertTrue(factory.didReceiveThreadReadRequest)
        val turnStartReceipt = service.sendTurnStart(
            threadId = "live-thread-1",
            userInput = "Please continue on the live thread.",
            projectPath = "~/work/live-project",
        )
        assertEquals("live-thread-1", turnStartReceipt.threadId)
        assertEquals("turn-2", turnStartReceipt.turnId)
        assertTrue(factory.didReceiveThreadResumeRequest)
        assertEquals("live-thread-1", factory.lastThreadResumeThreadId)
        assertTrue(factory.didReceiveTurnStartRequest)
        assertEquals("live-thread-1", factory.lastTurnStartThreadId)
        assertEquals("Please continue on the live thread.", factory.lastTurnStartUserInput)
        val updatedDetail = service.fetchThreadDetail("live-thread-1")
        assertEquals("turn-2", updatedDetail.activeTurnId)
        assertTrue(updatedDetail.entries.any { it.body.contains("Please continue on the live thread.") })
        assertTrue(updatedDetail.entries.any { it.body.contains("The live send request reached the bridge.") })
        assertFalse(factory.closed)

        service.disconnectActiveSession()
        assertTrue(factory.closed)
    }

    @Test
    fun startsNewThreadAndPreservesRequestedProjectPath() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(payload = payload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)
        val createdThread = service.startThread(
            preferredProjectPath = "~/work/new-project",
            modelIdentifier = "gpt-5.4",
        )

        assertTrue(factory.didReceiveThreadStartRequest)
        assertEquals("~/work/new-project", factory.lastThreadStartProjectPath)
        assertEquals("gpt-5.4", factory.lastThreadStartModel)
        assertEquals("live-thread-2", createdThread.id)
        assertEquals("~/work/new-project", createdThread.projectPath)
        assertEquals("Started from Android", createdThread.title)
    }

    @Test
    fun sendsFirstTurnOnFreshlyStartedThreadWithoutResumeRoundTrip() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(payload = payload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)
        val createdThread = service.startThread(
            preferredProjectPath = "~/work/new-project",
            modelIdentifier = "gpt-5.4",
        )
        service.sendTurnStart(
            threadId = createdThread.id,
            userInput = "First message in the fresh thread.",
            projectPath = createdThread.projectPath,
        )

        assertFalse(factory.didReceiveThreadResumeRequest)
        assertEquals("live-thread-2", factory.lastTurnStartThreadId)
        assertEquals("First message in the fresh thread.", factory.lastTurnStartUserInput)
    }

    @Test
    fun rejectsThreadListRequestWithoutActiveSession() = runBlocking {
        val service = RelayBootstrapService(
            socketFactory = ClosingRelaySocketFactory(code = 4002, reason = "Mac session not available"),
            now = { fixedNow },
        )

        val error = runCatching {
            service.fetchThreadList()
        }.exceptionOrNull()

        assertTrue(error is RelayBootstrapException)
        assertTrue(error?.message.orEmpty().contains("secure session is not active", ignoreCase = true))
        assertFalse(service.shouldRecoverLiveSession(error as RelayBootstrapException))
    }

    @Test
    fun reportsRelaySessionUnavailableClearly() = runBlocking {
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = RelaySecureCrypto.generatePhoneIdentity().publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = ClosingRelaySocketFactory(code = 4002, reason = "Mac session not available")
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        val error = runCatching {
            service.bootstrap(payload)
        }.exceptionOrNull()

        assertTrue(error is RelayBootstrapException)
        assertTrue(error?.message.orEmpty().contains("live Mac bridge session", ignoreCase = true))
    }

    @Test
    fun reconnectsToTrustedMacWithoutFreshQrBootstrap() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val persistedPhoneIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val resolvedPayload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "resolved-session-1",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val persistence = InMemoryTrustedReconnectPersistence(
            TrustedReconnectRecord(
                macDeviceId = "mac-device-1",
                macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
                relay = "wss://example.com/relay",
                phoneDeviceId = persistedPhoneIdentity.deviceId,
                phoneIdentityPrivateKey = RelaySecureCrypto.serializePhoneIdentityPrivateKey(persistedPhoneIdentity.privateKey),
                phoneIdentityPublicKey = persistedPhoneIdentity.publicKeyBase64,
                lastPairedAt = fixedNow.toEpochMilli(),
            ),
        )
        val factory = SuccessfulRelaySocketFactory(payload = resolvedPayload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            trustedReconnectPersistence = persistence,
            trustedSessionResolver = { trustedRecord ->
                assertEquals("mac-device-1", trustedRecord.macDeviceId)
                TrustedSessionResolveResponse(
                    macDeviceId = "mac-device-1",
                    macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
                    displayName = "Test Mac",
                    sessionId = "resolved-session-1",
                )
            },
            now = { fixedNow },
        )

        val reconnectResult = service.reconnectTrustedSession()

        assertEquals("wss://example.com/relay/resolved-session-1", factory.connectedUrl)
        assertEquals("resolved-session-1", reconnectResult.payload.sessionId)
        assertEquals("Test Mac", reconnectResult.trustedRecord.displayName)
        assertEquals("resolved-session-1", reconnectResult.trustedRecord.lastResolvedSessionId)
        assertEquals(fixedNow.toEpochMilli(), reconnectResult.trustedRecord.lastResolvedAt)
        assertEquals(persistedPhoneIdentity.deviceId, reconnectResult.verification.phoneDeviceId)
        assertEquals(TrustedReconnectPath.ResolvedLiveSession, reconnectResult.reconnectPath)
        assertEquals("resolved-session-1", persistence.read()?.lastResolvedSessionId)
        val liveThreads = service.fetchThreadList(limit = 1)
        assertEquals(1, liveThreads.size)
    }

    @Test
    fun ignoresLiteralNullDisplayNameFromSecureReadyDuringFirstBootstrap() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val persistence = InMemoryTrustedReconnectPersistence()
        val factory = SuccessfulRelaySocketFactory(
            payload = payload,
            bridgeIdentity = bridgeIdentity,
            secureReadyDisplayNameRaw = JSONObject.NULL,
        )
        val service = RelayBootstrapService(
            socketFactory = factory,
            trustedReconnectPersistence = persistence,
            now = { fixedNow },
        )

        service.bootstrap(payload)

        assertNull(service.readTrustedReconnectRecord()?.displayName)
        assertNull(persistence.read()?.displayName)
    }

    @Test
    fun fallsBackToSavedSessionWhenTrustedResolveCannotReachLiveSession() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val persistedPhoneIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val persistence = InMemoryTrustedReconnectPersistence(
            TrustedReconnectRecord(
                macDeviceId = "mac-device-1",
                macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
                relay = "wss://relay.example.com/relay",
                phoneDeviceId = persistedPhoneIdentity.deviceId,
                phoneIdentityPrivateKey = RelaySecureCrypto.serializePhoneIdentityPrivateKey(persistedPhoneIdentity.privateKey),
                phoneIdentityPublicKey = persistedPhoneIdentity.publicKeyBase64,
                lastPairedAt = fixedNow.toEpochMilli(),
                lastResolvedSessionId = "saved-session-77",
                lastResolvedAt = fixedNow.minusSeconds(30).toEpochMilli(),
            ),
        )
        val fallbackPayload = PairingQrPayload(
            version = 2,
            relay = "wss://relay.example.com/relay",
            sessionId = "saved-session-77",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(payload = fallbackPayload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            trustedReconnectPersistence = persistence,
            trustedSessionResolver = {
                throw RelayBootstrapException(
                    "Your trusted Mac is offline right now.",
                    RelayBootstrapFailureKind.SessionUnavailable,
                )
            },
            now = { fixedNow },
        )

        val reconnectResult = service.reconnectTrustedSession()

        assertEquals("wss://relay.example.com/relay/saved-session-77", factory.connectedUrl)
        assertEquals("saved-session-77", reconnectResult.payload.sessionId)
        assertEquals(TrustedReconnectPath.SavedSessionFallback, reconnectResult.reconnectPath)
        assertEquals("saved-session-77", reconnectResult.trustedRecord.lastResolvedSessionId)
    }

    @Test
    fun doesNotFallbackToSavedSessionWhenMacNoLongerTrustsThisAndroidDevice() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val persistedPhoneIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val persistence = InMemoryTrustedReconnectPersistence(
            TrustedReconnectRecord(
                macDeviceId = "mac-device-1",
                macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
                relay = "wss://relay.example.com/relay",
                phoneDeviceId = persistedPhoneIdentity.deviceId,
                phoneIdentityPrivateKey = RelaySecureCrypto.serializePhoneIdentityPrivateKey(persistedPhoneIdentity.privateKey),
                phoneIdentityPublicKey = persistedPhoneIdentity.publicKeyBase64,
                lastPairedAt = fixedNow.toEpochMilli(),
                lastResolvedSessionId = "saved-session-77",
                lastResolvedAt = fixedNow.minusSeconds(30).toEpochMilli(),
            ),
        )
        val service = RelayBootstrapService(
            trustedReconnectPersistence = persistence,
            trustedSessionResolver = {
                throw RelayBootstrapException(
                    "This Android device is no longer trusted by the Mac. Pair with a fresh QR code again.",
                    RelayBootstrapFailureKind.PairingExpired,
                )
            },
            now = { fixedNow },
        )

        val error = runCatching {
            service.reconnectTrustedSession()
        }.exceptionOrNull() as RelayBootstrapException

        assertEquals(RelayBootstrapFailureKind.PairingExpired, error.failureKind)
        assertTrue(error.message.orEmpty().contains("fresh QR code", ignoreCase = true))
    }

    @Test
    fun marksDroppedTrustedLiveSessionRecoverable() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val persistence = InMemoryTrustedReconnectPersistence()
        val factory = SuccessfulRelaySocketFactory(payload = payload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            trustedReconnectPersistence = persistence,
            now = { fixedNow },
        )

        service.bootstrap(payload)
        assertTrue(service.hasActiveSession())
        assertTrue(service.readTrustedReconnectRecord() != null)

        factory.dropLiveSession()

        assertFalse(service.hasActiveSession())
        val error = runCatching {
            service.fetchThreadDetail("live-thread-1")
        }.exceptionOrNull() as RelayBootstrapException

        assertTrue(error.isRecoverableSessionLoss)
        assertTrue(service.shouldRecoverLiveSession(error))
        assertTrue(error.message.orEmpty().contains("reconnect", ignoreCase = true))
    }

    @Test
    fun notifiesSessionLossListenerWhenLiveSessionDrops() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(payload = payload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )
        var notifiedError: RelayBootstrapException? = null

        service.setSessionLossListener { error ->
            notifiedError = error
        }
        service.bootstrap(payload)

        factory.dropLiveSession()

        assertEquals(RelayBootstrapFailureKind.SessionDisconnected, notifiedError?.failureKind)
        assertTrue(notifiedError?.isRecoverableSessionLoss == true)
        assertTrue(notifiedError?.message.orEmpty().contains("reconnect", ignoreCase = true))
    }

    @Test
    fun interruptsRunningTurnAndClearsActiveRunState() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(payload = payload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)
        val runningDetail = service.fetchThreadDetail("live-thread-1")
        assertEquals("turn-1", runningDetail.activeTurnId)

        val receipt = service.interruptTurn("live-thread-1")

        assertEquals("live-thread-1", receipt.threadId)
        assertEquals("turn-1", receipt.turnId)
        assertTrue(factory.didReceiveTurnInterruptRequest)
        assertEquals("turn-1", factory.lastTurnInterruptTurnId)

        val stoppedDetail = service.fetchThreadDetail("live-thread-1")
        assertFalse(stoppedDetail.isRunning)
        assertNull(stoppedDetail.activeTurnId)
        assertEquals(ThreadStatus.Waiting, stoppedDetail.status)
    }

    @Test
    fun continuesStoppedThreadOnSameLiveThread() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(payload = payload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)
        service.interruptTurn("live-thread-1")

        val receipt = service.continueThread(
            threadId = "live-thread-1",
            projectPath = "~/work/live-project",
        )

        assertEquals("live-thread-1", receipt.threadId)
        assertEquals("turn-2", receipt.turnId)
        assertTrue(factory.didReceiveTurnStartRequest)
        assertEquals("continue", factory.lastTurnStartUserInput)

        val continuedDetail = service.fetchThreadDetail("live-thread-1")
        assertTrue(continuedDetail.isRunning)
        assertEquals("turn-2", continuedDetail.activeTurnId)
        assertTrue(continuedDetail.entries.any { it.body.contains("continue") })
    }

    @Test
    fun treatsNestedStatusObjectsAsRunning() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(
            payload = payload,
            bridgeIdentity = bridgeIdentity,
            useObjectStatusPayloads = true,
        )
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)

        val liveThreads = service.fetchThreadList()
        val liveDetail = service.fetchThreadDetail("live-thread-1")

        assertEquals(ThreadStatus.Running, liveThreads.first().status)
        assertEquals(ThreadStatus.Running, liveDetail.status)
        assertEquals("turn-1", liveDetail.activeTurnId)
        assertTrue(liveDetail.isRunning)
    }

    @Test
    fun preservesRunningStateFromRealtimeNotificationsWhenThreadSnapshotsLag() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(
            payload = payload,
            bridgeIdentity = bridgeIdentity,
            initialLiveThreadStatus = "waiting",
            initialTurnStatus = "completed",
            emitRunningNotificationBeforeThreadResponses = true,
        )
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)

        val liveThreads = service.fetchThreadList()
        val liveDetail = service.fetchThreadDetail("live-thread-1")

        assertEquals(ThreadStatus.Running, liveThreads.first().status)
        assertEquals(ThreadStatus.Running, liveDetail.status)
        assertEquals("turn-live", liveDetail.activeTurnId)
        assertTrue(liveDetail.stateLabel.contains("active turn ready", ignoreCase = true))
    }

    @Test
    fun forwardsAssistantRuntimeEventsForPhoneStartedTurn() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(
            payload = payload,
            bridgeIdentity = bridgeIdentity,
            emitAssistantStreamDuringTurnStart = true,
        )
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )
        val runtimeEvents = mutableListOf<LiveThreadRuntimeEvent>()

        service.setRuntimeEventListener { event ->
            runtimeEvents += event
        }
        service.bootstrap(payload)

        service.sendTurnStart(
            threadId = "live-thread-1",
            userInput = "Please continue on the live thread.",
            projectPath = "~/work/live-project",
        )

        assertTrue(
            runtimeEvents.any { event ->
                event is LiveThreadRuntimeEvent.TurnStarted
                    && event.threadId == "live-thread-1"
                    && event.turnId == "turn-2"
            },
        )
        assertTrue(
            runtimeEvents.any { event ->
                event is LiveThreadRuntimeEvent.AssistantDelta
                    && event.threadId == "live-thread-1"
                    && event.turnId == "turn-2"
                    && event.delta.contains("streaming", ignoreCase = true)
            },
        )
        assertTrue(
            runtimeEvents.any { event ->
                event is LiveThreadRuntimeEvent.AssistantCompleted
                    && event.threadId == "live-thread-1"
                    && event.turnId == "turn-2"
                    && event.text.contains("live send request reached the bridge", ignoreCase = true)
            },
        )
    }

    @Test
    fun forwardsThreadListRefreshRuntimeEventsForThreadLifecycleNotifications() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(
            payload = payload,
            bridgeIdentity = bridgeIdentity,
            emitThreadLifecycleNotificationsBeforeThreadResponses = true,
        )
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )
        val runtimeEvents = mutableListOf<LiveThreadRuntimeEvent>()

        service.setRuntimeEventListener { event ->
            runtimeEvents += event
        }
        service.bootstrap(payload)

        service.fetchThreadList()

        assertTrue(
            runtimeEvents.any { event ->
                event is LiveThreadRuntimeEvent.ThreadListChanged
                    && event.threadIdHint == "live-thread-2"
            },
        )
    }

    @Test
    fun sanitizesNullThreadTitlesAndFallsBackToPreview() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(
            payload = payload,
            bridgeIdentity = bridgeIdentity,
            threadListThreadNameRaw = JSONObject.NULL,
            threadListThreadTitleRaw = JSONObject.NULL,
            threadListThreadPreview = "Fallback preview title",
        )
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)
        val liveThreads = service.fetchThreadList()

        assertEquals("Fallback preview title", liveThreads.first().title)
    }

    @Test
    fun sendsRuntimeSelectionsWithTurnStartRequest() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(payload = payload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)
        service.sendTurnStart(
            threadId = "live-thread-1",
            userInput = "Please continue on the live thread.",
            projectPath = "~/work/live-project",
            modelIdentifier = "gpt-5.4",
            reasoningEffort = "high",
            serviceTier = "fast",
            accessMode = AccessMode.FullAccess,
        )

        assertEquals("gpt-5.4", factory.lastTurnStartModel)
        assertEquals("high", factory.lastTurnStartEffort)
        assertEquals("fast", factory.lastTurnStartServiceTier)
        assertEquals("never", factory.lastTurnStartApprovalPolicy)
        assertEquals("dangerFullAccess", factory.lastTurnStartSandboxPolicyType)
    }

    @Test
    fun sendsImageAttachmentsWithTurnStartRequest() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(payload = payload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)
        service.sendTurnStart(
            threadId = "live-thread-1",
            userInput = "Can you see the screenshot?",
            attachments = listOf(
                dev.remodex.android.model.ImageAttachment(
                    id = "attachment-1",
                    uri = "content://images/1",
                    payloadDataUrl = "data:image/jpeg;base64,ZmFrZS1pbWFnZQ==",
                ),
            ),
            projectPath = "~/work/live-project",
        )

        assertEquals("Can you see the screenshot?", factory.lastTurnStartUserInput)
        assertEquals(listOf("data:image/jpeg;base64,ZmFrZS1pbWFnZQ=="), factory.lastTurnStartImagePayloads)
    }

    @Test
    fun allowsAttachmentOnlyTurnStartRequests() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(payload = payload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)
        service.sendTurnStart(
            threadId = "live-thread-1",
            userInput = "",
            attachments = listOf(
                dev.remodex.android.model.ImageAttachment(
                    id = "attachment-2",
                    uri = "content://images/2",
                    payloadDataUrl = "data:image/jpeg;base64,YXR0YWNobWVudC1vbmx5",
                ),
            ),
            projectPath = "~/work/live-project",
        )

        assertEquals("", factory.lastTurnStartUserInput)
        assertEquals(listOf("data:image/jpeg;base64,YXR0YWNobWVudC1vbmx5"), factory.lastTurnStartImagePayloads)
        assertTrue(factory.didReceiveTurnStartRequest)
    }

    @Test
    fun sendsRenameAndArchiveRequests() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(payload = payload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)
        service.renameThread(threadId = "live-thread-1", name = "Renamed on Android")
        service.archiveThread(threadId = "live-thread-1", unarchive = false)

        assertTrue(factory.didReceiveThreadNameSetRequest)
        assertEquals("live-thread-1", factory.lastThreadNameSetThreadId)
        assertEquals("Renamed on Android", factory.lastThreadNameSetName)
        assertTrue(factory.didReceiveThreadArchiveRequest)
        assertEquals("thread/archive", factory.lastThreadArchiveMethod)
        assertEquals("live-thread-1", factory.lastThreadArchiveThreadId)
    }

    @Test
    fun sendsPlanModeCollaborationPayloadWithTurnStartRequest() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(payload = payload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)
        service.sendTurnStart(
            threadId = "live-thread-1",
            userInput = "Draft a plan before coding.",
            projectPath = "~/work/live-project",
            modelIdentifier = "gpt-5.4",
            reasoningEffort = "high",
            collaborationMode = dev.remodex.android.model.CollaborationModeKind.Plan,
        )

        assertEquals("plan", factory.lastTurnStartCollaborationMode)
        assertEquals("gpt-5.4", factory.lastTurnStartCollaborationModel)
        assertEquals("high", factory.lastTurnStartCollaborationReasoning)
    }

    @Test
    fun decodesThreadMetadataFieldsFromListResponses() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(
            payload = payload,
            bridgeIdentity = bridgeIdentity,
            threadListModel = "gpt-5.4",
            threadListModelProvider = "openai",
            threadListParentThreadId = "parent-thread-1",
        )
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)
        val liveThread = service.fetchThreadList().first()

        assertEquals("gpt-5.4", liveThread.model)
        assertEquals("openai", liveThread.modelProvider)
        assertEquals("parent-thread-1", liveThread.parentThreadId)
    }

    @Test
    fun sendsArchivedFlagForArchivedThreadListRequests() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(payload = payload, bridgeIdentity = bridgeIdentity)
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)
        service.fetchThreadList(archived = true)

        assertTrue(factory.didReceiveArchivedThreadListRequest)
    }

    @Test
    fun decodesStructuredTimelineEntriesFromThreadHistory() = runBlocking {
        val bridgeIdentity = RelaySecureCrypto.generatePhoneIdentity()
        val payload = PairingQrPayload(
            version = 2,
            relay = "wss://example.com/relay",
            sessionId = "session-123",
            macDeviceId = "mac-device-1",
            macIdentityPublicKey = bridgeIdentity.publicKeyBase64,
            expiresAt = 1_900_000_000_000L,
        )
        val factory = SuccessfulRelaySocketFactory(
            payload = payload,
            bridgeIdentity = bridgeIdentity,
            includeStructuredTimelineItems = true,
        )
        val service = RelayBootstrapService(
            socketFactory = factory,
            now = { fixedNow },
        )

        service.bootstrap(payload)
        val detail = service.fetchThreadDetail("live-thread-1")

        assertTrue(detail.entries.any { it.kind == TimelineEntryKind.Thinking })
        assertTrue(detail.entries.any { it.kind == TimelineEntryKind.CommandExecution })
        assertTrue(detail.entries.any { it.kind == TimelineEntryKind.ToolActivity })
        val planEntry = detail.entries.firstOrNull { it.kind == TimelineEntryKind.Plan }
        assertTrue(planEntry != null)
        assertEquals(2, planEntry?.planSteps?.size)
        assertTrue(detail.entries.any { it.attachments.isNotEmpty() })
    }
}

private class SuccessfulRelaySocketFactory(
    private val payload: PairingQrPayload,
    private val bridgeIdentity: RuntimePhoneIdentity,
    private val secureReadyDisplayNameRaw: Any? = null,
    private val useObjectStatusPayloads: Boolean = false,
    private val initialLiveThreadStatus: String = "running",
    private val initialTurnStatus: String = "running",
    private val emitRunningNotificationBeforeThreadResponses: Boolean = false,
    private val emitAssistantStreamDuringTurnStart: Boolean = false,
    private val emitThreadLifecycleNotificationsBeforeThreadResponses: Boolean = false,
    private val includeStructuredTimelineItems: Boolean = false,
    private val threadListThreadNameRaw: Any? = "Live bridge thread",
    private val threadListThreadTitleRaw: Any? = "Live bridge thread",
    private val threadListThreadPreview: String = "Real thread list loaded from the bridge.",
    private val threadListModel: String? = null,
    private val threadListModelProvider: String? = null,
    private val threadListParentThreadId: String? = null,
) : RelaySocketFactory {
    private val fixedNow = Instant.parse("2026-03-26T20:00:00Z")
    var connectedUrl: String? = null
    var connectedHeaders: Map<String, String> = emptyMap()
    var didReceiveResumeState = false
    var didReceiveInitializeRequest = false
    var didReceiveInitializedNotification = false
    var didReceiveThreadListRequest = false
    var didReceiveThreadReadRequest = false
    var didReceiveThreadStartRequest = false
    var didReceiveThreadResumeRequest = false
    var didReceiveTurnStartRequest = false
    var didReceiveTurnInterruptRequest = false
    var didReceiveThreadNameSetRequest = false
    var didReceiveThreadArchiveRequest = false
    var didReceiveArchivedThreadListRequest = false
    var lastThreadResumeThreadId: String? = null
    var lastThreadStartProjectPath: String? = null
    var lastThreadStartModel: String? = null
    var lastTurnStartThreadId: String? = null
    var lastTurnStartUserInput: String? = null
    var lastTurnStartModel: String? = null
    var lastTurnStartEffort: String? = null
    var lastTurnStartServiceTier: String? = null
    var lastTurnStartApprovalPolicy: String? = null
    var lastTurnStartSandboxPolicyType: String? = null
    var lastTurnStartCollaborationMode: String? = null
    var lastTurnStartCollaborationModel: String? = null
    var lastTurnStartCollaborationReasoning: String? = null
    var lastTurnStartImagePayloads: List<String> = emptyList()
    var lastTurnInterruptTurnId: String? = null
    var lastThreadNameSetThreadId: String? = null
    var lastThreadNameSetName: String? = null
    var lastThreadArchiveThreadId: String? = null
    var lastThreadArchiveMethod: String? = null
    var closed = false
    private var nextBridgeOutboundSeq = 1
    private var didEmitRunningNotificationBeforeThreadResponses = false
    private var didEmitThreadLifecycleNotificationsBeforeThreadResponses = false
    private var relayListener: RelaySocketListener? = null
    private var bridgeSession: ActiveSecureRelaySession? = null
    private var liveThreadPreview = threadListThreadPreview
    private var liveThreadStatus = initialLiveThreadStatus
    private val liveThreadTurns = mutableListOf(
        JSONObject()
            .put("id", "turn-1")
            .put("status", encodeStatusPayload(initialTurnStatus))
            .put("createdAt", fixedNow.minusSeconds(90).toEpochMilli())
            .put(
                "items",
                org.json.JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "userMessage")
                            .put("id", "item-user-1")
                            .put(
                                "content",
                                org.json.JSONArray().put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put("text", "Check whether the Android phone can open a real thread."),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("type", "agentMessage")
                            .put("id", "item-agent-1")
                            .put("text", "The live thread list is already loaded from the bridge."),
                    )
                    .put(
                        JSONObject()
                            .put("type", "agentMessage")
                            .put("id", "item-agent-2")
                            .put("text", "Loaded the real thread detail over thread/read."),
                    ),
            ),
    )

    init {
        if (includeStructuredTimelineItems) {
            liveThreadTurns.firstOrNull()?.optJSONArray("items")?.apply {
                put(
                    JSONObject()
                        .put("type", "userMessage")
                        .put("id", "item-user-image-1")
                        .put(
                            "content",
                            org.json.JSONArray()
                                .put(JSONObject().put("type", "text").put("text", "Reference screenshot attached."))
                                .put(JSONObject().put("type", "image").put("imageUrl", "https://example.com/screenshot.png")),
                        ),
                )
                put(
                    JSONObject()
                        .put("type", "reasoning")
                        .put("id", "item-thinking-1")
                        .put("text", "Comparing the Android reducer against the iOS timeline model."),
                )
                put(
                    JSONObject()
                        .put("type", "toolCall")
                        .put("id", "item-tool-1")
                        .put("toolName", "rg")
                        .put("summary", "Searched the repository for timeline reducers."),
                )
                put(
                    JSONObject()
                        .put("type", "commandExecution")
                        .put("id", "item-command-1")
                        .put("command", "rg -n \"TimelineEntry\" app/src/main/java")
                        .put("stdout", "app/src/main/java/.../ConversationScreen.kt"),
                )
                put(
                    JSONObject()
                        .put("type", "plan")
                        .put("id", "item-plan-1")
                        .put("explanation", "Bring Android timeline rendering closer to iOS.")
                        .put(
                            "plan",
                            org.json.JSONArray()
                                .put(JSONObject().put("step", "Decode structured item kinds").put("status", "completed"))
                                .put(JSONObject().put("step", "Render dedicated cards").put("status", "in_progress")),
                        ),
                )
            }
        }
    }

    override fun connect(
        url: String,
        headers: Map<String, String>,
        listener: RelaySocketListener,
    ): RelaySocketConnection {
        connectedUrl = url
        connectedHeaders = headers
        relayListener = listener
        listener.onOpen()

        return object : RelaySocketConnection {
            override fun send(text: String): Boolean {
                val json = JSONObject(text)
                when (json.getString("kind")) {
                    "clientHello" -> listener.onMessage(buildServerHello(json).toString())
                    "clientAuth" -> listener.onMessage(
                        JSONObject()
                            .put("kind", "secureReady")
                            .put("sessionId", payload.sessionId)
                            .put("keyEpoch", 1)
                            .put("macDeviceId", payload.macDeviceId)
                            .put("displayName", secureReadyDisplayNameRaw ?: JSONObject.NULL)
                            .toString(),
                    )

                    "resumeState" -> didReceiveResumeState = true
                    "encryptedEnvelope" -> handleEncryptedEnvelope(json, listener)
                }
                return true
            }

            override fun close(code: Int, reason: String?) {
                closed = true
            }
        }
    }

    fun dropLiveSession(code: Int = 4004, reason: String = "Mac bridge dropped") {
        relayListener?.onClosed(code, reason)
    }

    private fun buildServerHello(clientHello: JSONObject): JSONObject {
        val bridgeEphemeral = RelaySecureCrypto.generateX25519KeyPair()
        val serverNonce = RelaySecureCrypto.randomBytes(32)
        val handshakeMode = clientHello.getString("handshakeMode")
        val expiresAtForTranscript = if (handshakeMode == "trusted_reconnect") 0L else payload.expiresAt
        val transcriptBytes = RelaySecureCrypto.buildTranscriptBytes(
            sessionId = payload.sessionId,
            protocolVersion = 1,
            handshakeMode = handshakeMode,
            keyEpoch = 1,
            macDeviceId = payload.macDeviceId,
            phoneDeviceId = clientHello.getString("phoneDeviceId"),
            macIdentityPublicKey = payload.macIdentityPublicKey,
            phoneIdentityPublicKey = clientHello.getString("phoneIdentityPublicKey"),
            macEphemeralPublicKey = bridgeEphemeral.publicKeyBase64,
            phoneEphemeralPublicKey = clientHello.getString("phoneEphemeralPublicKey"),
            clientNonce = Base64.getDecoder().decode(clientHello.getString("clientNonce")),
            serverNonce = serverNonce,
            expiresAtForTranscript = expiresAtForTranscript,
        )
        val macSignature = RelaySecureCrypto.signTranscript(
            privateKey = bridgeIdentity.privateKey,
            transcriptBytes = transcriptBytes,
        )
        val sessionKeys = RelaySecureCrypto.deriveSessionKeysFromMacPerspective(
            phoneEphemeralPublicKey = clientHello.getString("phoneEphemeralPublicKey"),
            macEphemeralPrivateKey = bridgeEphemeral.privateKey,
            transcriptBytes = transcriptBytes,
            sessionId = payload.sessionId,
            macDeviceId = payload.macDeviceId,
            phoneDeviceId = clientHello.getString("phoneDeviceId"),
            keyEpoch = 1,
        )

        return JSONObject()
            .put("kind", "serverHello")
            .put("protocolVersion", 1)
            .put("sessionId", payload.sessionId)
            .put("handshakeMode", handshakeMode)
            .put("macDeviceId", payload.macDeviceId)
            .put("macIdentityPublicKey", payload.macIdentityPublicKey)
            .put("macEphemeralPublicKey", bridgeEphemeral.publicKeyBase64)
            .put("serverNonce", Base64.getEncoder().encodeToString(serverNonce))
            .put("keyEpoch", 1)
            .put("expiresAtForTranscript", expiresAtForTranscript)
            .put("macSignature", macSignature)
            .put("clientNonce", clientHello.getString("clientNonce"))
            .also {
                bridgeSession = ActiveSecureRelaySession(
                    connectionToken = "test-connection-token",
                    socket = object : RelaySocketConnection {
                        override fun send(text: String): Boolean = true
                        override fun close(code: Int, reason: String?) = Unit
                    },
                    events = Channel(Channel.UNLIMITED),
                    sessionId = payload.sessionId,
                    keyEpoch = 1,
                    macDeviceId = payload.macDeviceId,
                    macIdentityPublicKey = payload.macIdentityPublicKey,
                    phoneDeviceId = clientHello.getString("phoneDeviceId"),
                    phoneIdentityPublicKey = clientHello.getString("phoneIdentityPublicKey"),
                    phoneToMacKey = sessionKeys.first,
                    macToPhoneKey = sessionKeys.second,
                    nextOutboundCounter = 0,
                    lastInboundCounter = -1,
                )
            }
    }

    private fun handleEncryptedEnvelope(
        envelope: JSONObject,
        listener: RelaySocketListener,
    ) {
        val session = bridgeSession ?: error("Expected bridge session")
        val payloadText = decryptPhoneEnvelope(envelope, session) ?: error("Expected phone payload")
        val payloadJson = JSONObject(payloadText)
        val method = payloadJson.optString("method")
        when (method) {
            "initialize" -> {
                didReceiveInitializeRequest = true
                val response = JSONObject()
                    .put("id", payloadJson.getString("id"))
                    .put(
                        "result",
                        JSONObject()
                            .put("bridgeManaged", true),
                    )
                sendEncryptedPayload(listener, session, response)
            }

            "initialized" -> {
                didReceiveInitializedNotification = true
            }

            "thread/list" -> {
                didReceiveThreadListRequest = true
                val isArchivedRequest = payloadJson.optJSONObject("params")?.optBoolean("archived") == true
                if (isArchivedRequest) {
                    didReceiveArchivedThreadListRequest = true
                }
                maybeEmitRunningNotificationBeforeThreadResponses(listener, session)
                maybeEmitThreadLifecycleNotificationsBeforeThreadResponses(listener, session)
                val threadObject = JSONObject()
                    .put("id", if (isArchivedRequest) "archived-thread-1" else "live-thread-1")
                    .put("preview", liveThreadPreview)
                    .put("cwd", if (isArchivedRequest) "~/work/archived-project" else "~/work/live-project")
                    .put("updatedAt", fixedNow.toEpochMilli())
                    .put("status", encodeStatusPayload(liveThreadStatus))
                if (threadListThreadNameRaw != null) {
                    threadObject.put("name", threadListThreadNameRaw)
                }
                if (threadListThreadTitleRaw != null) {
                    threadObject.put("title", threadListThreadTitleRaw)
                }
                if (threadListModel != null) {
                    threadObject.put("model", threadListModel)
                }
                if (threadListModelProvider != null) {
                    threadObject.put("modelProvider", threadListModelProvider)
                }
                if (threadListParentThreadId != null) {
                    threadObject.put("parentThreadId", threadListParentThreadId)
                }
                val response = JSONObject()
                    .put("id", payloadJson.getString("id"))
                    .put(
                        "result",
                        JSONObject()
                            .put(
                                "data",
                                org.json.JSONArray().put(threadObject),
                            )
                            .put("nextCursor", JSONObject.NULL),
                    )
                sendEncryptedPayload(listener, session, response)
            }

            "thread/start" -> {
                didReceiveThreadStartRequest = true
                val params = payloadJson.optJSONObject("params") ?: JSONObject()
                lastThreadStartProjectPath = params.optString("cwd").trim().ifEmpty { null }
                lastThreadStartModel = params.optString("model").trim().ifEmpty { null }
                val response = JSONObject()
                    .put("id", payloadJson.getString("id"))
                    .put(
                        "result",
                        JSONObject()
                            .put(
                                "thread",
                                JSONObject()
                                    .put("id", "live-thread-2")
                                    .put("title", "Started from Android")
                                    .put("cwd", params.optString("cwd").ifBlank { "~/work/live-project" })
                                    .put("updatedAt", fixedNow.toEpochMilli())
                                    .put("status", encodeStatusPayload("waiting")),
                            ),
                    )
                sendEncryptedPayload(listener, session, response)
            }

            "thread/read" -> {
                didReceiveThreadReadRequest = true
                maybeEmitRunningNotificationBeforeThreadResponses(listener, session)
                maybeEmitThreadLifecycleNotificationsBeforeThreadResponses(listener, session)
                val response = JSONObject()
                    .put("id", payloadJson.getString("id"))
                    .put(
                        "result",
                        JSONObject()
                            .put(
                                "thread",
                                JSONObject()
                                    .put("id", "live-thread-1")
                                    .put("source", "cli")
                                    .put("cwd", "~/work/live-project")
                                    .put("status", encodeStatusPayload(liveThreadStatus))
                                    .put("updatedAt", fixedNow.toEpochMilli())
                                    .put(
                                        "turns",
                                        org.json.JSONArray().apply {
                                            liveThreadTurns.forEach { put(it) }
                                        },
                                    ),
                            ),
                    )
                sendEncryptedPayload(listener, session, response)
            }

            "thread/resume" -> {
                didReceiveThreadResumeRequest = true
                val params = payloadJson.optJSONObject("params") ?: JSONObject()
                lastThreadResumeThreadId = params.optString("threadId")
                    .ifBlank { params.optString("thread_id") }
                    .trim()
                val response = JSONObject()
                    .put("id", payloadJson.getString("id"))
                    .put(
                        "result",
                        JSONObject()
                            .put(
                                "thread",
                                JSONObject()
                                    .put("id", "live-thread-1")
                                    .put("source", "cli")
                                    .put("cwd", "~/work/live-project")
                                    .put("status", encodeStatusPayload(liveThreadStatus))
                                    .put("updatedAt", fixedNow.toEpochMilli()),
                            ),
                    )
                sendEncryptedPayload(listener, session, response)
            }

            "thread/name/set" -> {
                didReceiveThreadNameSetRequest = true
                val params = payloadJson.optJSONObject("params") ?: JSONObject()
                lastThreadNameSetThreadId = params.optString("thread_id")
                    .ifBlank { params.optString("threadId") }
                    .trim()
                lastThreadNameSetName = params.optString("name").trim().ifEmpty { null }
                val response = JSONObject()
                    .put("id", payloadJson.getString("id"))
                    .put("result", JSONObject())
                sendEncryptedPayload(listener, session, response)
            }

            "thread/archive",
            "thread/unarchive" -> {
                didReceiveThreadArchiveRequest = true
                lastThreadArchiveMethod = method
                val params = payloadJson.optJSONObject("params") ?: JSONObject()
                lastThreadArchiveThreadId = params.optString("threadId")
                    .ifBlank { params.optString("thread_id") }
                    .trim()
                val response = JSONObject()
                    .put("id", payloadJson.getString("id"))
                    .put("result", JSONObject())
                sendEncryptedPayload(listener, session, response)
            }

            "turn/start" -> {
                didReceiveTurnStartRequest = true
                val params = payloadJson.optJSONObject("params") ?: JSONObject()
                val threadId = params.optString("threadId")
                    .ifBlank { params.optString("thread_id") }
                    .trim()
                lastTurnStartThreadId = threadId
                val inputItems = params.optJSONArray("input")
                var userInput = ""
                val imagePayloads = mutableListOf<String>()
                for (index in 0 until (inputItems?.length() ?: 0)) {
                    val itemObject = inputItems?.optJSONObject(index) ?: continue
                    when (itemObject.optString("type")) {
                        "text" -> {
                            val candidate = itemObject.optString("text").trim()
                            if (candidate.isNotEmpty()) {
                                userInput = candidate
                            }
                        }

                        "image" -> {
                            val imagePayload = itemObject.optString("url")
                                .ifBlank { itemObject.optString("image_url") }
                                .trim()
                            if (imagePayload.isNotEmpty()) {
                                imagePayloads += imagePayload
                            }
                        }
                    }
                }
                lastTurnStartUserInput = userInput
                lastTurnStartImagePayloads = imagePayloads
                lastTurnStartModel = params.optString("model").trim().ifEmpty { null }
                lastTurnStartEffort = params.optString("effort").trim().ifEmpty { null }
                lastTurnStartServiceTier = params.optString("serviceTier").trim().ifEmpty { null }
                lastTurnStartApprovalPolicy = params.optString("approvalPolicy").trim().ifEmpty { null }
                lastTurnStartSandboxPolicyType = params.optJSONObject("sandboxPolicy")
                    ?.optString("type")
                    ?.trim()
                    ?.ifEmpty { null }
                val collaborationMode = params.optJSONObject("collaborationMode")
                lastTurnStartCollaborationMode = collaborationMode
                    ?.optString("mode")
                    ?.trim()
                    ?.ifEmpty { null }
                val collaborationSettings = collaborationMode?.optJSONObject("settings")
                lastTurnStartCollaborationModel = collaborationSettings
                    ?.optString("model")
                    ?.trim()
                    ?.ifEmpty { null }
                lastTurnStartCollaborationReasoning = collaborationSettings
                    ?.optString("reasoning_effort")
                    ?.trim()
                    ?.ifEmpty { null }
                markLatestInterruptibleTurnCompleted()
                liveThreadPreview = userInput
                liveThreadStatus = "running"
                val nextTurnId = "turn-${liveThreadTurns.size + 1}"
                liveThreadTurns += JSONObject()
                    .put("id", nextTurnId)
                    .put("status", encodeStatusPayload("running"))
                    .put("createdAt", fixedNow.toEpochMilli())
                    .put(
                        "items",
                        org.json.JSONArray()
                            .put(
                                JSONObject()
                                    .put("type", "userMessage")
                                    .put("id", "item-user-2")
                                    .put(
                                        "content",
                                        org.json.JSONArray().put(
                                            JSONObject()
                                                .put("type", "text")
                                                .put("text", userInput),
                                        ),
                                    ),
                            )
                            .put(
                                JSONObject()
                                    .put("type", "agentMessage")
                                    .put("id", "item-agent-3")
                                    .put(
                                        "text",
                                        if (userInput == "continue") {
                                            "The live continue request reached the bridge."
                                        } else {
                                            "The live send request reached the bridge."
                                        },
                                    ),
                            ),
                    )
                if (emitAssistantStreamDuringTurnStart) {
                    sendEncryptedPayload(
                        listener = listener,
                        session = session,
                        payload = JSONObject()
                            .put("method", "turn/started")
                            .put(
                                "params",
                                JSONObject()
                                    .put("threadId", threadId)
                                    .put("turnId", nextTurnId),
                            ),
                    )
                    sendEncryptedPayload(
                        listener = listener,
                        session = session,
                        payload = JSONObject()
                            .put("method", "item/agentMessage/delta")
                            .put(
                                "params",
                                JSONObject()
                                    .put("threadId", threadId)
                                    .put("turnId", nextTurnId)
                                    .put("itemId", "item-agent-stream-$nextTurnId")
                                    .put("delta", "Streaming reply from the bridge. "),
                            ),
                    )
                    sendEncryptedPayload(
                        listener = listener,
                        session = session,
                        payload = JSONObject()
                            .put("method", "item/completed")
                            .put(
                                "params",
                                JSONObject()
                                    .put("threadId", threadId)
                                    .put("turnId", nextTurnId)
                                    .put(
                                        "item",
                                        JSONObject()
                                            .put("id", "item-agent-stream-$nextTurnId")
                                            .put("type", "agentMessage")
                                            .put("text", liveThreadTurns.last().optJSONArray("items")
                                                ?.optJSONObject(1)
                                                ?.optString("text")
                                                .orEmpty()),
                                    ),
                            ),
                    )
                }
                val response = JSONObject()
                    .put("id", payloadJson.getString("id"))
                    .put(
                        "result",
                        JSONObject()
                            .put(
                                "turn",
                                JSONObject()
                                    .put("id", nextTurnId),
                            ),
                    )
                sendEncryptedPayload(listener, session, response)
            }

            "turn/interrupt" -> {
                didReceiveTurnInterruptRequest = true
                val params = payloadJson.optJSONObject("params") ?: JSONObject()
                val turnId = params.optString("turnId")
                    .ifBlank { params.optString("turn_id") }
                    .trim()
                lastTurnInterruptTurnId = turnId
                markTurnStopped(turnId)
                liveThreadStatus = "waiting"
                val response = JSONObject()
                    .put("id", payloadJson.getString("id"))
                    .put("result", JSONObject())
                sendEncryptedPayload(listener, session, response)
            }
        }
    }

    private fun maybeEmitRunningNotificationBeforeThreadResponses(
        listener: RelaySocketListener,
        session: ActiveSecureRelaySession,
    ) {
        if (!emitRunningNotificationBeforeThreadResponses || didEmitRunningNotificationBeforeThreadResponses) {
            return
        }
        didEmitRunningNotificationBeforeThreadResponses = true

        sendEncryptedPayload(
            listener = listener,
            session = session,
            payload = JSONObject()
                .put("method", "turn/started")
                .put(
                    "params",
                    JSONObject()
                        .put("threadId", "live-thread-1")
                        .put("turnId", "turn-live"),
                ),
        )
        sendEncryptedPayload(
            listener = listener,
            session = session,
            payload = JSONObject()
                .put("method", "thread/status/changed")
                .put(
                    "params",
                    JSONObject()
                        .put("threadId", "live-thread-1")
                        .put("status", encodeStatusPayload("running")),
                ),
        )
    }

    private fun maybeEmitThreadLifecycleNotificationsBeforeThreadResponses(
        listener: RelaySocketListener,
        session: ActiveSecureRelaySession,
    ) {
        if (
            !emitThreadLifecycleNotificationsBeforeThreadResponses
            || didEmitThreadLifecycleNotificationsBeforeThreadResponses
        ) {
            return
        }
        didEmitThreadLifecycleNotificationsBeforeThreadResponses = true

        sendEncryptedPayload(
            listener = listener,
            session = session,
            payload = JSONObject()
                .put("method", "thread/started")
                .put(
                    "params",
                    JSONObject()
                        .put(
                            "thread",
                            JSONObject()
                                .put("id", "live-thread-2")
                                .put("title", "Started from iOS")
                                .put("cwd", "~/work/ios-project"),
                        ),
                ),
        )
        sendEncryptedPayload(
            listener = listener,
            session = session,
            payload = JSONObject()
                .put("method", "thread/name/updated")
                .put(
                    "params",
                    JSONObject()
                        .put("threadId", "live-thread-2")
                        .put("name", "Renamed on iOS"),
                ),
        )
    }

    private fun sendEncryptedPayload(
        listener: RelaySocketListener,
        session: ActiveSecureRelaySession,
        payload: JSONObject,
    ) {
        val encryptedPayload = RelaySecureCrypto.encryptEnvelopePayload(
            payloadText = payload.toString(),
            session = session,
            sender = "mac",
            bridgeOutboundSeq = nextBridgeOutboundSeq++,
        )
        listener.onMessage(encryptedPayload.toString())
    }

    private fun markLatestInterruptibleTurnCompleted() {
        for (index in liveThreadTurns.size - 1 downTo 0) {
            val turnObject = liveThreadTurns[index]
            val status = statusString(turnObject).lowercase()
            if (status.contains("running") || status.contains("started") || status.contains("pending")) {
                turnObject.put("status", encodeStatusPayload("completed"))
                return
            }
        }
    }

    private fun markTurnStopped(turnId: String) {
        for (index in liveThreadTurns.size - 1 downTo 0) {
            val turnObject = liveThreadTurns[index]
            if (turnObject.optString("id") == turnId) {
                turnObject.put("status", encodeStatusPayload("stopped"))
                return
            }
        }
    }

    private fun encodeStatusPayload(status: String): Any {
        return if (useObjectStatusPayloads) {
            JSONObject().put("type", status)
        } else {
            status
        }
    }

    private fun statusString(turnObject: JSONObject): String {
        val rawStatus = turnObject.opt("status")
        return when (rawStatus) {
            is JSONObject -> rawStatus.optString("type")
            is String -> rawStatus
            else -> ""
        }
    }

    private fun decryptPhoneEnvelope(
        envelope: JSONObject,
        session: ActiveSecureRelaySession,
    ): String? {
        val sessionId = envelope.optString("sessionId")
        val keyEpoch = envelope.optInt("keyEpoch")
        val sender = envelope.optString("sender")
        val counter = envelope.optInt("counter")
        if (
            sessionId != session.sessionId
            || keyEpoch != session.keyEpoch
            || sender != "iphone"
        ) {
            return null
        }

        val ciphertext = Base64.getDecoder().decode(envelope.optString("ciphertext"))
        val tag = Base64.getDecoder().decode(envelope.optString("tag"))
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(session.phoneToMacKey, "AES"),
            javax.crypto.spec.GCMParameterSpec(
                128,
                buildNonce(sender = "iphone", counter = counter),
            ),
        )
        val plaintext = cipher.doFinal(ciphertext + tag)
        return JSONObject(String(plaintext)).optString("payloadText").ifBlank { null }
    }

    private fun buildNonce(sender: String, counter: Int): ByteArray {
        val nonce = ByteArray(12)
        nonce[0] = if (sender == "mac") 1 else 2
        var value = counter.toLong()
        for (index in 11 downTo 1) {
            nonce[index] = (value and 0xff).toByte()
            value = value shr 8
        }
        return nonce
    }
}

private class ClosingRelaySocketFactory(
    private val code: Int,
    private val reason: String,
) : RelaySocketFactory {
    override fun connect(
        url: String,
        headers: Map<String, String>,
        listener: RelaySocketListener,
    ): RelaySocketConnection {
        listener.onOpen()
        return object : RelaySocketConnection {
            override fun send(text: String): Boolean {
                listener.onClosed(code, reason)
                return true
            }

            override fun close(code: Int, reason: String?) = Unit
        }
    }
}

private class InMemoryTrustedReconnectPersistence(
    initialRecord: TrustedReconnectRecord? = null,
) : TrustedReconnectPersistence {
    private var record: TrustedReconnectRecord? = initialRecord

    override fun read(): TrustedReconnectRecord? = record

    override fun write(record: TrustedReconnectRecord) {
        this.record = record
    }

    override fun clear() {
        record = null
    }
}
