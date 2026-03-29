package dev.remodex.android.feature.pairing

import android.util.Log
import androidx.compose.runtime.Immutable
import dev.remodex.android.feature.threads.generateThumbnailFromDataUri
import dev.remodex.android.model.AccessMode
import dev.remodex.android.model.CollaborationModeKind
import dev.remodex.android.model.ThreadDetail
import dev.remodex.android.BuildConfig
import dev.remodex.android.model.ImageAttachment
import dev.remodex.android.model.ThreadStatus
import dev.remodex.android.model.ThreadSummary
import dev.remodex.android.model.TimelineEntry
import dev.remodex.android.model.TimelineEntryKind
import dev.remodex.android.model.TimelinePlanStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val relayWaitTimeoutMs = 12_000L
private const val secureProtocolVersion = 1
private const val secureHandshakeTag = "remodex-e2ee-v1"
private const val secureHandshakeLabel = "client-auth"
private const val secureHandshakeModeQrBootstrap = "qr_bootstrap"
private const val secureHandshakeModeTrustedReconnect = "trusted_reconnect"
private const val trustedSessionResolveTag = "remodex-trusted-session-resolve-v1"
private const val bootstrapLogTag = "RemodexBootstrap"
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
private val bootstrapVerifiedFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val timelineTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val threadDateFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

enum class RelayBootstrapPhase {
    Idle,
    Connecting,
    Handshaking,
    Verified,
}

@Immutable
data class RelayBootstrapVerification(
    val sessionId: String,
    val sessionLabel: String,
    val relayLabel: String,
    val macDeviceId: String,
    val macFingerprint: String,
    val phoneDeviceId: String,
    val phoneFingerprint: String,
    val keyEpoch: Int,
    val initializeLabel: String,
    val verifiedAt: Instant,
)

data class TrustedReconnectBootstrapResult(
    val payload: PairingQrPayload,
    val verification: RelayBootstrapVerification,
    val trustedRecord: TrustedReconnectRecord,
    val reconnectPath: TrustedReconnectPath,
)

data class LiveTurnStartReceipt(
    val threadId: String,
    val turnId: String?,
    val userInput: String,
    val sentAt: Instant,
)

@Immutable
data class LiveThreadRunState(
    val threadId: String,
    val status: ThreadStatus,
    val activeTurnId: String?,
    val latestTurnId: String?,
    val hasInterruptibleTurnWithoutId: Boolean,
) {
    val isRunning: Boolean
        get() = status == ThreadStatus.Running || activeTurnId != null || hasInterruptibleTurnWithoutId
}

data class LiveTurnInterruptReceipt(
    val threadId: String,
    val turnId: String,
    val interruptedAt: Instant,
)

data class RelayReasoningEffortOption(
    val reasoningEffort: String,
    val description: String,
)

data class RelayModelOption(
    val id: String,
    val model: String,
    val displayName: String,
    val description: String,
    val isDefault: Boolean,
    val supportedReasoningEfforts: List<RelayReasoningEffortOption>,
    val defaultReasoningEffort: String?,
)

sealed interface LiveThreadRuntimeEvent {
    data class ThreadListChanged(
        val threadIdHint: String?,
    ) : LiveThreadRuntimeEvent

    data class ThreadStatusChanged(
        val threadId: String,
        val status: ThreadStatus,
    ) : LiveThreadRuntimeEvent

    data class TurnStarted(
        val threadId: String,
        val turnId: String?,
    ) : LiveThreadRuntimeEvent

    data class TurnCompleted(
        val threadId: String,
        val turnId: String?,
        val status: ThreadStatus,
    ) : LiveThreadRuntimeEvent

    data class AssistantDelta(
        val threadId: String,
        val turnId: String,
        val itemId: String?,
        val delta: String,
    ) : LiveThreadRuntimeEvent

    data class AssistantCompleted(
        val threadId: String,
        val turnId: String?,
        val itemId: String?,
        val text: String,
    ) : LiveThreadRuntimeEvent

    data class StructuredEntryUpdated(
        val threadId: String,
        val turnId: String?,
        val entry: TimelineEntry,
    ) : LiveThreadRuntimeEvent

    data class UserMessageEcho(
        val threadId: String,
        val turnId: String?,
        val text: String,
    ) : LiveThreadRuntimeEvent

    data class ErrorNotification(
        val threadId: String?,
        val turnId: String?,
        val message: String,
    ) : LiveThreadRuntimeEvent
}

enum class RelayBootstrapFailureKind {
    Generic,
    SessionMissing,
    SessionDisconnected,
    SessionUnavailable,
    PairingExpired,
}

enum class TrustedReconnectPath {
    ResolvedLiveSession,
    SavedSessionFallback,
}

class RelayBootstrapException(
    message: String,
    val failureKind: RelayBootstrapFailureKind = RelayBootstrapFailureKind.Generic,
) : Exception(message) {
    val isRecoverableSessionLoss: Boolean
        get() = failureKind == RelayBootstrapFailureKind.SessionMissing
            || failureKind == RelayBootstrapFailureKind.SessionDisconnected
            || failureKind == RelayBootstrapFailureKind.SessionUnavailable
}

private data class SessionDisconnectSnapshot(
    val message: String,
    val failureKind: RelayBootstrapFailureKind,
)

class RelayBootstrapService(
    private val socketFactory: RelaySocketFactory = OkHttpRelaySocketFactory(),
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val trustedReconnectPersistence: TrustedReconnectPersistence? = null,
    private val now: () -> Instant = { Instant.now() },
    private val trustedSessionResolver: (suspend (TrustedReconnectRecord) -> TrustedSessionResolveResponse)? = null,
) {
    private var activeSession: ActiveSecureRelaySession? = null
    private var lastSessionDisconnect: SessionDisconnectSnapshot? = null
    private var sessionLossListener: ((RelayBootstrapException) -> Unit)? = null
    private var runtimeEventListener: ((LiveThreadRuntimeEvent) -> Unit)? = null
    private val liveThreadRuntimeStates = mutableMapOf<String, LiveThreadRunState>()
    private val threadIdByTurnId = mutableMapOf<String, String>()
    private val resumedThreadIds = mutableSetOf<String>()

    suspend fun bootstrap(
        payload: PairingQrPayload,
        onPhaseChanged: (RelayBootstrapPhase) -> Unit = {},
    ): RelayBootstrapVerification {
        return bootstrapInternal(
            payload = payload,
            handshakeMode = secureHandshakeModeQrBootstrap,
            phoneIdentity = null,
            onPhaseChanged = onPhaseChanged,
        )
    }

    suspend fun reconnectTrustedSession(
        trustedRecord: TrustedReconnectRecord? = null,
        onPhaseChanged: (RelayBootstrapPhase) -> Unit = {},
    ): TrustedReconnectBootstrapResult {
        val trustedRecord = trustedRecord ?: trustedReconnectPersistence?.read()
            ?: throw RelayBootstrapException(
                "No trusted Mac is saved on this Android device yet. Pair once with a QR code first.",
            )
        val target = resolveTrustedReconnectTarget(trustedRecord)
        val verification = bootstrapInternal(
            payload = target.payload,
            handshakeMode = secureHandshakeModeTrustedReconnect,
            phoneIdentity = trustedRecord.toRuntimePhoneIdentity(),
            onPhaseChanged = onPhaseChanged,
        )
        trustedReconnectPersistence?.write(target.trustedRecord)
        return TrustedReconnectBootstrapResult(
            payload = target.payload,
            verification = verification,
            trustedRecord = target.trustedRecord,
            reconnectPath = target.reconnectPath,
        )
    }

    fun readTrustedReconnectRecord(): TrustedReconnectRecord? = trustedReconnectPersistence?.read()

    fun clearTrustedReconnectRecord() {
        trustedReconnectPersistence?.clear()
    }

    fun hasActiveSession(): Boolean = activeSession?.isOpen == true

    fun setSessionLossListener(listener: ((RelayBootstrapException) -> Unit)?) {
        sessionLossListener = listener
    }

    fun setRuntimeEventListener(listener: ((LiveThreadRuntimeEvent) -> Unit)?) {
        runtimeEventListener = listener
    }

    fun shouldRecoverLiveSession(error: RelayBootstrapException): Boolean {
        if (!error.isRecoverableSessionLoss) {
            return false
        }
        return trustedReconnectPersistence?.read() != null
    }

    private suspend fun bootstrapInternal(
        payload: PairingQrPayload,
        handshakeMode: String,
        phoneIdentity: RuntimePhoneIdentity?,
        onPhaseChanged: (RelayBootstrapPhase) -> Unit = {},
    ): RelayBootstrapVerification {
        disconnectActiveSession()
        lastSessionDisconnect = null
        logInfo(
            bootstrapLogTag,
            "bootstrap start relay=${payload.relayDisplayLabel()} session=${payload.maskedSessionLabel()} mode=$handshakeMode",
        )
        var socket: RelaySocketConnection? = null
        var events: Channel<RelaySocketEvent>? = null

        try {
            val connectionToken = UUID.randomUUID().toString()
            val relayUrl = relaySocketUrl(payload)
            logInfo(bootstrapLogTag, "crypto phone identity start")
            val resolvedPhoneIdentity = runCatching {
                phoneIdentity ?: RelaySecureCrypto.generatePhoneIdentity()
            }.getOrElse { error ->
                throw RelayBootstrapException(
                    "Android crypto setup failed while generating the phone identity: ${error.message ?: error::class.java.simpleName}.",
                )
            }
            logInfo(bootstrapLogTag, "crypto phone identity ready")
            logInfo(bootstrapLogTag, "crypto ephemeral start")
            val phoneEphemeral = runCatching {
                RelaySecureCrypto.generateX25519KeyPair()
            }.getOrElse { error ->
                throw RelayBootstrapException(
                    "Android crypto setup failed while generating the X25519 relay key: ${error.message ?: error::class.java.simpleName}.",
                )
            }
            logInfo(
                bootstrapLogTag,
                "crypto ephemeral ready algorithm=${phoneEphemeral.algorithm}",
            )
            val clientNonce = RelaySecureCrypto.randomBytes(32)
            events = Channel(Channel.UNLIMITED)
            val relayEvents = events
            logInfo(bootstrapLogTag, "relay connect start url=$relayUrl")
            socket = socketFactory.connect(
                url = relayUrl,
                headers = mapOf("x-role" to "iphone"),
                listener = object : RelaySocketListener {
                    override fun onOpen() {
                        logInfo(bootstrapLogTag, "relay callback open session=${payload.maskedSessionLabel()}")
                        relayEvents.trySend(RelaySocketEvent.Open)
                    }

                    override fun onMessage(text: String) {
                        relayEvents.trySend(RelaySocketEvent.Message(text))
                    }

                    override fun onClosed(code: Int, reason: String?) {
                        logInfo(
                            bootstrapLogTag,
                            "relay callback closed session=${payload.maskedSessionLabel()} code=$code reason=${reason ?: ""}",
                        )
                        handleSocketDisconnected(
                            connectionToken = connectionToken,
                            snapshot = relayDisconnectSnapshot(
                                code = code,
                                reason = reason,
                                duringHandshake = false,
                            ),
                        )
                        relayEvents.trySend(RelaySocketEvent.Closed(code, reason))
                    }

                    override fun onFailure(throwable: Throwable) {
                        logError(
                            bootstrapLogTag,
                            "relay callback failure session=${payload.maskedSessionLabel()} error=${throwable.message ?: throwable::class.java.simpleName}",
                            throwable,
                        )
                        handleSocketDisconnected(
                            connectionToken = connectionToken,
                            snapshot = transportFailureSnapshot(
                                throwable = throwable,
                                duringHandshake = false,
                            ),
                        )
                        relayEvents.trySend(RelaySocketEvent.Failure(throwable))
                    }
                },
            )
            logInfo(bootstrapLogTag, "relay connect issued session=${payload.maskedSessionLabel()}")

            onPhaseChanged(RelayBootstrapPhase.Connecting)
            awaitRelayOpen(events)
            logInfo(bootstrapLogTag, "relay open session=${payload.maskedSessionLabel()}")

            val clientHello = buildClientHello(
                payload = payload,
                phoneIdentity = resolvedPhoneIdentity,
                phoneEphemeral = phoneEphemeral,
                clientNonce = clientNonce,
                handshakeMode = handshakeMode,
            )
            ensureSent(socket, clientHello.toString())

            onPhaseChanged(RelayBootstrapPhase.Handshaking)
            val serverHello = awaitMatchingServerHello(
                events = events,
                payload = payload,
                clientHello = clientHello,
                clientNonce = clientNonce,
                phoneIdentity = resolvedPhoneIdentity,
                expectedHandshakeMode = handshakeMode,
            )
            logInfo(
                bootstrapLogTag,
                "server hello keyEpoch=${serverHello.keyEpoch} mac=${payload.maskedMacDeviceLabel()}",
            )

            val transcriptBytes = RelaySecureCrypto.buildTranscriptBytes(
                sessionId = payload.sessionId,
                protocolVersion = serverHello.protocolVersion,
                handshakeMode = serverHello.handshakeMode,
                keyEpoch = serverHello.keyEpoch,
                macDeviceId = serverHello.macDeviceId,
                phoneDeviceId = resolvedPhoneIdentity.deviceId,
                macIdentityPublicKey = serverHello.macIdentityPublicKey,
                phoneIdentityPublicKey = resolvedPhoneIdentity.publicKeyBase64,
                macEphemeralPublicKey = serverHello.macEphemeralPublicKey,
                phoneEphemeralPublicKey = phoneEphemeral.publicKeyBase64,
                clientNonce = clientNonce,
                serverNonce = Base64.getDecoder().decode(serverHello.serverNonce),
                expiresAtForTranscript = serverHello.expiresAtForTranscript,
            )
            RelaySecureCrypto.verifyMacSignature(
                macIdentityPublicKey = serverHello.macIdentityPublicKey,
                transcriptBytes = transcriptBytes,
                macSignature = serverHello.macSignature,
            )

            val clientAuth = buildClientAuth(
                sessionId = payload.sessionId,
                phoneIdentity = resolvedPhoneIdentity,
                keyEpoch = serverHello.keyEpoch,
                transcriptBytes = transcriptBytes,
            )
            ensureSent(socket, clientAuth.toString())

            val secureReadyDisplayName = awaitMatchingSecureReady(
                events = events,
                expectedSessionId = payload.sessionId,
                expectedKeyEpoch = serverHello.keyEpoch,
                expectedMacDeviceId = payload.macDeviceId,
            )
            logInfo(
                bootstrapLogTag,
                "secure ready session=${payload.maskedSessionLabel()} keyEpoch=${serverHello.keyEpoch}" +
                    (secureReadyDisplayName?.let { " displayName=$it" } ?: ""),
            )

            val sessionKeys = RelaySecureCrypto.deriveSessionKeys(
                macEphemeralPublicKey = serverHello.macEphemeralPublicKey,
                phoneEphemeralPrivateKey = phoneEphemeral.privateKey,
                transcriptBytes = transcriptBytes,
                sessionId = payload.sessionId,
                macDeviceId = payload.macDeviceId,
                phoneDeviceId = resolvedPhoneIdentity.deviceId,
                keyEpoch = serverHello.keyEpoch,
            )
            val secureSession = ActiveSecureRelaySession(
                connectionToken = connectionToken,
                socket = socket,
                events = events,
                sessionId = payload.sessionId,
                keyEpoch = serverHello.keyEpoch,
                macDeviceId = payload.macDeviceId,
                macIdentityPublicKey = payload.macIdentityPublicKey,
                phoneDeviceId = resolvedPhoneIdentity.deviceId,
                phoneIdentityPublicKey = resolvedPhoneIdentity.publicKeyBase64,
                phoneToMacKey = sessionKeys.first,
                macToPhoneKey = sessionKeys.second,
                nextOutboundCounter = 0,
                lastInboundCounter = -1,
            )
            activeSession = secureSession
            lastSessionDisconnect = null
            clearLiveThreadRuntimeState()

            val resumeState = JSONObject()
                .put("kind", "resumeState")
                .put("sessionId", payload.sessionId)
                .put("keyEpoch", serverHello.keyEpoch)
                .put("lastAppliedBridgeOutboundSeq", 0)
            ensureSent(socket, resumeState.toString())
            val initializeLabel = initializeSecureSession(secureSession)
            logInfo(
                bootstrapLogTag,
                "initialize complete session=${payload.maskedSessionLabel()} label=$initializeLabel",
            )

            onPhaseChanged(RelayBootstrapPhase.Verified)
            val verification = RelayBootstrapVerification(
                sessionId = payload.sessionId,
                sessionLabel = payload.maskedSessionLabel(),
                relayLabel = payload.relayDisplayLabel(),
                macDeviceId = payload.macDeviceId,
                macFingerprint = RelaySecureCrypto.shortFingerprint(payload.macIdentityPublicKey),
                phoneDeviceId = resolvedPhoneIdentity.deviceId,
                phoneFingerprint = RelaySecureCrypto.shortFingerprint(resolvedPhoneIdentity.publicKeyBase64),
                keyEpoch = serverHello.keyEpoch,
                initializeLabel = initializeLabel,
                verifiedAt = now(),
            )
            if (handshakeMode == secureHandshakeModeQrBootstrap) {
                persistTrustedReconnectRecord(payload, resolvedPhoneIdentity, secureReadyDisplayName)
            }
            return verification
        } catch (error: Exception) {
            logError(
                bootstrapLogTag,
                "bootstrap failed session=${payload.maskedSessionLabel()} error=${error.message ?: error::class.java.simpleName}",
                error,
            )
            disconnectActiveSession()
            socket?.close(1000, "bootstrap-failed")
            events?.close()
            throw error
        }
    }

    fun disconnectActiveSession() {
        activeSession?.isOpen = false
        activeSession?.events?.close()
        activeSession?.socket?.close(1000, "session-closed")
        activeSession = null
        lastSessionDisconnect = null
        clearLiveThreadRuntimeState()
    }

    suspend fun fetchThreadList(limit: Int? = null, archived: Boolean = false): List<ThreadSummary> {
        val session = requireActiveSession(
            "The secure session is not active yet. Reconnect before loading live threads.",
        )
        logInfo(
            bootstrapLogTag,
            "thread/list start session=${maskIdentifier(session.sessionId)} limit=${limit ?: "default"}",
        )

        val allThreads = mutableListOf<ThreadSummary>()
        var nextCursor: Any? = JSONObject.NULL
        var hasRequestedFirstPage: Boolean

        do {
            val requestId = "thread-list-${UUID.randomUUID()}"
            val params = JSONObject()
                .put(
                    "sourceKinds",
                    org.json.JSONArray().apply {
                        put("cli")
                        put("vscode")
                        put("appServer")
                        put("exec")
                        put("unknown")
                    },
                )
                .put("cursor", nextCursor ?: JSONObject.NULL)
            if (limit != null) {
                params.put("limit", limit)
            }
            if (archived) {
                params.put("archived", true)
            }

            val request = JSONObject()
                .put("id", requestId)
                .put("method", "thread/list")
                .put("params", params)

            sendEncryptedAppPayload(session, request.toString())
            val response = awaitEncryptedRpcResponse(session, requestId)
            response.optJSONObject("error")?.let { error ->
                val message = error.optString("message")
                    .ifBlank { "The bridge rejected the thread list request." }
                logError(
                    bootstrapLogTag,
                    "thread/list error session=${maskIdentifier(session.sessionId)} message=$message",
                    null,
                )
                throw RelayBootstrapException(message)
            }
            val result = response.optJSONObject("result")
                ?: throw RelayBootstrapException("The bridge returned an invalid thread list response.")
            val page = result.optJSONArray("data")
                ?: result.optJSONArray("items")
                ?: result.optJSONArray("threads")
                ?: throw RelayBootstrapException("The bridge returned a thread list response without thread data.")

            for (index in 0 until page.length()) {
                val threadObject = page.optJSONObject(index) ?: continue
                decodeLiveThreadSummary(threadObject)?.let(allThreads::add)
            }

            nextCursor = when {
                result.has("nextCursor") -> result.opt("nextCursor")
                result.has("next_cursor") -> result.opt("next_cursor")
                else -> JSONObject.NULL
            }
            hasRequestedFirstPage = true
        } while (shouldContinueThreadListPagination(nextCursor, limit, hasRequestedFirstPage))

        logInfo(
            bootstrapLogTag,
            "thread/list success session=${maskIdentifier(session.sessionId)} count=${allThreads.size}",
        )
        return allThreads
    }

    suspend fun fetchThreadDetail(threadId: String): ThreadDetail {
        val session = requireActiveSession(
            "The secure session is not active yet. Reconnect before loading live thread detail.",
        )
        val normalizedThreadId = threadId.trim()
        if (normalizedThreadId.isEmpty()) {
            throw RelayBootstrapException("The live thread detail request needs a thread id.")
        }
        logInfo(
            bootstrapLogTag,
            "thread/read start session=${maskIdentifier(session.sessionId)} thread=${maskIdentifier(normalizedThreadId)}",
        )

        val threadObject = readThreadDetailObject(session, normalizedThreadId)
        val detail = decodeLiveThreadDetail(threadObject)
        logInfo(
            bootstrapLogTag,
            "thread/read success session=${maskIdentifier(session.sessionId)} thread=${maskIdentifier(normalizedThreadId)} entries=${detail.entries.size}",
        )
        return detail
    }

    suspend fun startThread(
        preferredProjectPath: String? = null,
        modelIdentifier: String? = null,
    ): ThreadSummary {
        val session = requireActiveSession(
            "The secure session is not active yet. Reconnect before creating a chat.",
        )
        val requestId = "thread-start-${UUID.randomUUID()}"
        val normalizedProjectPath = normalizeThreadStartProjectPath(preferredProjectPath)
        val trimmedModelIdentifier = modelIdentifier?.trim()?.takeIf(String::isNotEmpty)
        val params = JSONObject()

        if (trimmedModelIdentifier != null) {
            params.put("model", trimmedModelIdentifier)
        }
        if (normalizedProjectPath != null) {
            params.put("cwd", normalizedProjectPath)
        }

        val request = JSONObject()
            .put("id", requestId)
            .put("method", "thread/start")
            .put("params", params)

        logInfo(
            bootstrapLogTag,
            "thread/start start session=${maskIdentifier(session.sessionId)} cwd=${maskIdentifier(normalizedProjectPath.orEmpty())}",
        )
        sendEncryptedAppPayload(session, request.toString())
        val response = awaitEncryptedRpcResponse(session, requestId)

        response.optJSONObject("error")?.let { error ->
            val message = error.optString("message")
                .ifBlank { "The bridge could not create a new chat." }
            logError(
                bootstrapLogTag,
                "thread/start error session=${maskIdentifier(session.sessionId)} message=$message",
                null,
            )
            throw RelayBootstrapException(message)
        }

        val result = response.optJSONObject("result")
            ?: throw RelayBootstrapException("The bridge returned an invalid thread/start response.")
        val summary = decodeStartedThreadSummary(
            result = result,
            preferredProjectPath = normalizedProjectPath,
        ) ?: throw RelayBootstrapException("The bridge did not return the created chat.")
        resumedThreadIds += summary.id
        logInfo(
            bootstrapLogTag,
            "thread/start success session=${maskIdentifier(session.sessionId)} thread=${maskIdentifier(summary.id)}",
        )
        return summary
    }

    suspend fun fetchModelList(): List<RelayModelOption> {
        val session = requireActiveSession(
            "The secure session is not active yet. Reconnect before loading model list.",
        )
        logInfo(bootstrapLogTag, "model/list start session=${maskIdentifier(session.sessionId)}")

        val requestId = "model-list-${UUID.randomUUID()}"
        val params = JSONObject()
            .put("cursor", JSONObject.NULL)
            .put("limit", 50)
            .put("includeHidden", false)

        val request = JSONObject()
            .put("id", requestId)
            .put("method", "model/list")
            .put("params", params)

        sendEncryptedAppPayload(session, request.toString())
        val response = awaitEncryptedRpcResponse(session, requestId)

        response.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifBlank { "The bridge rejected the model list request." }
            logError(bootstrapLogTag, "model/list error: $message", null)
            throw RelayBootstrapException(message)
        }

        val result = response.optJSONObject("result")
            ?: throw RelayBootstrapException("The bridge returned an invalid model list response.")

        val page = result.optJSONArray("items")
            ?: result.optJSONArray("data")
            ?: result.optJSONArray("models")
            ?: throw RelayBootstrapException("The bridge returned a model list response without model data.")

        val models = mutableListOf<RelayModelOption>()
        for (i in 0 until page.length()) {
            val obj = page.optJSONObject(i) ?: continue
            decodeRelayModelOption(obj)?.let(models::add)
        }

        logInfo(bootstrapLogTag, "model/list success count=${models.size}")
        return models
    }

    private fun decodeRelayModelOption(obj: JSONObject): RelayModelOption? {
        val id = obj.optString("id").ifBlank { return null }
        val model = obj.optString("model").ifBlank { id }
        val displayName = obj.optString("displayName").ifBlank {
            obj.optString("display_name").ifBlank { model }
        }
        val description = obj.optString("description", "")
        val isDefault = obj.optBoolean("isDefault", false)
            || obj.optBoolean("is_default", false)

        val defaultReasoningEffort = obj.optString("defaultReasoningEffort", "").ifBlank {
            obj.optString("default_reasoning_effort", "")
        }.ifBlank { null }

        val supportedEfforts = mutableListOf<RelayReasoningEffortOption>()
        val effortsArray = obj.optJSONArray("supportedReasoningEfforts")
            ?: obj.optJSONArray("supported_reasoning_efforts")
        if (effortsArray != null) {
            for (j in 0 until effortsArray.length()) {
                val effortObj = effortsArray.optJSONObject(j) ?: continue
                var effort = effortObj.optString("reasoningEffort", "")
                if (effort.isBlank()) effort = effortObj.optString("reasoning_effort", "")
                if (effort.isBlank()) continue
                val desc = effortObj.optString("description", "")
                supportedEfforts.add(RelayReasoningEffortOption(effort, desc))
            }
        }

        return RelayModelOption(
            id = id,
            model = model,
            displayName = displayName,
            description = description,
            isDefault = isDefault,
            supportedReasoningEfforts = supportedEfforts,
            defaultReasoningEffort = defaultReasoningEffort,
        )
    }

    suspend fun fetchContextWindowUsage(threadId: String): Pair<Long, Long>? {
        val session = requireActiveSession(
            "The secure session is not active yet.",
        )
        val normalizedThreadId = threadId.trim()
        if (normalizedThreadId.isEmpty()) return null

        val requestId = "ctx-window-${UUID.randomUUID()}"
        val params = JSONObject().put("threadId", normalizedThreadId)

        val request = JSONObject()
            .put("id", requestId)
            .put("method", "thread/contextWindow/read")
            .put("params", params)

        sendEncryptedAppPayload(session, request.toString())
        val response = awaitEncryptedRpcResponse(session, requestId)

        response.optJSONObject("error")?.let { return null }

        val result = response.optJSONObject("result") ?: return null
        val usage = result.optJSONObject("usage") ?: result

        val tokensUsed = firstLongFromKeys(usage,
            "tokensUsed", "tokens_used", "totalTokens", "total_tokens",
            "usedTokens", "used_tokens", "inputTokens", "input_tokens",
        )
        val tokenLimit = firstLongFromKeys(usage,
            "tokenLimit", "token_limit", "maxTokens", "max_tokens",
            "contextWindow", "context_window",
        )
        val tokensRemaining = firstLongFromKeys(usage,
            "tokensRemaining", "tokens_remaining", "remainingTokens", "remaining_tokens",
            "remainingInputTokens", "remaining_input_tokens",
        )

        val resolvedUsed = maxOf(0L, tokensUsed ?: 0L)
        val resolvedLimit = tokenLimit
            ?: if (tokensRemaining != null) resolvedUsed + maxOf(0L, tokensRemaining) else null
        if (resolvedLimit == null || resolvedLimit <= 0L) return null

        return Pair(minOf(resolvedUsed, resolvedLimit), resolvedLimit)
    }

    private fun firstLongFromKeys(obj: JSONObject, vararg keys: String): Long? {
        for (key in keys) {
            if (obj.has(key) && !obj.isNull(key)) {
                val value = obj.optLong(key, Long.MIN_VALUE)
                if (value != Long.MIN_VALUE) return value
            }
        }
        return null
    }

    suspend fun sendTurnStart(
        threadId: String,
        userInput: String,
        attachments: List<ImageAttachment> = emptyList(),
        projectPath: String? = null,
        modelIdentifier: String? = null,
        reasoningEffort: String? = null,
        serviceTier: String? = null,
        accessMode: AccessMode = AccessMode.OnRequest,
        collaborationMode: CollaborationModeKind? = null,
    ): LiveTurnStartReceipt {
        val session = requireActiveSession(
            "The secure session is not active yet. Reconnect before sending a live message.",
        )
        val normalizedThreadId = threadId.trim()
        val trimmedInput = userInput.trim()
        val normalizedAttachments = attachments.filter { resolveOutgoingImagePayload(it) != null }
        if (normalizedThreadId.isEmpty()) {
            throw RelayBootstrapException("A live send needs a thread id.")
        }
        if (trimmedInput.isEmpty() && normalizedAttachments.isEmpty()) {
            throw RelayBootstrapException("Type a message or attach an image before sending it to the live thread.")
        }

        ensureThreadResumed(
            session = session,
            threadId = normalizedThreadId,
            projectPath = projectPath,
        )
        logInfo(
            bootstrapLogTag,
            "turn/start start session=${maskIdentifier(session.sessionId)} thread=${maskIdentifier(normalizedThreadId)} chars=${trimmedInput.length}",
        )

        val response = try {
            sendTurnStartRequest(
                session = session,
                threadId = normalizedThreadId,
                userInput = trimmedInput,
                attachments = normalizedAttachments,
                modelIdentifier = modelIdentifier,
                reasoningEffort = reasoningEffort,
                serviceTier = serviceTier,
                accessMode = accessMode,
                collaborationMode = collaborationMode,
                useSnakeCaseThreadId = false,
                imageUrlKey = "url",
            )
        } catch (error: RelayBootstrapException) {
            val message = error.message.orEmpty()
            if (normalizedAttachments.isNotEmpty() && shouldRetryTurnStartWithImageUrlField(message)) {
                try {
                    sendTurnStartRequest(
                        session = session,
                        threadId = normalizedThreadId,
                        userInput = trimmedInput,
                        attachments = normalizedAttachments,
                        modelIdentifier = modelIdentifier,
                        reasoningEffort = reasoningEffort,
                        serviceTier = serviceTier,
                        accessMode = accessMode,
                        collaborationMode = collaborationMode,
                        useSnakeCaseThreadId = false,
                        imageUrlKey = "image_url",
                    )
                } catch (retryError: RelayBootstrapException) {
                    if (!shouldRetryTurnStartWithSnakeCase(retryError.message.orEmpty())) {
                        throw retryError
                    }
                    sendTurnStartRequest(
                        session = session,
                        threadId = normalizedThreadId,
                        userInput = trimmedInput,
                        attachments = normalizedAttachments,
                        modelIdentifier = modelIdentifier,
                        reasoningEffort = reasoningEffort,
                        serviceTier = serviceTier,
                        accessMode = accessMode,
                        collaborationMode = collaborationMode,
                        useSnakeCaseThreadId = true,
                        imageUrlKey = "image_url",
                    )
                }
            } else {
                if (!shouldRetryTurnStartWithSnakeCase(message)) {
                    throw error
                }
                try {
                    sendTurnStartRequest(
                        session = session,
                        threadId = normalizedThreadId,
                        userInput = trimmedInput,
                        attachments = normalizedAttachments,
                        modelIdentifier = modelIdentifier,
                        reasoningEffort = reasoningEffort,
                        serviceTier = serviceTier,
                        accessMode = accessMode,
                        collaborationMode = collaborationMode,
                        useSnakeCaseThreadId = true,
                        imageUrlKey = "url",
                    )
                } catch (retryError: RelayBootstrapException) {
                    if (!(normalizedAttachments.isNotEmpty()
                            && shouldRetryTurnStartWithImageUrlField(retryError.message.orEmpty()))
                    ) {
                        throw retryError
                    }
                    sendTurnStartRequest(
                        session = session,
                        threadId = normalizedThreadId,
                        userInput = trimmedInput,
                        attachments = normalizedAttachments,
                        modelIdentifier = modelIdentifier,
                        reasoningEffort = reasoningEffort,
                        serviceTier = serviceTier,
                        accessMode = accessMode,
                        collaborationMode = collaborationMode,
                        useSnakeCaseThreadId = true,
                        imageUrlKey = "image_url",
                    )
                }
            }
        }

        val turnId = extractTurnId(response.optJSONObject("result"))
        resumedThreadIds += normalizedThreadId
        logInfo(
            bootstrapLogTag,
            "turn/start success session=${maskIdentifier(session.sessionId)} thread=${maskIdentifier(normalizedThreadId)} turn=${maskIdentifier(turnId.orEmpty())}",
        )
        return LiveTurnStartReceipt(
            threadId = normalizedThreadId,
            turnId = turnId,
            userInput = trimmedInput,
            sentAt = now(),
        )
    }

    suspend fun continueThread(
        threadId: String,
        projectPath: String? = null,
        modelIdentifier: String? = null,
        reasoningEffort: String? = null,
        serviceTier: String? = null,
        accessMode: AccessMode = AccessMode.OnRequest,
        collaborationMode: CollaborationModeKind? = null,
    ): LiveTurnStartReceipt {
        val session = requireActiveSession(
            "The secure session is not active yet. Reconnect before continuing this thread.",
        )
        val normalizedThreadId = threadId.trim()
        if (normalizedThreadId.isEmpty()) {
            throw RelayBootstrapException("A continue action needs a thread id.")
        }

        val runState = readThreadRunState(session, normalizedThreadId)
        if (runState.isRunning) {
            throw RelayBootstrapException(
                "This thread is still running on the Mac. Stop it or wait for completion before continuing.",
            )
        }

        return sendTurnStart(
            threadId = normalizedThreadId,
            userInput = "continue",
            projectPath = projectPath,
            modelIdentifier = modelIdentifier,
            reasoningEffort = reasoningEffort,
            serviceTier = serviceTier,
            accessMode = accessMode,
            collaborationMode = collaborationMode,
        )
    }

    suspend fun interruptTurn(
        threadId: String,
        turnId: String? = null,
    ): LiveTurnInterruptReceipt {
        val session = requireActiveSession(
            "The secure session is not active yet. Reconnect before stopping a live run.",
        )
        val normalizedThreadId = threadId.trim()
        if (normalizedThreadId.isEmpty()) {
            throw RelayBootstrapException("A stop action needs a thread id.")
        }

        var resolvedTurnId = turnId?.trim()?.takeIf { it.isNotEmpty() }
        if (resolvedTurnId == null) {
            val runState = readThreadRunState(session, normalizedThreadId)
            resolvedTurnId = runState.activeTurnId
            if (resolvedTurnId == null && runState.hasInterruptibleTurnWithoutId) {
                throw RelayBootstrapException(
                    "The active run has not published a turn id yet. Try stopping it again in a moment.",
                )
            }
            if (resolvedTurnId == null) {
                throw RelayBootstrapException("There is no active run to stop in this thread.")
            }
        }

        logInfo(
            bootstrapLogTag,
            "turn/interrupt start session=${maskIdentifier(session.sessionId)} thread=${maskIdentifier(normalizedThreadId)} turn=${maskIdentifier(resolvedTurnId)}",
        )

        val confirmedTurnId = sendTurnInterruptRequestWithRetries(
            session = session,
            threadId = normalizedThreadId,
            turnId = resolvedTurnId,
        )

        logInfo(
            bootstrapLogTag,
            "turn/interrupt success session=${maskIdentifier(session.sessionId)} thread=${maskIdentifier(normalizedThreadId)} turn=${maskIdentifier(confirmedTurnId)}",
        )

        return LiveTurnInterruptReceipt(
            threadId = normalizedThreadId,
            turnId = confirmedTurnId,
            interruptedAt = now(),
        )
    }

    suspend fun renameThread(
        threadId: String,
        name: String,
    ) {
        val session = requireActiveSession(
            "The secure session is not active yet. Reconnect before renaming this conversation.",
        )
        val normalizedThreadId = threadId.trim()
        val trimmedName = name.trim()
        if (normalizedThreadId.isEmpty()) {
            throw RelayBootstrapException("A rename action needs a thread id.")
        }
        if (trimmedName.isEmpty()) {
            throw RelayBootstrapException("Conversation title cannot be empty.")
        }

        val requestId = "thread-name-set-${UUID.randomUUID()}"
        val request = JSONObject()
            .put("id", requestId)
            .put("method", "thread/name/set")
            .put(
                "params",
                JSONObject()
                    .put("thread_id", normalizedThreadId)
                    .put("name", trimmedName),
            )
        sendEncryptedAppPayload(session, request.toString())
        val response = awaitEncryptedRpcResponse(session, requestId)
        response.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifBlank { "The bridge rejected the rename request." }
            throw RelayBootstrapException(message)
        }
    }

    suspend fun archiveThread(
        threadId: String,
        unarchive: Boolean,
    ) {
        val session = requireActiveSession(
            "The secure session is not active yet. Reconnect before changing this conversation.",
        )
        val normalizedThreadId = threadId.trim()
        if (normalizedThreadId.isEmpty()) {
            throw RelayBootstrapException("A thread archive action needs a thread id.")
        }

        val requestId = "thread-archive-${UUID.randomUUID()}"
        val request = JSONObject()
            .put("id", requestId)
            .put("method", if (unarchive) "thread/unarchive" else "thread/archive")
            .put(
                "params",
                JSONObject()
                    .put("threadId", normalizedThreadId),
            )
        sendEncryptedAppPayload(session, request.toString())
        val response = awaitEncryptedRpcResponse(session, requestId)
        response.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifBlank {
                if (unarchive) "The bridge rejected the unarchive request." else "The bridge rejected the archive request."
            }
            throw RelayBootstrapException(message)
        }
    }

    private suspend fun awaitRelayOpen(events: Channel<RelaySocketEvent>) {
        withTimeout(relayWaitTimeoutMs) {
            while (true) {
                when (val event = events.receive()) {
                    RelaySocketEvent.Open -> return@withTimeout
                    is RelaySocketEvent.Failure -> {
                        val snapshot = transportFailureSnapshot(
                            throwable = event.throwable,
                            duringHandshake = true,
                        )
                        throw RelayBootstrapException(snapshot.message, snapshot.failureKind)
                    }

                    is RelaySocketEvent.Closed -> {
                        val snapshot = relayDisconnectSnapshot(
                            code = event.code,
                            reason = event.reason,
                            duringHandshake = true,
                        )
                        throw RelayBootstrapException(snapshot.message, snapshot.failureKind)
                    }

                    is RelaySocketEvent.Message -> {
                        continue
                    }
                }
            }
        }
    }

    private suspend fun awaitMatchingServerHello(
        events: Channel<RelaySocketEvent>,
        payload: PairingQrPayload,
        clientHello: JSONObject,
        clientNonce: ByteArray,
        phoneIdentity: RuntimePhoneIdentity,
        expectedHandshakeMode: String,
    ): ServerHelloMessage {
        var matchedServerHello: ServerHelloMessage? = null
        withTimeout(relayWaitTimeoutMs) {
            while (matchedServerHello == null) {
                when (val event = events.receive()) {
                    RelaySocketEvent.Open -> continue
                    is RelaySocketEvent.Failure -> {
                        val snapshot = transportFailureSnapshot(
                            throwable = event.throwable,
                            duringHandshake = true,
                        )
                        throw RelayBootstrapException(snapshot.message, snapshot.failureKind)
                    }

                    is RelaySocketEvent.Closed -> {
                        val snapshot = relayDisconnectSnapshot(
                            code = event.code,
                            reason = event.reason,
                            duringHandshake = true,
                        )
                        throw RelayBootstrapException(snapshot.message, snapshot.failureKind)
                    }

                    is RelaySocketEvent.Message -> {
                        val json = parseJsonOrNull(event.text) ?: continue
                        when (json.optString("kind")) {
                            "secureError" -> throw RelayBootstrapException(
                                json.optString("message", "The bridge rejected the secure pairing request."),
                            )

                            "serverHello" -> {
                                val serverHello = decodeServerHello(json)
                                if (!isMatchingServerHello(
                                        serverHello = serverHello,
                                        payload = payload,
                                        expectedClientNonce = clientHello.optString("clientNonce"),
                                        clientNonce = clientNonce,
                                        phoneIdentity = phoneIdentity,
                                        phoneEphemeralPublicKey = clientHello.optString("phoneEphemeralPublicKey"),
                                        expectedHandshakeMode = expectedHandshakeMode,
                                    )
                                ) {
                                    continue
                                }
                                matchedServerHello = serverHello
                            }
                        }
                    }
                }
            }
        }
        return matchedServerHello ?: throw RelayBootstrapException(
            "The bridge did not return a usable secure handshake response before timing out.",
        )
    }

    private suspend fun awaitMatchingSecureReady(
        events: Channel<RelaySocketEvent>,
        expectedSessionId: String,
        expectedKeyEpoch: Int,
        expectedMacDeviceId: String,
    ): String? {
        var resolvedDisplayName: String? = null
        withTimeout(relayWaitTimeoutMs) {
            while (true) {
                when (val event = events.receive()) {
                    RelaySocketEvent.Open -> continue
                    is RelaySocketEvent.Failure -> {
                        val snapshot = transportFailureSnapshot(
                            throwable = event.throwable,
                            duringHandshake = true,
                        )
                        throw RelayBootstrapException(snapshot.message, snapshot.failureKind)
                    }

                    is RelaySocketEvent.Closed -> {
                        val snapshot = relayDisconnectSnapshot(
                            code = event.code,
                            reason = event.reason,
                            duringHandshake = true,
                        )
                        throw RelayBootstrapException(snapshot.message, snapshot.failureKind)
                    }

                    is RelaySocketEvent.Message -> {
                        val json = parseJsonOrNull(event.text) ?: continue
                        when (json.optString("kind")) {
                            "secureError" -> throw RelayBootstrapException(
                                json.optString("message", "The bridge rejected secure pairing."),
                            )

                            "secureReady" -> {
                                val sessionId = json.optString("sessionId")
                                val keyEpoch = json.optInt("keyEpoch")
                                val macDeviceId = json.optString("macDeviceId")
                                if (
                                    sessionId == expectedSessionId
                                    && keyEpoch == expectedKeyEpoch
                                    && macDeviceId == expectedMacDeviceId
                                ) {
                                    resolvedDisplayName = readMeaningfulString(json.optString("displayName", ""))
                                    return@withTimeout
                                }
                            }
                        }
                    }
                }
            }
        }
        return resolvedDisplayName
    }

    private fun buildClientHello(
        payload: PairingQrPayload,
        phoneIdentity: RuntimePhoneIdentity,
        phoneEphemeral: RuntimeEphemeralKeyPair,
        clientNonce: ByteArray,
        handshakeMode: String,
    ): JSONObject {
        return JSONObject()
            .put("kind", "clientHello")
            .put("protocolVersion", secureProtocolVersion)
            .put("sessionId", payload.sessionId)
            .put("handshakeMode", handshakeMode)
            .put("phoneDeviceId", phoneIdentity.deviceId)
            .put("phoneIdentityPublicKey", phoneIdentity.publicKeyBase64)
            .put("phoneEphemeralPublicKey", phoneEphemeral.publicKeyBase64)
            .put("clientNonce", Base64.getEncoder().encodeToString(clientNonce))
    }

    private fun buildClientAuth(
        sessionId: String,
        phoneIdentity: RuntimePhoneIdentity,
        keyEpoch: Int,
        transcriptBytes: ByteArray,
    ): JSONObject {
        val signature = RelaySecureCrypto.signClientAuthTranscript(
            privateKey = phoneIdentity.privateKey,
            transcriptBytes = transcriptBytes,
        )
        return JSONObject()
            .put("kind", "clientAuth")
            .put("sessionId", sessionId)
            .put("phoneDeviceId", phoneIdentity.deviceId)
            .put("keyEpoch", keyEpoch)
            .put("phoneSignature", signature)
    }

    private fun isMatchingServerHello(
        serverHello: ServerHelloMessage,
        payload: PairingQrPayload,
        expectedClientNonce: String,
        clientNonce: ByteArray,
        phoneIdentity: RuntimePhoneIdentity,
        phoneEphemeralPublicKey: String,
        expectedHandshakeMode: String,
    ): Boolean {
        if (serverHello.protocolVersion != secureProtocolVersion) {
            throw RelayBootstrapException(
                "The bridge is using a different secure transport version. Update Remodex on your Mac and try again.",
            )
        }
        if (serverHello.sessionId != payload.sessionId) {
            return false
        }
        if (serverHello.macDeviceId != payload.macDeviceId) {
            return false
        }
        if (serverHello.macIdentityPublicKey != payload.macIdentityPublicKey) {
            throw RelayBootstrapException(
                "The bridge reported a different Mac identity than the pairing code you accepted.",
            )
        }
        if (serverHello.handshakeMode != expectedHandshakeMode) {
            throw RelayBootstrapException(
                "The bridge responded with the wrong secure reconnect mode.",
            )
        }

        val echoedNonce = serverHello.clientNonce
        if (!echoedNonce.isNullOrBlank()) {
            return echoedNonce == expectedClientNonce
        }

        val transcriptBytes = RelaySecureCrypto.buildTranscriptBytes(
            sessionId = payload.sessionId,
            protocolVersion = serverHello.protocolVersion,
            handshakeMode = serverHello.handshakeMode,
            keyEpoch = serverHello.keyEpoch,
            macDeviceId = serverHello.macDeviceId,
            phoneDeviceId = phoneIdentity.deviceId,
            macIdentityPublicKey = serverHello.macIdentityPublicKey,
            phoneIdentityPublicKey = phoneIdentity.publicKeyBase64,
            macEphemeralPublicKey = serverHello.macEphemeralPublicKey,
            phoneEphemeralPublicKey = phoneEphemeralPublicKey,
            clientNonce = clientNonce,
            serverNonce = Base64.getDecoder().decode(serverHello.serverNonce),
            expiresAtForTranscript = serverHello.expiresAtForTranscript,
        )
        return RelaySecureCrypto.isMacSignatureValid(
            macIdentityPublicKey = serverHello.macIdentityPublicKey,
            transcriptBytes = transcriptBytes,
            macSignature = serverHello.macSignature,
        )
    }

    private fun decodeServerHello(json: JSONObject): ServerHelloMessage {
        return ServerHelloMessage(
            protocolVersion = json.optInt("protocolVersion"),
            sessionId = json.optString("sessionId"),
            handshakeMode = json.optString("handshakeMode"),
            macDeviceId = json.optString("macDeviceId"),
            macIdentityPublicKey = json.optString("macIdentityPublicKey"),
            macEphemeralPublicKey = json.optString("macEphemeralPublicKey"),
            serverNonce = json.optString("serverNonce"),
            keyEpoch = json.optInt("keyEpoch"),
            expiresAtForTranscript = json.optLong("expiresAtForTranscript"),
            macSignature = json.optString("macSignature"),
            clientNonce = json.optString("clientNonce").ifBlank { null },
        )
    }

    private fun relaySocketUrl(payload: PairingQrPayload): String {
        val base = payload.relay.trimEnd('/')
        return URI("$base/${payload.sessionId}").toString()
    }

    private fun relayDisconnectSnapshot(
        code: Int,
        reason: String?,
        duringHandshake: Boolean,
    ): SessionDisconnectSnapshot {
        return when (code) {
            4000, 4001, 4003 -> SessionDisconnectSnapshot(
                message = "The saved pairing code is no longer usable. Generate a fresh one from the Mac bridge.",
                failureKind = RelayBootstrapFailureKind.PairingExpired,
            )

            4002 -> SessionDisconnectSnapshot(
                message = if (duringHandshake) {
                    "The relay could not find a live Mac bridge session for this pairing code. Start the bridge again and retry with a fresh code."
                } else {
                    "The saved Mac session is temporarily unavailable. Android can reconnect when the bridge is live again."
                },
                failureKind = if (duringHandshake) {
                    RelayBootstrapFailureKind.Generic
                } else {
                    RelayBootstrapFailureKind.SessionUnavailable
                },
            )

            4004 -> SessionDisconnectSnapshot(
                message = if (duringHandshake) {
                    "The Mac bridge dropped out before secure pairing finished. Retry after the bridge is live again."
                } else {
                    "The Mac bridge dropped out and the live Android session needs to reconnect."
                },
                failureKind = if (duringHandshake) {
                    RelayBootstrapFailureKind.Generic
                } else {
                    RelayBootstrapFailureKind.SessionDisconnected
                },
            )

            else -> SessionDisconnectSnapshot(
                message = reason?.takeIf { it.isNotBlank() } ?: if (duringHandshake) {
                    "The relay connection closed before secure pairing could finish."
                } else {
                    "The live relay connection closed and needs to reconnect."
                },
                failureKind = if (duringHandshake) {
                    RelayBootstrapFailureKind.Generic
                } else {
                    RelayBootstrapFailureKind.SessionDisconnected
                },
            )
        }
    }

    private fun transportFailureSnapshot(
        throwable: Throwable,
        duringHandshake: Boolean,
    ): SessionDisconnectSnapshot {
        val detail = throwable.message?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown error"
        return SessionDisconnectSnapshot(
            message = if (duringHandshake) {
                "The relay connection failed before secure pairing could finish: $detail."
            } else {
                "The live relay connection dropped: $detail."
            },
            failureKind = if (duringHandshake) {
                RelayBootstrapFailureKind.Generic
            } else {
                RelayBootstrapFailureKind.SessionDisconnected
            },
        )
    }

    private fun handleSocketDisconnected(
        connectionToken: String,
        snapshot: SessionDisconnectSnapshot,
    ) {
        val session = activeSession ?: return
        if (session.connectionToken != connectionToken) {
            return
        }
        session.isOpen = false
        activeSession = null
        lastSessionDisconnect = snapshot
        sessionLossListener?.invoke(
            RelayBootstrapException(
                snapshot.message,
                snapshot.failureKind,
            ),
        )
    }

    private fun requireActiveSession(defaultMessage: String): ActiveSecureRelaySession {
        val session = activeSession
        if (session != null && session.isOpen) {
            return session
        }

        activeSession = null
        val snapshot = lastSessionDisconnect
        throw RelayBootstrapException(
            snapshot?.message ?: defaultMessage,
            snapshot?.failureKind ?: RelayBootstrapFailureKind.SessionMissing,
        )
    }

    private suspend fun resolveTrustedSessionImpl(
        trustedRecord: TrustedReconnectRecord,
    ): TrustedSessionResolveResponse = withContext(Dispatchers.IO) {
        val resolveUrl = trustedSessionResolveUrl(trustedRecord.relay)
            ?: throw RelayBootstrapException("The saved trusted Mac relay URL is invalid.")
        val nonce = UUID.randomUUID().toString()
        val timestamp = now().toEpochMilli()
        val transcriptBytes = RelaySecureCrypto.buildTrustedSessionResolveBytes(
            macDeviceId = trustedRecord.macDeviceId,
            phoneDeviceId = trustedRecord.phoneDeviceId,
            phoneIdentityPublicKey = trustedRecord.phoneIdentityPublicKey,
            nonce = nonce,
            timestamp = timestamp,
        )
        val signature = RelaySecureCrypto.signTrustedSessionResolveRequest(
            privateKey = trustedRecord.toRuntimePhoneIdentity().privateKey,
            transcriptBytes = transcriptBytes,
        )
        val requestBody = JSONObject()
            .put("macDeviceId", trustedRecord.macDeviceId)
            .put("phoneDeviceId", trustedRecord.phoneDeviceId)
            .put("phoneIdentityPublicKey", trustedRecord.phoneIdentityPublicKey)
            .put("nonce", nonce)
            .put("timestamp", timestamp)
            .put("signature", signature)
        val request = Request.Builder()
            .url(resolveUrl)
            .post(requestBody.toString().toRequestBody(jsonMediaType))
            .build()

        val client = if (relayEndpointProfile(resolveUrl).prefersDirectNoProxy) {
            httpClient.newBuilder()
                .proxy(Proxy.NO_PROXY)
                .build()
        } else {
            httpClient
        }

        try {
            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                val jsonBody = parseJsonOrNull(rawBody)

                if (response.isSuccessful) {
                    val sessionId = jsonBody?.optString("sessionId").orEmpty().trim()
                    val macDeviceId = jsonBody?.optString("macDeviceId").orEmpty().trim()
                    val macIdentityPublicKey = jsonBody?.optString("macIdentityPublicKey").orEmpty().trim()
                    if (sessionId.isEmpty() || macDeviceId.isEmpty() || macIdentityPublicKey.isEmpty()) {
                        throw RelayBootstrapException("The trusted Mac relay returned malformed session data.")
                    }
                    return@withContext TrustedSessionResolveResponse(
                        macDeviceId = macDeviceId,
                        macIdentityPublicKey = macIdentityPublicKey,
                        displayName = readMeaningfulString(jsonBody?.optString("displayName", "")),
                        sessionId = sessionId,
                    )
                }

                val errorCode = jsonBody?.optString("code").orEmpty()
                val errorMessage = jsonBody?.optString("error")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: rawBody.trim().takeIf { it.isNotEmpty() }
                throw RelayBootstrapException(
                    message = when (errorCode) {
                        "session_unavailable" -> "Your trusted Mac is offline right now."
                        "phone_not_trusted", "invalid_signature" -> "This Android device is no longer trusted by the Mac. Another device may have replaced the trust. Update the bridge to multi-phone trust, or pair with a fresh QR code."
                        "resolve_request_replayed", "resolve_request_expired" -> "The trusted reconnect request expired. Try reconnecting again."
                        "invalid_request" -> "The trusted reconnect request was rejected by the relay."
                        else -> if (response.code == 404) {
                            "This relay does not support live trusted-session lookup yet."
                        } else {
                            errorMessage ?: "The trusted Mac relay could not resolve the current bridge session."
                        }
                    },
                    failureKind = when (errorCode) {
                        "session_unavailable" -> RelayBootstrapFailureKind.SessionUnavailable
                        "phone_not_trusted", "invalid_signature" -> RelayBootstrapFailureKind.PairingExpired
                        else -> RelayBootstrapFailureKind.Generic
                    },
                )
            }
        } catch (error: IOException) {
            throw RelayBootstrapException(
                "Could not reach the trusted Mac relay. Check your connection and try again.",
            )
        }
    }

    private suspend fun resolveTrustedReconnectTarget(
        trustedRecord: TrustedReconnectRecord,
    ): TrustedReconnectTarget {
        val resolver: suspend (TrustedReconnectRecord) -> TrustedSessionResolveResponse =
            trustedSessionResolver ?: { record -> resolveTrustedSessionImpl(record) }

        return try {
            val resolved = resolver(trustedRecord)
            val refreshedAt = now()
            val payload = PairingQrPayload(
                version = 2,
                relay = trustedRecord.relay,
                sessionId = resolved.sessionId,
                macDeviceId = resolved.macDeviceId,
                macIdentityPublicKey = resolved.macIdentityPublicKey,
                expiresAt = refreshedAt.plusSeconds(5 * 60).toEpochMilli(),
            )
            TrustedReconnectTarget(
                payload = payload,
                trustedRecord = trustedRecord.copy(
                    macDeviceId = resolved.macDeviceId,
                    macIdentityPublicKey = resolved.macIdentityPublicKey,
                    displayName = resolved.displayName ?: trustedRecord.displayName,
                    lastResolvedSessionId = resolved.sessionId,
                    lastResolvedAt = refreshedAt.toEpochMilli(),
                    lastUsedAt = refreshedAt.toEpochMilli(),
                ),
                reconnectPath = TrustedReconnectPath.ResolvedLiveSession,
            )
        } catch (error: RelayBootstrapException) {
            val fallbackSessionId = trustedRecord.lastResolvedSessionId?.trim().orEmpty()
            if (fallbackSessionId.isEmpty() || error.failureKind == RelayBootstrapFailureKind.PairingExpired) {
                throw error
            }

            logInfo(
                bootstrapLogTag,
                "trusted resolve fallback relay=${trustedRecord.relayDisplayLabel()} session=${maskIdentifier(fallbackSessionId)}",
            )
            val fallbackAt = now()
            TrustedReconnectTarget(
                payload = PairingQrPayload(
                    version = 2,
                    relay = trustedRecord.relay,
                    sessionId = fallbackSessionId,
                    macDeviceId = trustedRecord.macDeviceId,
                    macIdentityPublicKey = trustedRecord.macIdentityPublicKey,
                    expiresAt = fallbackAt.plusSeconds(5 * 60).toEpochMilli(),
                ),
                trustedRecord = trustedRecord.copy(
                    lastUsedAt = fallbackAt.toEpochMilli(),
                ),
                reconnectPath = TrustedReconnectPath.SavedSessionFallback,
            )
        }
    }

    private fun trustedSessionResolveUrl(relay: String): String? {
        val components = runCatching { URI(relay.trim()) }.getOrNull() ?: return null
        val scheme = when (components.scheme?.lowercase(Locale.US)) {
            "wss" -> "https"
            "ws" -> "http"
            "https", "http" -> components.scheme.lowercase(Locale.US)
            else -> return null
        }
        val pathSegments = components.path.orEmpty()
            .split('/')
            .filter { it.isNotBlank() }
        val rebuiltPath = if (pathSegments.lastOrNull() == "relay") {
            val prefix = pathSegments.dropLast(1)
            "/" + (prefix + listOf("v1", "trusted", "session", "resolve")).joinToString("/")
        } else {
            "/v1/trusted/session/resolve"
        }
        return URI(
            scheme,
            components.userInfo,
            components.host,
            components.port,
            rebuiltPath,
            null,
            null,
        ).toString()
    }

    private fun persistTrustedReconnectRecord(
        payload: PairingQrPayload,
        phoneIdentity: RuntimePhoneIdentity,
        bridgeDisplayName: String? = null,
    ) {
        val persistence = trustedReconnectPersistence ?: return
        val existing = persistence.read()
        persistence.write(
            TrustedReconnectRecord(
                macDeviceId = payload.macDeviceId,
                macIdentityPublicKey = payload.macIdentityPublicKey,
                relay = payload.relay,
                phoneDeviceId = phoneIdentity.deviceId,
                phoneIdentityPrivateKey = RelaySecureCrypto.serializePhoneIdentityPrivateKey(phoneIdentity.privateKey),
                phoneIdentityPublicKey = phoneIdentity.publicKeyBase64,
                lastPairedAt = now().toEpochMilli(),
                displayName = bridgeDisplayName ?: existing?.displayName,
                lastResolvedSessionId = payload.sessionId,
                lastResolvedAt = now().toEpochMilli(),
                lastUsedAt = now().toEpochMilli(),
            ),
        )
    }

    private fun ensureSent(socket: RelaySocketConnection, text: String) {
        if (!socket.send(text)) {
            throw RelayBootstrapException("The relay connection closed before the secure pairing message could be sent.")
        }
    }

    private fun maskIdentifier(value: String, visibleTail: Int = 8): String {
        return if (value.length <= visibleTail) {
            value
        } else {
            "...${value.takeLast(visibleTail)}"
        }
    }

    private fun logInfo(tag: String, message: String) {
        runCatching { Log.i(tag, message) }
            .getOrElse { println("I/$tag: $message") }
    }

    private fun logError(tag: String, message: String, throwable: Throwable?) {
        runCatching {
            if (throwable == null) {
                Log.e(tag, message)
            } else {
                Log.e(tag, message, throwable)
            }
        }.getOrElse {
            println("E/$tag: $message")
            throwable?.printStackTrace()
        }
    }

    private fun parseJsonOrNull(text: String): JSONObject? {
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun normalizeThreadStartProjectPath(rawValue: String?): String? {
        val trimmed = rawValue?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (trimmed == "/") {
            return trimmed
        }

        var normalized = trimmed
        while (normalized.endsWith("/")) {
            normalized = normalized.dropLast(1)
        }
        return normalized.ifEmpty { "/" }
    }

    private fun decodeStartedThreadSummary(
        result: JSONObject,
        preferredProjectPath: String?,
    ): ThreadSummary? {
        val threadObject = sequenceOf(
            result.optJSONObject("thread"),
            result.optJSONObject("data"),
            result.optJSONObject("item"),
            result.optJSONObject("conversation"),
            result.takeIf { it.has("id") },
        ).firstOrNull()

        decodeLiveThreadSummary(threadObject ?: JSONObject())?.let { decoded ->
            return if (decoded.projectPath == "Unknown project" && preferredProjectPath != null) {
                decoded.copy(projectPath = preferredProjectPath)
            } else {
                decoded
            }
        }

        val fallbackThreadId = sequenceOf(
            result.optString("threadId"),
            result.optString("thread_id"),
            threadObject?.optString("id"),
        ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null

        return ThreadSummary(
            id = fallbackThreadId,
            title = "New Chat",
            projectPath = preferredProjectPath ?: "Unknown project",
            status = ThreadStatus.Waiting,
            preview = "No preview yet.",
            lastUpdatedLabel = formatThreadUpdatedLabel(now()),
        )
    }

    private fun decodeLiveThreadSummary(threadObject: JSONObject): ThreadSummary? {
        val id = readMeaningfulString(threadObject, "id") ?: return null
        val title = readMeaningfulString(threadObject, "name")
            ?: readMeaningfulString(threadObject, "title")
            ?: readMeaningfulString(threadObject, "preview")
            ?: "New Thread"
        val preview = readMeaningfulString(threadObject, "preview")
            ?: "No preview yet."
        val projectPath = readMeaningfulString(threadObject, "cwd")
            ?: readMeaningfulString(threadObject, "current_working_directory")
            ?: readMeaningfulString(threadObject, "working_directory")
            ?: "Unknown project"
        val updatedAt = parseThreadInstant(threadObject, "updatedAt", "updated_at")
            ?: parseThreadInstant(threadObject, "createdAt", "created_at")
            ?: now()
        val status = mergeRealtimeSummaryStatus(
            threadId = id,
            snapshotStatus = parseThreadStatus(threadObject),
        )

        return ThreadSummary(
            id = id,
            title = title,
            projectPath = projectPath,
            status = status,
            preview = preview,
            lastUpdatedLabel = formatThreadUpdatedLabel(updatedAt),
            model = readMeaningfulString(threadObject, "model"),
            modelProvider = readMeaningfulString(threadObject, "modelProvider")
                ?: readMeaningfulString(threadObject, "model_provider")
                ?: readMeaningfulString(threadObject, "modelProviderId")
                ?: readMeaningfulString(threadObject, "model_provider_id"),
            parentThreadId = readMeaningfulString(threadObject, "parentThreadId")
                ?: readMeaningfulString(threadObject, "parent_thread_id"),
        )
    }

    private suspend fun ensureThreadResumed(
        session: ActiveSecureRelaySession,
        threadId: String,
        projectPath: String?,
    ) {
        if (threadId in resumedThreadIds) {
            return
        }
        val requestId = "thread-resume-${UUID.randomUUID()}"
        val params = JSONObject()
            .put("threadId", threadId)
        val normalizedProjectPath = projectPath?.trim().orEmpty()
        if (normalizedProjectPath.isNotEmpty() && normalizedProjectPath != "Unknown project") {
            params.put("cwd", normalizedProjectPath)
        }
        val request = JSONObject()
            .put("id", requestId)
            .put("method", "thread/resume")
            .put("params", params)

        logInfo(
            bootstrapLogTag,
            "thread/resume start session=${maskIdentifier(session.sessionId)} thread=${maskIdentifier(threadId)}",
        )
        sendEncryptedAppPayload(session, request.toString())
        val response = awaitEncryptedRpcResponse(session, requestId)
        response.optJSONObject("error")?.let { error ->
            val message = error.optString("message")
                .ifBlank { "The bridge could not prepare this thread for a live send." }
            if (shouldIgnoreResumeWarmupError(message)) {
                logInfo(
                    bootstrapLogTag,
                    "thread/resume warmup bypass session=${maskIdentifier(session.sessionId)} thread=${maskIdentifier(threadId)} message=$message",
                )
                return
            }
            logError(
                bootstrapLogTag,
                "thread/resume error session=${maskIdentifier(session.sessionId)} thread=${maskIdentifier(threadId)} message=$message",
                null,
            )
            throw RelayBootstrapException(message)
        }
        resumedThreadIds += threadId
        logInfo(
            bootstrapLogTag,
            "thread/resume success session=${maskIdentifier(session.sessionId)} thread=${maskIdentifier(threadId)}",
        )
    }

    private suspend fun readThreadRunState(
        session: ActiveSecureRelaySession,
        threadId: String,
    ): LiveThreadRunState {
        val threadObject = readThreadDetailObject(session, threadId)
        return decodeThreadRunState(
            threadId = threadId,
            threadObject = threadObject,
            turns = threadObject.optJSONArray("turns"),
        )
    }

    private suspend fun readThreadDetailObject(
        session: ActiveSecureRelaySession,
        threadId: String,
    ): JSONObject {
        return try {
            sendThreadReadRequest(
                session = session,
                threadId = threadId,
                useSnakeCaseParams = false,
            )
        } catch (error: RelayBootstrapException) {
            if (!shouldRetryThreadReadWithSnakeCase(error.message.orEmpty())) {
                throw error
            }
            sendThreadReadRequest(
                session = session,
                threadId = threadId,
                useSnakeCaseParams = true,
            )
        }
    }

    private suspend fun sendTurnInterruptRequestWithRetries(
        session: ActiveSecureRelaySession,
        threadId: String,
        turnId: String,
    ): String {
        var currentTurnId = turnId

        try {
            sendTurnInterruptRequest(
                session = session,
                threadId = threadId,
                turnId = currentTurnId,
                useSnakeCaseParams = false,
            )
            return currentTurnId
        } catch (error: RelayBootstrapException) {
            var finalError = error

            if (shouldRetryInterruptWithSnakeCase(error.message.orEmpty())) {
                try {
                    sendTurnInterruptRequest(
                        session = session,
                        threadId = threadId,
                        turnId = currentTurnId,
                        useSnakeCaseParams = true,
                    )
                    return currentTurnId
                } catch (retryError: RelayBootstrapException) {
                    finalError = retryError
                }
            }

            if (shouldRetryInterruptWithRefreshedTurnId(finalError.message.orEmpty())) {
                val refreshedRunState = readThreadRunState(session, threadId)
                val refreshedTurnId = refreshedRunState.activeTurnId
                if (!refreshedTurnId.isNullOrBlank() && refreshedTurnId != currentTurnId) {
                    currentTurnId = refreshedTurnId
                    try {
                        sendTurnInterruptRequest(
                            session = session,
                            threadId = threadId,
                            turnId = currentTurnId,
                            useSnakeCaseParams = false,
                        )
                        return currentTurnId
                    } catch (refreshError: RelayBootstrapException) {
                        finalError = refreshError
                        if (shouldRetryInterruptWithSnakeCase(refreshError.message.orEmpty())) {
                            sendTurnInterruptRequest(
                                session = session,
                                threadId = threadId,
                                turnId = currentTurnId,
                                useSnakeCaseParams = true,
                            )
                            return currentTurnId
                        }
                    }
                }
            }

            throw finalError
        }
    }

    private suspend fun sendTurnStartRequest(
        session: ActiveSecureRelaySession,
        threadId: String,
        userInput: String,
        attachments: List<ImageAttachment>,
        modelIdentifier: String?,
        reasoningEffort: String?,
        serviceTier: String?,
        accessMode: AccessMode,
        collaborationMode: CollaborationModeKind?,
        useSnakeCaseThreadId: Boolean,
        imageUrlKey: String,
    ): JSONObject {
        val sandboxModes = listOf(SandboxSendMode.Modern, SandboxSendMode.Legacy, SandboxSendMode.Minimal)
        var lastError: RelayBootstrapException? = null

        for (sandboxMode in sandboxModes) {
            for ((approvalIndex, approvalPolicy) in accessMode.approvalPolicyCandidates.withIndex()) {
                val requestId = "turn-start-${UUID.randomUUID()}"
                val params = buildTurnStartParams(
                    threadId = threadId,
                    userInput = userInput,
                    attachments = attachments,
                    modelIdentifier = modelIdentifier,
                    reasoningEffort = reasoningEffort,
                    serviceTier = serviceTier,
                    accessMode = accessMode,
                    collaborationMode = collaborationMode,
                    approvalPolicy = approvalPolicy,
                    sandboxMode = sandboxMode,
                    useSnakeCaseThreadId = useSnakeCaseThreadId,
                    imageUrlKey = imageUrlKey,
                )
                val request = JSONObject()
                    .put("id", requestId)
                    .put("method", "turn/start")
                    .put("params", params)

                sendEncryptedAppPayload(session, request.toString())
                val response = awaitEncryptedRpcResponse(session, requestId)
                val error = response.optJSONObject("error")
                if (error == null) {
                    return response
                }

                val message = error.optString("message")
                    .ifBlank { "The bridge rejected the live send request." }
                lastError = RelayBootstrapException(message)
                val canRetryApproval = approvalIndex < accessMode.approvalPolicyCandidates.lastIndex
                if (canRetryApproval && shouldRetryWithApprovalPolicyFallback(message)) {
                    continue
                }
                if (sandboxMode != sandboxModes.last() && shouldFallbackFromSandboxPolicy(message)) {
                    break
                }
                logError(
                    bootstrapLogTag,
                    "turn/start error session=${maskIdentifier(session.sessionId)} thread=${maskIdentifier(threadId)} message=$message",
                    null,
                )
                throw lastError
            }
        }

        throw lastError ?: RelayBootstrapException("The bridge rejected the live send request.")
    }

    private fun buildTurnStartParams(
        threadId: String,
        userInput: String,
        attachments: List<ImageAttachment>,
        modelIdentifier: String?,
        reasoningEffort: String?,
        serviceTier: String?,
        accessMode: AccessMode,
        collaborationMode: CollaborationModeKind?,
        approvalPolicy: String,
        sandboxMode: SandboxSendMode,
        useSnakeCaseThreadId: Boolean,
        imageUrlKey: String,
    ): JSONObject {
        val params = JSONObject()
            .put(
                if (useSnakeCaseThreadId) "thread_id" else "threadId",
                threadId,
            )
            .put("input", buildTurnInputPayload(userInput, attachments, imageUrlKey))

        readMeaningfulString(modelIdentifier)?.let { params.put("model", it) }
        readMeaningfulString(reasoningEffort)?.let { params.put("effort", it) }
        readMeaningfulString(serviceTier)?.let { params.put("serviceTier", it) }
        collaborationMode?.let { mode ->
            params.put(
                "collaborationMode",
                JSONObject()
                    .put("mode", mode.wireValue)
                    .put(
                        "settings",
                        JSONObject()
                            .put("model", readMeaningfulString(modelIdentifier) ?: JSONObject.NULL)
                            .put("reasoning_effort", readMeaningfulString(reasoningEffort) ?: JSONObject.NULL)
                            .put("developer_instructions", JSONObject.NULL),
                    ),
            )
        }
        params.put("approvalPolicy", approvalPolicy)

        when (sandboxMode) {
            SandboxSendMode.Modern -> params.put("sandboxPolicy", runtimeSandboxPolicyObject(accessMode))
            SandboxSendMode.Legacy -> params.put("sandbox", accessMode.sandboxLegacyValue)
            SandboxSendMode.Minimal -> Unit
        }

        return params
    }

    private suspend fun sendTurnInterruptRequest(
        session: ActiveSecureRelaySession,
        threadId: String,
        turnId: String,
        useSnakeCaseParams: Boolean,
    ) {
        val requestId = "turn-interrupt-${UUID.randomUUID()}"
        val params = JSONObject()
            .put(
                if (useSnakeCaseParams) "turn_id" else "turnId",
                turnId,
            )
            .put(
                if (useSnakeCaseParams) "thread_id" else "threadId",
                threadId,
            )
        val request = JSONObject()
            .put("id", requestId)
            .put("method", "turn/interrupt")
            .put("params", params)

        sendEncryptedAppPayload(session, request.toString())
        val response = awaitEncryptedRpcResponse(session, requestId)
        response.optJSONObject("error")?.let { error ->
            val message = error.optString("message")
                .ifBlank { "The bridge rejected the stop request." }
            logError(
                bootstrapLogTag,
                "turn/interrupt error session=${maskIdentifier(session.sessionId)} thread=${maskIdentifier(threadId)} turn=${maskIdentifier(turnId)} message=$message",
                null,
            )
            throw RelayBootstrapException(message)
        }
    }

    private suspend fun sendThreadReadRequest(
        session: ActiveSecureRelaySession,
        threadId: String,
        useSnakeCaseParams: Boolean,
    ): JSONObject {
        val requestId = "thread-read-${UUID.randomUUID()}"
        val params = JSONObject()
            .put(
                if (useSnakeCaseParams) "thread_id" else "threadId",
                threadId,
            )
            .put(
                if (useSnakeCaseParams) "include_turns" else "includeTurns",
                true,
            )
        val request = JSONObject()
            .put("id", requestId)
            .put("method", "thread/read")
            .put("params", params)

        sendEncryptedAppPayload(session, request.toString())
        val response = awaitEncryptedRpcResponse(session, requestId)
        response.optJSONObject("error")?.let { error ->
            val message = error.optString("message")
                .ifBlank { "The bridge rejected the live thread detail request." }
            logError(
                bootstrapLogTag,
                "thread/read error session=${maskIdentifier(session.sessionId)} thread=${maskIdentifier(threadId)} message=$message",
                null,
            )
            throw RelayBootstrapException(message)
        }

        val result = response.optJSONObject("result")
            ?: throw RelayBootstrapException("The bridge returned an invalid live thread detail response.")
        return result.optJSONObject("thread")
            ?: throw RelayBootstrapException("The bridge returned a thread detail response without a thread payload.")
    }

    private fun shouldRetryThreadReadWithSnakeCase(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        val hints = listOf(
            "turnid",
            "threadid",
            "turn_id",
            "thread_id",
            "unknown field",
            "missing field",
            "invalid",
        )
        return hints.any(normalized::contains)
    }

    private fun shouldRetryTurnStartWithSnakeCase(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        val hints = listOf(
            "threadid",
            "thread_id",
            "unknown field",
            "missing field",
            "invalid",
        )
        return hints.any(normalized::contains)
    }

    private fun shouldRetryTurnStartWithImageUrlField(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        if (!normalized.contains("image_url")) {
            return false
        }
        return normalized.contains("missing")
            || normalized.contains("unknown field")
            || normalized.contains("expected")
            || normalized.contains("invalid")
    }

    private fun shouldIgnoreResumeWarmupError(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return normalized.contains("not materialized")
            || normalized.contains("not yet materialized")
            || normalized.contains("no rollout found")
    }

    private fun shouldRetryInterruptWithSnakeCase(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        val hints = listOf(
            "turnid",
            "threadid",
            "turn_id",
            "thread_id",
            "unknown field",
            "missing field",
            "invalid",
        )
        return hints.any(normalized::contains)
    }

    private fun shouldRetryInterruptWithRefreshedTurnId(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        val hints = listOf(
            "turn not found",
            "no active turn",
            "not in progress",
            "not running",
            "already finished",
            "already completed",
            "already stopped",
            "interruptible",
        )
        return hints.any(normalized::contains)
    }

    private fun buildTurnInputPayload(
        userInput: String,
        attachments: List<ImageAttachment>,
        imageUrlKey: String,
    ): org.json.JSONArray {
        val payload = org.json.JSONArray()
        attachments.forEach { attachment ->
            resolveOutgoingImagePayload(attachment)?.let { imagePayload ->
                payload.put(
                    JSONObject()
                        .put("type", "image")
                        .put(imageUrlKey, imagePayload),
                )
            }
        }

        val trimmedInput = userInput.trim()
        if (trimmedInput.isNotEmpty()) {
            payload.put(
                JSONObject()
                    .put("type", "text")
                    .put("text", trimmedInput),
            )
        }
        return payload
    }

    private fun resolveOutgoingImagePayload(attachment: ImageAttachment): String? {
        val payloadDataUrl = attachment.payloadDataUrl?.trim().orEmpty()
        if (payloadDataUrl.isNotEmpty()) {
            return payloadDataUrl
        }

        val fallbackUri = attachment.uri.trim()
        if (fallbackUri.startsWith("data:image", ignoreCase = true)) {
            return fallbackUri
        }
        if (fallbackUri.startsWith("https://", ignoreCase = true)
            || fallbackUri.startsWith("http://", ignoreCase = true)
        ) {
            return fallbackUri
        }
        return null
    }

    private fun extractTurnId(result: JSONObject?): String? {
        val fromTurnObject = result?.optJSONObject("turn")?.optString("id")?.trim()
        if (!fromTurnObject.isNullOrEmpty()) {
            return fromTurnObject
        }

        val fromTopLevel = sequenceOf(
            result?.optString("turnId")?.trim(),
            result?.optString("turn_id")?.trim(),
            result?.optString("id")?.trim(),
        ).firstOrNull { !it.isNullOrEmpty() }
        return fromTopLevel
    }

    private fun decodeLiveThreadDetail(threadObject: JSONObject): ThreadDetail {
        val threadId = threadObject.optString("id").trim()
        val source = threadObject.optString("source").trim()
        val cwd = sequenceOf(
            threadObject.optString("cwd").trim(),
            threadObject.optString("current_working_directory").trim(),
            threadObject.optString("working_directory").trim(),
        ).firstOrNull { it.isNotEmpty() }.orEmpty()
        val turns = threadObject.optJSONArray("turns")
        val runState = decodeThreadRunState(
            threadId = threadId,
            threadObject = threadObject,
            turns = turns,
        )
        val entries = decodeTimelineEntries(threadId = threadId, threadObject = threadObject, turns = turns)
        val subtitle = when {
            cwd.isNotEmpty() && source.isNotEmpty() -> "$source • $cwd"
            cwd.isNotEmpty() -> cwd
            source.isNotEmpty() -> source
            else -> "Live thread detail"
        }
        val statePrefix = threadStatePresentationLabel(runState.status)
        val stateLabel = when {
            runState.activeTurnId != null -> "$statePrefix • active turn ready"
            runState.hasInterruptibleTurnWithoutId -> "$statePrefix • waiting for active turn id"
            entries.isEmpty() -> "$statePrefix • no timeline items yet"
            else -> "$statePrefix • loaded ${entries.size} timeline entries"
        }

        return ThreadDetail(
            threadId = threadId,
            subtitle = subtitle,
            stateLabel = stateLabel,
            entries = if (entries.isEmpty()) {
                listOf(
                    TimelineEntry(
                        id = "${threadId}-empty",
                        speaker = "System",
                        timestampLabel = formatTimelineTimestampLabel(
                            parseThreadInstant(threadObject, "updatedAt", "updated_at", "createdAt", "created_at")
                                ?: now(),
                        ),
                        body = "The live thread exists on the bridge, but it does not have timeline items yet.",
                    ),
                )
            } else {
                entries
            },
            status = runState.status,
            activeTurnId = runState.activeTurnId,
            latestTurnId = runState.latestTurnId,
            hasInterruptibleTurnWithoutId = runState.hasInterruptibleTurnWithoutId,
        )
    }

    private fun decodeThreadRunState(
        threadId: String,
        threadObject: JSONObject,
        turns: org.json.JSONArray?,
    ): LiveThreadRunState {
        val fallbackStatus = parseThreadStatus(threadObject)
        var latestTurnId: String? = null
        var latestTurnStatus: String? = null
        var activeTurnId: String? = null
        var hasInterruptibleTurnWithoutId = false
        var sawExplicitTurnStatus = false

        for (turnIndex in (turns?.length() ?: 0) - 1 downTo 0) {
            val turnObject = turns?.optJSONObject(turnIndex) ?: continue
            val turnId = extractTurnIdentifier(turnObject)
            if (latestTurnId == null && !turnId.isNullOrBlank()) {
                latestTurnId = turnId
            }

            val normalizedStatus = normalizeInterruptTurnStatus(turnObject)
            if (normalizedStatus != null) {
                sawExplicitTurnStatus = true
                if (latestTurnStatus == null) {
                    latestTurnStatus = normalizedStatus
                }
            }

            if (normalizedStatus == null || !isInterruptibleTurnStatus(normalizedStatus)) {
                continue
            }

            if (!turnId.isNullOrBlank()) {
                activeTurnId = turnId
                break
            }

            hasInterruptibleTurnWithoutId = true
            break
        }

        if (
            activeTurnId == null
            && !hasInterruptibleTurnWithoutId
            && !sawExplicitTurnStatus
            && fallbackStatus == ThreadStatus.Running
        ) {
            activeTurnId = latestTurnId
        }

        val effectiveStatus = when {
            activeTurnId != null || hasInterruptibleTurnWithoutId -> ThreadStatus.Running
            latestTurnStatus != null -> parseTurnLifecycleStatus(latestTurnStatus)
            else -> fallbackStatus
        }

        return mergeRealtimeRunningState(
            LiveThreadRunState(
                threadId = threadId,
                status = effectiveStatus,
                activeTurnId = activeTurnId,
                latestTurnId = latestTurnId,
                hasInterruptibleTurnWithoutId = hasInterruptibleTurnWithoutId,
            ),
        )
    }

    private fun mergeRealtimeSummaryStatus(
        threadId: String,
        snapshotStatus: ThreadStatus,
    ): ThreadStatus {
        val runtimeState = liveThreadRuntimeStates[threadId] ?: return snapshotStatus
        return if (runtimeState.isRunning) {
            ThreadStatus.Running
        } else {
            snapshotStatus
        }
    }

    private fun mergeRealtimeRunningState(
        snapshot: LiveThreadRunState,
    ): LiveThreadRunState {
        val runtimeState = liveThreadRuntimeStates[snapshot.threadId] ?: return snapshot
        if (!runtimeState.isRunning) {
            return snapshot
        }

        return snapshot.copy(
            status = ThreadStatus.Running,
            activeTurnId = snapshot.activeTurnId ?: runtimeState.activeTurnId,
            latestTurnId = snapshot.latestTurnId ?: runtimeState.latestTurnId,
            hasInterruptibleTurnWithoutId = snapshot.hasInterruptibleTurnWithoutId
                || runtimeState.hasInterruptibleTurnWithoutId,
        )
    }

    private fun decodeTimelineEntries(
        threadId: String,
        threadObject: JSONObject,
        turns: org.json.JSONArray?,
    ): List<TimelineEntry> {
        val baseInstant = parseThreadInstant(threadObject, "createdAt", "created_at", "updatedAt", "updated_at")
            ?: now()
        val timelineEntries = mutableListOf<TimelineEntry>()
        var syntheticOffsetMs = 0L

        for (turnIndex in 0 until (turns?.length() ?: 0)) {
            val turnObject = turns?.optJSONObject(turnIndex) ?: continue
            val turnInstant = parseThreadInstant(turnObject, "createdAt", "created_at", "updatedAt", "updated_at")
                ?: baseInstant
            val items = turnObject.optJSONArray("items")

            for (itemIndex in 0 until (items?.length() ?: 0)) {
                val itemObject = items?.optJSONObject(itemIndex) ?: continue
                val normalizedType = normalizeTimelineItemType(itemObject.optString("type"))
                val entryKind = decodeTimelineEntryKind(normalizedType)
                val body = decodeTimelineBody(itemObject, normalizedType, entryKind)
                val planSteps = if (entryKind == TimelineEntryKind.Plan) {
                    decodeTimelinePlanSteps(itemObject)
                } else {
                    emptyList()
                }
                val attachments = decodeTimelineAttachments(itemObject)
                if (body.isBlank() && planSteps.isEmpty() && attachments.isEmpty()) {
                    continue
                }
                val itemInstant = parseThreadInstant(itemObject, "createdAt", "created_at", "updatedAt", "updated_at")
                    ?: turnInstant.plusMillis(syntheticOffsetMs)
                syntheticOffsetMs += 1

                timelineEntries += TimelineEntry(
                    id = itemObject.optString("id").ifBlank { "$threadId-$turnIndex-$itemIndex" },
                    speaker = decodeTimelineSpeaker(itemObject, normalizedType),
                    timestampLabel = formatTimelineTimestampLabel(itemInstant),
                    body = body,
                    kind = entryKind,
                    title = decodeTimelineTitle(itemObject, entryKind),
                    detail = decodeTimelineDetail(itemObject, entryKind),
                    planSteps = planSteps,
                    attachments = attachments,
                )
            }
        }

        return timelineEntries
    }

    private fun decodeTimelineEntryKind(normalizedType: String): TimelineEntryKind {
        return when {
            normalizedType == "usermessage"
                || normalizedType == "agentmessage"
                || normalizedType == "assistantmessage"
                || normalizedType == "message" -> TimelineEntryKind.Chat

            normalizedType == "reasoning" -> TimelineEntryKind.Thinking
            normalizedType == "toolcall" -> TimelineEntryKind.ToolActivity
            normalizedType == "filechange"
                || normalizedType == "diff" -> TimelineEntryKind.FileChange
            normalizedType == "commandexecution" -> TimelineEntryKind.CommandExecution
            normalizedType == "plan" -> TimelineEntryKind.Plan
            normalizedType.startsWith("collab")
                || normalizedType == "subagentaction" -> TimelineEntryKind.SubagentAction
            else -> TimelineEntryKind.System
        }
    }

    private fun decodeTimelineBody(
        itemObject: JSONObject,
        normalizedType: String,
        entryKind: TimelineEntryKind,
    ): String {
        val directText = decodeTimelineText(itemObject)
        return when (entryKind) {
            TimelineEntryKind.Chat -> directText
            TimelineEntryKind.Thinking -> directText.ifBlank { "Reasoning step recorded." }
            TimelineEntryKind.FileChange -> directText.ifBlank { "File changes were recorded in this turn." }
            TimelineEntryKind.ToolActivity -> directText.ifBlank { decodeTimelineToolSummary(itemObject) }
            TimelineEntryKind.CommandExecution -> directText.ifBlank {
                decodeTimelineCommandStatus(itemObject) ?: "A command ran during this turn."
            }

            TimelineEntryKind.Plan -> directText.ifBlank { decodeTimelinePlanExplanation(itemObject) ?: "The plan was updated." }
            TimelineEntryKind.SubagentAction -> directText.ifBlank { decodeSubagentSummaryText(itemObject, normalizedType) }
            TimelineEntryKind.System -> when (normalizedType) {
                "contextcompaction" -> "Context compacted."
                "enteredreviewmode" -> {
                    val reviewLabel = listOf(
                        itemObject.optString("review"),
                        itemObject.optJSONObject("data")?.optString("review").orEmpty(),
                    ).firstOrNull { it.isNotBlank() }?.trim() ?: "changes"
                    directText.ifBlank { "Reviewing $reviewLabel..." }
                }
                "exitedreviewmode" -> {
                    val reviewText = listOf(
                        itemObject.optString("review"),
                        itemObject.optJSONObject("data")?.optString("review").orEmpty(),
                    ).firstOrNull { it.isNotBlank() }?.trim()
                    directText.ifBlank { reviewText ?: "Review mode finished." }
                }
                else -> directText
            }
        }
    }

    private fun decodeTimelineTitle(
        itemObject: JSONObject,
        entryKind: TimelineEntryKind,
    ): String? {
        return when (entryKind) {
            TimelineEntryKind.Thinking -> "Thinking"
            TimelineEntryKind.ToolActivity -> decodeTimelineToolName(itemObject) ?: "Tool"
            TimelineEntryKind.FileChange -> "File change"
            TimelineEntryKind.CommandExecution -> decodeTimelineCommandTitle(itemObject) ?: "Command"
            TimelineEntryKind.Plan -> "Plan"
            TimelineEntryKind.SubagentAction -> decodeSubagentTitle(itemObject)
            else -> null
        }
    }

    private fun decodeTimelineDetail(
        itemObject: JSONObject,
        entryKind: TimelineEntryKind,
    ): String? {
        return when (entryKind) {
            TimelineEntryKind.ToolActivity -> decodeTimelineToolSummary(itemObject)
                .takeIf { it.isNotBlank() && it != decodeTimelineTitle(itemObject, entryKind) }

            TimelineEntryKind.CommandExecution -> decodeTimelineCommandStatus(itemObject)
            else -> null
        }
    }

    private fun decodeTimelineSpeaker(itemObject: JSONObject, normalizedType: String): String {
        return when (normalizedType) {
            "usermessage" -> "You"
            "agentmessage", "assistantmessage" -> "Codex"
            "message" -> {
                val role = itemObject.optString("role").lowercase(Locale.US)
                if (role.contains("user")) "You" else "Codex"
            }

            "reasoning" -> "Thinking"
            "filechange", "diff" -> "File change"
            "toolcall" -> "Tool"
            "commandexecution" -> "Command"
            "plan" -> "Plan"
            "exitedreviewmode" -> "Codex"
            else -> if (normalizedType.startsWith("collab")) "Subagent" else "System"
        }
    }

    private fun decodeTimelineText(itemObject: JSONObject): String {
        val parts = mutableListOf<String>()
        val contentItems = itemObject.optJSONArray("content")

        for (index in 0 until (contentItems?.length() ?: 0)) {
            val contentObject = contentItems?.optJSONObject(index) ?: continue
            val normalizedType = normalizeTimelineItemType(contentObject.optString("type"))
            val text = when (normalizedType) {
                "text", "inputtext", "outputtext", "message" -> {
                    contentObject.optString("text").ifBlank {
                        contentObject.optJSONObject("data")?.optString("text").orEmpty()
                    }
                }

                "skill" -> {
                    contentObject.optString("id")
                        .ifBlank { contentObject.optString("name") }
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { "\$$it" }
                        .orEmpty()
                }

                else -> ""
            }

            if (text.isNotBlank()) {
                parts += text.trim()
            }
        }

        if (parts.isNotEmpty()) {
            return parts.joinToString(separator = "\n").trim()
        }

        val fallbackKeys = listOf(
            "text",
            "message",
            "summary",
            "stdout",
            "stderr",
            "output_text",
            "outputText",
        )
        fallbackKeys.firstNotNullOfOrNull { key ->
            itemObject.optString(key).trim().takeIf { it.isNotEmpty() }
        }?.let { return it }

        itemObject.optJSONObject("data")?.optString("text")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return ""
    }

    private fun decodeTimelineAttachments(itemObject: JSONObject): List<ImageAttachment> {
        val contentItems = itemObject.optJSONArray("content")
        if (contentItems == null || contentItems.length() == 0) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until contentItems.length()) {
                val contentObject = contentItems.optJSONObject(index) ?: continue
                val normalizedType = normalizeTimelineItemType(contentObject.optString("type"))
                if (normalizedType != "image" && normalizedType != "localimage") {
                    continue
                }
                val imageCandidates = listOf(
                    contentObject.optString("imageUrl"),
                    contentObject.optString("image_url"),
                    contentObject.optString("url"),
                    contentObject.optString("path"),
                    contentObject.optJSONObject("data")?.optString("path").orEmpty(),
                    contentObject.optJSONObject("data")?.optString("url").orEmpty(),
                    contentObject.optJSONObject("data")?.optString("imageUrl").orEmpty(),
                    contentObject.optJSONObject("data")?.optString("image_url").orEmpty(),
                ).mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                val imageUri = imageCandidates.firstOrNull() ?: continue
                val payloadDataUrl = imageCandidates.firstOrNull {
                    it.startsWith("data:image", ignoreCase = true)
                }
                val explicitThumbnailUri = listOf(
                    contentObject.optString("thumbnailUrl"),
                    contentObject.optString("thumbnail_url"),
                    contentObject.optString("thumbnail"),
                    contentObject.optJSONObject("data")?.optString("thumbnailUrl").orEmpty(),
                    contentObject.optJSONObject("data")?.optString("thumbnail_url").orEmpty(),
                    contentObject.optJSONObject("data")?.optString("thumbnail").orEmpty(),
                ).mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                    .firstOrNull()
                // If no explicit thumbnail was provided by the relay but we have a
                // data URI payload, generate a small JPEG thumbnail from it — this
                // mirrors iOS's makeThumbnailBase64JPEG() and ensures old-conversation
                // images always have a renderable thumbnail.
                val thumbnailUri = explicitThumbnailUri
                    ?: payloadDataUrl?.let { generateThumbnailFromDataUri(it) }
                add(
                    ImageAttachment(
                        id = contentObject.optString("id").ifBlank { "timeline-image-$index" },
                        uri = imageUri,
                        thumbnailUri = thumbnailUri,
                        payloadDataUrl = payloadDataUrl,
                    ),
                )
            }
        }
    }

    private fun decodeTimelineToolSummary(itemObject: JSONObject): String {
        val name = decodeTimelineToolName(itemObject)
        return name?.let { "Tool activity: $it" } ?: "Tool activity recorded."
    }

    private fun decodeTimelineToolName(itemObject: JSONObject): String? {
        return listOf(
            itemObject.optString("toolName"),
            itemObject.optString("tool_name"),
            itemObject.optString("name"),
            itemObject.optString("action"),
        ).firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun decodeSubagentSummaryText(itemObject: JSONObject, normalizedType: String): String {
        val tool = listOf(
            itemObject.optString("tool"),
            itemObject.optString("toolName"),
            itemObject.optString("tool_name"),
        ).firstOrNull { it.isNotBlank() }?.trim()

        val status = listOf(
            itemObject.optString("status"),
            itemObject.optJSONObject("data")?.optString("status").orEmpty(),
        ).firstOrNull { it.isNotBlank() }?.trim()

        val prompt = listOf(
            itemObject.optString("prompt"),
            itemObject.optString("message"),
            itemObject.optJSONObject("data")?.optString("prompt").orEmpty(),
        ).firstOrNull { it.isNotBlank() }?.trim()

        val parts = mutableListOf<String>()
        if (tool != null) parts += tool
        if (status != null) parts += "($status)"
        if (prompt != null) parts += prompt.take(120)

        if (parts.isEmpty()) {
            return when {
                normalizedType.contains("spawn") -> "Spawning sub-agent..."
                normalizedType.contains("waiting") -> "Waiting for sub-agent..."
                normalizedType.contains("close") -> "Sub-agent finished."
                normalizedType.contains("resume") -> "Resuming sub-agent..."
                else -> "Sub-agent activity"
            }
        }
        return parts.joinToString(separator = " ")
    }

    private fun decodeSubagentTitle(itemObject: JSONObject): String {
        val tool = listOf(
            itemObject.optString("tool"),
            itemObject.optString("toolName"),
            itemObject.optString("tool_name"),
        ).firstOrNull { it.isNotBlank() }?.trim()
        return tool ?: "Sub-agent"
    }

    private fun decodeTimelineCommandTitle(itemObject: JSONObject): String? {
        return listOf(
            itemObject.optString("command"),
            itemObject.optString("title"),
            itemObject.optString("name"),
        ).firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun decodeTimelineCommandStatus(itemObject: JSONObject): String? {
        return listOf(
            itemObject.optString("summary"),
            itemObject.optString("statusText"),
            itemObject.optString("status_text"),
            itemObject.optString("state"),
        ).firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun decodeTimelinePlanExplanation(itemObject: JSONObject): String? {
        return listOf(
            itemObject.optString("explanation"),
            itemObject.optString("summary"),
            itemObject.optString("text"),
        ).firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun decodeTimelinePlanSteps(itemObject: JSONObject): List<TimelinePlanStep> {
        val rawSteps = itemObject.optJSONArray("plan") ?: itemObject.optJSONArray("steps") ?: return emptyList()
        return buildList {
            for (index in 0 until rawSteps.length()) {
                val stepObject = rawSteps.optJSONObject(index) ?: continue
                val text = listOf(
                    stepObject.optString("step"),
                    stepObject.optString("title"),
                    stepObject.optString("text"),
                ).firstOrNull { it.isNotBlank() }?.trim() ?: continue
                val status = stepObject.optString("status").trim().takeIf(String::isNotEmpty)
                add(TimelinePlanStep(text = text, status = status))
            }
        }
    }

    private fun readMeaningfulString(
        container: JSONObject,
        key: String,
    ): String? {
        if (!container.has(key) || container.isNull(key)) {
            return null
        }
        return readMeaningfulString(container.optString(key, ""))
    }

    private fun readMeaningfulString(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        if (trimmed.equals("null", ignoreCase = true)) {
            return null
        }
        if (trimmed.equals("undefined", ignoreCase = true)) {
            return null
        }
        return trimmed
    }

    private fun normalizeTimelineItemType(value: String): String {
        return value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]"), "")
    }

    private fun formatTimelineTimestampLabel(instant: Instant): String {
        val zoneId = ZoneId.systemDefault()
        val zonedInstant = instant.atZone(zoneId)
        val currentZonedInstant = now().atZone(zoneId)
        return if (zonedInstant.toLocalDate() == currentZonedInstant.toLocalDate()) {
            timelineTimeFormatter.format(zonedInstant)
        } else {
            threadDateFormatter.format(zonedInstant)
        }
    }

    private fun runtimeSandboxPolicyObject(accessMode: AccessMode): JSONObject {
        return when (accessMode) {
            AccessMode.OnRequest -> JSONObject()
                .put("type", "workspaceWrite")
                .put("networkAccess", true)

            AccessMode.FullAccess -> JSONObject()
                .put("type", "dangerFullAccess")
        }
    }

    private fun shouldRetryWithApprovalPolicyFallback(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return normalized.contains("approval")
            || normalized.contains("unknown variant")
            || normalized.contains("expected one of")
            || normalized.contains("onrequest")
            || normalized.contains("on-request")
    }

    private fun shouldFallbackFromSandboxPolicy(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return normalized.contains("invalid params")
            || normalized.contains("invalid param")
            || normalized.contains("unknown field")
            || normalized.contains("unexpected field")
            || normalized.contains("unrecognized field")
            || normalized.contains("failed to parse")
            || normalized.contains("unsupported")
            || normalized.contains("sandbox")
            || normalized.contains("sandboxpolicy")
    }

    private enum class SandboxSendMode {
        Modern,
        Legacy,
        Minimal,
    }

    private fun parseThreadStatus(threadObject: JSONObject): ThreadStatus {
        val statusValue = extractStatusValue(
            container = threadObject,
            keys = listOf("status", "state"),
        ).orEmpty()

        return when {
            statusValue.contains("running") || statusValue.contains("in_progress") -> ThreadStatus.Running
            statusValue.contains("fail") || statusValue.contains("error") -> ThreadStatus.Failed
            statusValue.contains("complete") || statusValue.contains("done") || statusValue.contains("success") -> ThreadStatus.Completed
            else -> ThreadStatus.Waiting
        }
    }

    private fun threadStatePresentationLabel(status: ThreadStatus): String {
        return when (status) {
            ThreadStatus.Running -> "Running"
            ThreadStatus.Failed -> "Failed"
            ThreadStatus.Waiting,
            ThreadStatus.Completed -> "Ready"
        }
    }

    private fun parseTurnLifecycleStatus(normalizedStatus: String): ThreadStatus {
        return when {
            normalizedStatus.contains("running")
                || normalizedStatus.contains("inprogress")
                || normalizedStatus.contains("pending")
                || normalizedStatus.contains("started") -> ThreadStatus.Running

            normalizedStatus.contains("fail")
                || normalizedStatus.contains("error") -> ThreadStatus.Failed

            normalizedStatus.contains("complete")
                || normalizedStatus.contains("done")
                || normalizedStatus.contains("success") -> ThreadStatus.Completed

            else -> ThreadStatus.Waiting
        }
    }

    private fun extractTurnIdentifier(turnObject: JSONObject): String? {
        return sequenceOf(
            turnObject.optString("id"),
            turnObject.optString("turnId"),
            turnObject.optString("turn_id"),
        ).firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun normalizeInterruptTurnStatus(turnObject: JSONObject): String? {
        return extractStatusValue(
            container = turnObject,
            keys = listOf("status", "turnStatus", "turn_status"),
        )?.replace("_", "")
            ?.replace("-", "")
            ?.lowercase(Locale.US)
    }

    private fun extractStatusValue(
        container: JSONObject,
        keys: List<String>,
    ): String? {
        for (key in keys) {
            if (!container.has(key)) {
                continue
            }
            val rawValue = container.opt(key)
            when (rawValue) {
                is String -> {
                    val trimmed = rawValue.trim()
                    if (trimmed.isNotEmpty()) {
                        return trimmed.lowercase(Locale.US)
                    }
                }

                is JSONObject -> {
                    val nested = sequenceOf(
                        rawValue.optString("type"),
                        rawValue.optString("statusType"),
                        rawValue.optString("status_type"),
                        rawValue.optString("status"),
                        rawValue.optString("state"),
                    ).firstOrNull { it.isNotBlank() }?.trim()
                    if (!nested.isNullOrEmpty()) {
                        return nested.lowercase(Locale.US)
                    }
                }
            }
        }
        return null
    }

    private fun clearLiveThreadRuntimeState() {
        liveThreadRuntimeStates.clear()
        threadIdByTurnId.clear()
        resumedThreadIds.clear()
    }

    private fun trackIncomingRuntimeNotification(payloadObject: JSONObject) {
        val method = payloadObject.optString("method").trim()
        if (method.isEmpty()) {
            return
        }
        val params = payloadObject.optJSONObject("params") ?: JSONObject()

        when (method) {
            "turn/started" -> {
                val threadId = extractNotificationThreadId(params) ?: return
                val turnId = extractNotificationTurnId(params)
                val previousState = liveThreadRuntimeStates[threadId]
                val hasInterruptibleTurnWithoutId = previousState?.hasInterruptibleTurnWithoutId == true
                    || turnId.isNullOrBlank()
                liveThreadRuntimeStates[threadId] = LiveThreadRunState(
                    threadId = threadId,
                    status = ThreadStatus.Running,
                    activeTurnId = turnId,
                    latestTurnId = turnId ?: previousState?.latestTurnId,
                    hasInterruptibleTurnWithoutId = hasInterruptibleTurnWithoutId,
                )
                if (!turnId.isNullOrBlank()) {
                    threadIdByTurnId[turnId] = threadId
                }
            }

            "turn/completed" -> {
                val turnId = extractNotificationTurnId(params)
                val threadId = extractNotificationThreadId(params)
                    ?: turnId?.let(threadIdByTurnId::get)
                    ?: return
                if (!turnId.isNullOrBlank()) {
                    threadIdByTurnId[turnId] = threadId
                }
                val previousState = liveThreadRuntimeStates[threadId]
                liveThreadRuntimeStates[threadId] = LiveThreadRunState(
                    threadId = threadId,
                    status = notificationTerminalStatus(params),
                    activeTurnId = null,
                    latestTurnId = turnId ?: previousState?.latestTurnId,
                    hasInterruptibleTurnWithoutId = false,
                )
            }

            "thread/status/changed",
            "thread/status",
            "codex/event/thread_status_changed" -> {
                val threadId = extractNotificationThreadId(params) ?: return
                val normalizedStatus = extractNotificationStatus(params) ?: return
                val previousState = liveThreadRuntimeStates[threadId]
                when {
                    isRunningNotificationStatus(normalizedStatus) -> {
                        liveThreadRuntimeStates[threadId] = LiveThreadRunState(
                            threadId = threadId,
                            status = ThreadStatus.Running,
                            activeTurnId = previousState?.activeTurnId,
                            latestTurnId = previousState?.latestTurnId,
                            hasInterruptibleTurnWithoutId = previousState?.hasInterruptibleTurnWithoutId == true,
                        )
                    }

                    isTerminalNotificationStatus(normalizedStatus) -> {
                        liveThreadRuntimeStates[threadId] = LiveThreadRunState(
                            threadId = threadId,
                            status = notificationTerminalStatus(params, normalizedStatus),
                            activeTurnId = null,
                            latestTurnId = previousState?.latestTurnId,
                            hasInterruptibleTurnWithoutId = false,
                        )
                    }
                }
            }
        }

        parseRuntimeUiEvent(method = method, params = params)?.let { event ->
            runtimeEventListener?.invoke(event)
        }
    }

    private fun extractNotificationThreadId(params: JSONObject): String? {
        val eventObject = params.optJSONObject("event")
        return sequenceOf(
            params.optString("threadId"),
            params.optString("thread_id"),
            params.optString("conversationId"),
            params.optString("conversation_id"),
            params.optJSONObject("thread")?.optString("id"),
            params.optJSONObject("turn")?.optString("threadId"),
            params.optJSONObject("turn")?.optString("thread_id"),
            params.optJSONObject("item")?.optString("threadId"),
            params.optJSONObject("item")?.optString("thread_id"),
            eventObject?.optString("threadId"),
            eventObject?.optString("thread_id"),
            eventObject?.optString("conversationId"),
            eventObject?.optString("conversation_id"),
            eventObject?.optJSONObject("thread")?.optString("id"),
            eventObject?.optJSONObject("turn")?.optString("threadId"),
            eventObject?.optJSONObject("turn")?.optString("thread_id"),
            eventObject?.optJSONObject("item")?.optString("threadId"),
            eventObject?.optJSONObject("item")?.optString("thread_id"),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun extractNotificationTurnId(params: JSONObject): String? {
        val turnObject = params.optJSONObject("turn")
        val itemObject = params.optJSONObject("item")
        val eventObject = params.optJSONObject("event")
        return sequenceOf(
            turnObject?.optString("id"),
            params.optString("turnId"),
            params.optString("turn_id"),
            itemObject?.optString("turnId"),
            itemObject?.optString("turn_id"),
            eventObject?.optString("turnId"),
            eventObject?.optString("turn_id"),
            eventObject?.optJSONObject("turn")?.optString("id"),
            eventObject?.optJSONObject("turn")?.optString("turnId"),
            eventObject?.optJSONObject("turn")?.optString("turn_id"),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun parseRuntimeUiEvent(
        method: String,
        params: JSONObject,
    ): LiveThreadRuntimeEvent? {
        val eventObject = params.optJSONObject("event")
        return when (method) {
            "thread/started",
            "thread/name/updated" -> {
                LiveThreadRuntimeEvent.ThreadListChanged(
                    threadIdHint = extractNotificationThreadId(params),
                )
            }

            "thread/status/changed",
            "codex/event/thread_status_changed" -> {
                val threadId = extractNotificationThreadId(params) ?: return null
                val status = notificationTerminalStatus(params)
                LiveThreadRuntimeEvent.ThreadStatusChanged(
                    threadId = threadId,
                    status = status,
                )
            }

            "turn/started" -> {
                val threadId = extractNotificationThreadId(params) ?: return null
                LiveThreadRuntimeEvent.TurnStarted(
                    threadId = threadId,
                    turnId = extractNotificationTurnId(params),
                )
            }

            "turn/completed",
            "turn/failed" -> {
                val turnId = extractNotificationTurnId(params)
                val threadId = extractNotificationThreadId(params)
                    ?: turnId?.let(threadIdByTurnId::get)
                    ?: return null
                LiveThreadRuntimeEvent.TurnCompleted(
                    threadId = threadId,
                    turnId = turnId,
                    status = notificationTerminalStatus(params),
                )
            }

            "turn/plan/updated" -> {
                val threadId = extractNotificationThreadId(params) ?: return null
                val itemObject = extractIncomingItemObject(params, eventObject)
                val entry = decodeRuntimeStructuredEntry(
                    turnId = extractNotificationTurnId(params),
                    itemId = extractNotificationItemId(params, eventObject),
                    itemObject = itemObject,
                    normalizedType = "plan",
                    delta = null,
                    isStreaming = false,
                ) ?: return null
                LiveThreadRuntimeEvent.StructuredEntryUpdated(
                    threadId = threadId,
                    turnId = extractNotificationTurnId(params),
                    entry = entry,
                )
            }

            "turn/diff/updated",
            "codex/event/turn_diff_updated",
            "codex/event/turn_diff" -> {
                val threadId = extractNotificationThreadId(params) ?: return null
                val diffText = firstNonBlank(
                    params.optString("diff").trim(),
                    params.optString("unified_diff").trim(),
                    eventObject?.optString("diff")?.trim().orEmpty(),
                    eventObject?.optString("unified_diff")?.trim().orEmpty(),
                )
                if (diffText.isEmpty()) return null
                val entry = TimelineEntry(
                    id = extractNotificationItemId(params, eventObject) ?: "diff-${System.currentTimeMillis()}",
                    speaker = "File change",
                    timestampLabel = "Now",
                    body = diffText,
                    kind = TimelineEntryKind.FileChange,
                    title = "File change",
                    isStreaming = false,
                )
                LiveThreadRuntimeEvent.StructuredEntryUpdated(
                    threadId = threadId,
                    turnId = extractNotificationTurnId(params),
                    entry = entry,
                )
            }

            "item/agentMessage/delta",
            "codex/event/agent_message_content_delta",
            "codex/event/agent_message_delta" -> {
                val threadId = extractNotificationThreadId(params) ?: return null
                val turnId = extractNotificationTurnId(params) ?: return null
                val delta = extractAssistantDeltaText(params, eventObject).orEmpty().trim()
                if (delta.isEmpty()) {
                    return null
                }
                LiveThreadRuntimeEvent.AssistantDelta(
                    threadId = threadId,
                    turnId = turnId,
                    itemId = extractNotificationItemId(params, eventObject),
                    delta = delta,
                )
            }

            "item/started",
            "codex/event/item_started" -> {
                // Placeholder creation — route as structured entry for non-assistant items
                val threadId = extractNotificationThreadId(params) ?: return null
                val itemObject = extractIncomingItemObject(params, eventObject)
                val itemType = normalizeTimelineItemType(itemObject?.optString("type").orEmpty())
                val role = itemObject?.optString("role").orEmpty()
                if (isAssistantMessageItem(itemType, role)) {
                    return null // Assistant items will arrive via delta/completed
                }
                val entry = decodeRuntimeStructuredEntry(
                    turnId = extractNotificationTurnId(params),
                    itemId = extractNotificationItemId(params, eventObject),
                    itemObject = itemObject,
                    normalizedType = itemType,
                    delta = null,
                    isStreaming = true,
                ) ?: return null
                LiveThreadRuntimeEvent.StructuredEntryUpdated(
                    threadId = threadId,
                    turnId = extractNotificationTurnId(params),
                    entry = entry,
                )
            }

            "item/reasoning/delta",
            "item/reasoning/outputDelta",
            "item/reasoning/summaryTextDelta",
            "item/reasoning/summaryPartAdded",
            "item/reasoning/textDelta",
            "item/toolCall/outputDelta",
            "item/toolCall/output_delta",
            "item/tool_call/outputDelta",
            "item/tool_call/output_delta",
            "item/commandExecution/outputDelta",
            "item/command_execution/outputDelta",
            "item/fileChange/outputDelta",
            "item/plan/delta" -> {
                val threadId = extractNotificationThreadId(params) ?: return null
                val itemObject = extractIncomingItemObject(params, eventObject)
                val normalizedType = when {
                    method.contains("reasoning", ignoreCase = true) -> "reasoning"
                    method.contains("toolcall", ignoreCase = true) || method.contains("tool_call", ignoreCase = true) -> "toolcall"
                    method.contains("commandexecution", ignoreCase = true) || method.contains("command_execution", ignoreCase = true) -> "commandexecution"
                    method.contains("filechange", ignoreCase = true) -> "filechange"
                    method.contains("plan", ignoreCase = true) -> "plan"
                    else -> normalizeTimelineItemType(itemObject?.optString("type").orEmpty())
                }
                val entry = decodeRuntimeStructuredEntry(
                    turnId = extractNotificationTurnId(params),
                    itemId = extractNotificationItemId(params, eventObject),
                    itemObject = itemObject,
                    normalizedType = normalizedType,
                    delta = extractAssistantDeltaText(params, eventObject),
                    isStreaming = true,
                ) ?: return null
                LiveThreadRuntimeEvent.StructuredEntryUpdated(
                    threadId = threadId,
                    turnId = extractNotificationTurnId(params),
                    entry = entry,
                )
            }

            "item/commandExecution/terminalInteraction",
            "item/command_execution/terminalInteraction" -> {
                val threadId = extractNotificationThreadId(params) ?: return null
                val itemObject = extractIncomingItemObject(params, eventObject)
                val entry = decodeRuntimeStructuredEntry(
                    turnId = extractNotificationTurnId(params),
                    itemId = extractNotificationItemId(params, eventObject),
                    itemObject = itemObject,
                    normalizedType = "commandexecution",
                    delta = extractAssistantDeltaText(params, eventObject),
                    isStreaming = true,
                ) ?: return null
                LiveThreadRuntimeEvent.StructuredEntryUpdated(
                    threadId = threadId,
                    turnId = extractNotificationTurnId(params),
                    entry = entry,
                )
            }

            "codex/event/user_message" -> {
                val threadId = extractNotificationThreadId(params) ?: return null
                val text = firstNonBlank(
                    params.optString("message").trim(),
                    params.optString("text").trim(),
                    eventObject?.optString("message")?.trim().orEmpty(),
                    eventObject?.optString("text")?.trim().orEmpty(),
                )
                if (text.isEmpty()) return null
                LiveThreadRuntimeEvent.UserMessageEcho(
                    threadId = threadId,
                    turnId = extractNotificationTurnId(params),
                    text = text,
                )
            }

            "error",
            "codex/event/error" -> {
                val threadId = extractNotificationThreadId(params)
                val message = firstNonBlank(
                    params.optString("message").trim(),
                    params.optJSONObject("error")?.optString("message")?.trim().orEmpty(),
                    eventObject?.optString("message")?.trim().orEmpty(),
                ).ifEmpty { "An error occurred" }
                LiveThreadRuntimeEvent.ErrorNotification(
                    threadId = threadId,
                    turnId = extractNotificationTurnId(params),
                    message = message,
                )
            }

            "codex/event/patch_apply_begin",
            "codex/event/patch_apply_end" -> {
                val threadId = extractNotificationThreadId(params) ?: return null
                val entry = decodeRuntimeStructuredEntry(
                    turnId = extractNotificationTurnId(params),
                    itemId = extractNotificationItemId(params, eventObject)
                        ?: params.optString("call_id").takeIf(String::isNotBlank),
                    itemObject = extractIncomingItemObject(params, eventObject),
                    normalizedType = "filechange",
                    delta = null,
                    isStreaming = method.contains("begin"),
                ) ?: return null
                LiveThreadRuntimeEvent.StructuredEntryUpdated(
                    threadId = threadId,
                    turnId = extractNotificationTurnId(params),
                    entry = entry,
                )
            }

            "codex/event/exec_command_begin",
            "codex/event/exec_command_output_delta",
            "codex/event/exec_command_end" -> {
                val threadId = extractNotificationThreadId(params) ?: return null
                val entry = decodeRuntimeStructuredEntry(
                    turnId = extractNotificationTurnId(params),
                    itemId = extractNotificationItemId(params, eventObject)
                        ?: params.optString("call_id").takeIf(String::isNotBlank),
                    itemObject = extractIncomingItemObject(params, eventObject),
                    normalizedType = "commandexecution",
                    delta = extractAssistantDeltaText(params, eventObject),
                    isStreaming = !method.contains("end"),
                ) ?: return null
                LiveThreadRuntimeEvent.StructuredEntryUpdated(
                    threadId = threadId,
                    turnId = extractNotificationTurnId(params),
                    entry = entry,
                )
            }

            "codex/event/background_event",
            "codex/event/read",
            "codex/event/search",
            "codex/event/list_files" -> {
                // Essential activity events — surface as system entries
                val threadId = extractNotificationThreadId(params) ?: return null
                val activityText = decodeEssentialActivityText(method, params, eventObject)
                    .takeIf(String::isNotBlank) ?: return null
                val entry = TimelineEntry(
                    id = "activity-${System.currentTimeMillis()}",
                    speaker = "System",
                    timestampLabel = "Now",
                    body = activityText,
                    kind = TimelineEntryKind.System,
                    isStreaming = false,
                )
                LiveThreadRuntimeEvent.StructuredEntryUpdated(
                    threadId = threadId,
                    turnId = extractNotificationTurnId(params),
                    entry = entry,
                )
            }

            "item/completed",
            "codex/event/item_completed",
            "codex/event/agent_message" -> {
                val itemObject = extractIncomingItemObject(params, eventObject)
                val itemType = normalizeTimelineItemType(itemObject?.optString("type").orEmpty())
                val role = itemObject?.optString("role").orEmpty()
                val threadId = extractNotificationThreadId(params) ?: return null
                val text = when {
                    itemObject != null && isAssistantMessageItem(itemType, role) ->
                        decodeTimelineText(itemObject).trim()

                    else -> firstNonBlank(
                        params.optString("message").trim(),
                        eventObject?.optString("message")?.trim().orEmpty(),
                    )
                }
                if (text.isEmpty()) {
                    if (itemObject != null && !isAssistantMessageItem(itemType, role)) {
                        val entry = decodeRuntimeStructuredEntry(
                            turnId = extractNotificationTurnId(params),
                            itemId = extractNotificationItemId(params, eventObject),
                            itemObject = itemObject,
                            normalizedType = itemType,
                            delta = null,
                            isStreaming = false,
                        ) ?: return null
                        return LiveThreadRuntimeEvent.StructuredEntryUpdated(
                            threadId = threadId,
                            turnId = extractNotificationTurnId(params),
                            entry = entry,
                        )
                    }
                    return null
                }
                LiveThreadRuntimeEvent.AssistantCompleted(
                    threadId = threadId,
                    turnId = extractNotificationTurnId(params),
                    itemId = extractNotificationItemId(params, eventObject),
                    text = text,
                )
            }

            else -> {
                // Fallback pattern matching for variant event names
                val normalized = method.lowercase().replace(Regex("[^a-z0-9/]"), "")
                when {
                    // Legacy codex/event envelope — unwrap inner type
                    method == "codex/event" -> {
                        val innerType = params.optJSONObject("msg")?.optString("type")?.trim().orEmpty()
                        if (innerType.isNotBlank()) {
                            parseRuntimeUiEvent(innerType, params)
                        } else {
                            null
                        }
                    }
                    normalized.contains("filechange") -> {
                        val threadId = extractNotificationThreadId(params) ?: return null
                        val entry = decodeRuntimeStructuredEntry(
                            turnId = extractNotificationTurnId(params),
                            itemId = extractNotificationItemId(params, eventObject),
                            itemObject = extractIncomingItemObject(params, eventObject),
                            normalizedType = "filechange",
                            delta = extractAssistantDeltaText(params, eventObject),
                            isStreaming = !normalized.contains("completed") && !normalized.contains("done"),
                        ) ?: return null
                        LiveThreadRuntimeEvent.StructuredEntryUpdated(
                            threadId = threadId,
                            turnId = extractNotificationTurnId(params),
                            entry = entry,
                        )
                    }
                    normalized.contains("toolcall") -> {
                        val threadId = extractNotificationThreadId(params) ?: return null
                        val entry = decodeRuntimeStructuredEntry(
                            turnId = extractNotificationTurnId(params),
                            itemId = extractNotificationItemId(params, eventObject),
                            itemObject = extractIncomingItemObject(params, eventObject),
                            normalizedType = "toolcall",
                            delta = extractAssistantDeltaText(params, eventObject),
                            isStreaming = !normalized.contains("completed") && !normalized.contains("done"),
                        ) ?: return null
                        LiveThreadRuntimeEvent.StructuredEntryUpdated(
                            threadId = threadId,
                            turnId = extractNotificationTurnId(params),
                            entry = entry,
                        )
                    }
                    normalized.contains("diff") || normalized.contains("turndiff") -> {
                        val threadId = extractNotificationThreadId(params) ?: return null
                        val diffText = firstNonBlank(
                            params.optString("diff").trim(),
                            params.optString("unified_diff").trim(),
                            eventObject?.optString("diff")?.trim().orEmpty(),
                        )
                        if (diffText.isEmpty()) return null
                        val entry = TimelineEntry(
                            id = "diff-${System.currentTimeMillis()}",
                            speaker = "File change",
                            timestampLabel = "Now",
                            body = diffText,
                            kind = TimelineEntryKind.FileChange,
                            title = "File change",
                            isStreaming = false,
                        )
                        LiveThreadRuntimeEvent.StructuredEntryUpdated(
                            threadId = threadId,
                            turnId = extractNotificationTurnId(params),
                            entry = entry,
                        )
                    }
                    method.startsWith("codex/event/") -> {
                        // Unknown legacy codex/event — silently ignore
                        null
                    }
                    else -> null
                }
            }
        }
    }

    private fun decodeEssentialActivityText(
        method: String,
        params: JSONObject,
        eventObject: JSONObject?,
    ): String {
        val shortMethod = method.removePrefix("codex/event/")
        return when (shortMethod) {
            "background_event" -> {
                firstNonBlank(
                    params.optString("message").trim(),
                    params.optString("text").trim(),
                    params.optString("body").trim(),
                    eventObject?.optString("message")?.trim().orEmpty(),
                ).take(140)
            }
            "read" -> {
                val path = firstNonBlank(
                    params.optString("path").trim(),
                    params.optString("file_path").trim(),
                    params.optString("file").trim(),
                    eventObject?.optString("path")?.trim().orEmpty(),
                )
                if (path.isNotEmpty()) "Reading $path" else ""
            }
            "search" -> {
                val query = firstNonBlank(
                    params.optString("query").trim(),
                    params.optString("pattern").trim(),
                    params.optString("regex").trim(),
                    eventObject?.optString("query")?.trim().orEmpty(),
                )
                if (query.isNotEmpty()) "Searching: $query" else ""
            }
            "list_files" -> {
                val path = firstNonBlank(
                    params.optString("path").trim(),
                    params.optString("cwd").trim(),
                    eventObject?.optString("path")?.trim().orEmpty(),
                )
                if (path.isNotEmpty()) "Listing files in $path" else "Listing files"
            }
            else -> ""
        }
    }

    private fun decodeRuntimeStructuredEntry(
        turnId: String?,
        itemId: String?,
        itemObject: JSONObject?,
        normalizedType: String,
        delta: String?,
        isStreaming: Boolean,
    ): TimelineEntry? {
        val fallbackObject = itemObject ?: JSONObject()
        val entryKind = decodeTimelineEntryKind(normalizedType)
        if (entryKind == TimelineEntryKind.Chat) {
            return null
        }
        val body = delta?.trim()?.takeIf(String::isNotEmpty)
            ?: decodeTimelineBody(fallbackObject, normalizedType, entryKind).trim()
        val planSteps = if (entryKind == TimelineEntryKind.Plan) {
            decodeTimelinePlanSteps(fallbackObject)
        } else {
            emptyList()
        }
        val attachments = decodeTimelineAttachments(fallbackObject)
        if (body.isEmpty() && planSteps.isEmpty() && attachments.isEmpty()) {
            return null
        }
        val resolvedEntryId = when (entryKind) {
            TimelineEntryKind.Thinking -> turnId?.takeIf { it.isNotBlank() }?.let { "runtime-thinking-$it" }
                ?: itemId?.takeIf { it.isNotBlank() }
                ?: "runtime-thinking"
            else -> itemId?.takeIf { it.isNotBlank() } ?: "runtime-${normalizedType}-${turnId.orEmpty()}"
        }
        return TimelineEntry(
            id = resolvedEntryId,
            speaker = decodeTimelineSpeaker(fallbackObject, normalizedType),
            timestampLabel = "Now",
            body = body,
            kind = entryKind,
            title = decodeTimelineTitle(fallbackObject, entryKind),
            detail = decodeTimelineDetail(fallbackObject, entryKind),
            planSteps = planSteps,
            isStreaming = isStreaming,
            attachments = attachments,
        )
    }

    private fun extractAssistantDeltaText(
        params: JSONObject,
        eventObject: JSONObject?,
    ): String? {
        return sequenceOf(
            params.optString("delta"),
            eventObject?.optString("delta"),
            params.optJSONObject("event")?.optString("delta"),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun extractNotificationItemId(
        params: JSONObject,
        eventObject: JSONObject?,
    ): String? {
        val itemObject = extractIncomingItemObject(params, eventObject)
        return sequenceOf(
            itemObject?.optString("id"),
            params.optString("itemId"),
            params.optString("item_id"),
            eventObject?.optString("itemId"),
            eventObject?.optString("item_id"),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun extractIncomingItemObject(
        params: JSONObject,
        eventObject: JSONObject?,
    ): JSONObject? {
        val directItem = params.optJSONObject("item")
        if (directItem != null) {
            return directItem
        }

        val nestedEventItem = eventObject?.optJSONObject("item")
        if (nestedEventItem != null) {
            return nestedEventItem
        }

        return if (params.has("type")) params else eventObject?.takeIf { it.has("type") }
    }

    private fun isAssistantMessageItem(
        itemType: String,
        role: String,
    ): Boolean {
        val normalizedRole = role.trim().lowercase(Locale.US)
        return when (itemType) {
            "agentmessage", "assistantmessage" -> true
            "message" -> !normalizedRole.contains("user")
            else -> false
        }
    }

    private fun firstNonBlank(vararg values: String): String {
        return values.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun extractNotificationStatus(params: JSONObject): String? {
        val eventObject = params.optJSONObject("event")
        val turnObject = params.optJSONObject("turn")
        val statusObject = params.optJSONObject("status")
            ?: turnObject?.optJSONObject("status")
            ?: eventObject?.optJSONObject("status")

        val rawStatus = sequenceOf(
            statusObject?.optString("type"),
            statusObject?.optString("statusType"),
            statusObject?.optString("status_type"),
            extractStatusValue(params, listOf("status")),
            eventObject?.let { extractStatusValue(it, listOf("status")) },
            turnObject?.let { extractStatusValue(it, listOf("status")) },
        ).firstOrNull { !it.isNullOrBlank() } ?: return null

        return rawStatus.replace("_", "")
            .replace("-", "")
            .lowercase(Locale.US)
    }

    private fun isRunningNotificationStatus(normalizedStatus: String): Boolean {
        return normalizedStatus == "active"
            || normalizedStatus == "running"
            || normalizedStatus == "processing"
            || normalizedStatus == "inprogress"
            || normalizedStatus == "started"
            || normalizedStatus == "pending"
    }

    private fun isTerminalNotificationStatus(normalizedStatus: String): Boolean {
        return normalizedStatus == "idle"
            || normalizedStatus == "notloaded"
            || normalizedStatus == "completed"
            || normalizedStatus == "done"
            || normalizedStatus == "finished"
            || normalizedStatus == "stopped"
            || normalizedStatus == "systemerror"
            || normalizedStatus.contains("fail")
            || normalizedStatus.contains("error")
            || normalizedStatus.contains("interrupt")
            || normalizedStatus.contains("cancel")
            || normalizedStatus.contains("abort")
    }

    private fun notificationTerminalStatus(
        params: JSONObject,
        normalizedStatus: String = extractNotificationStatus(params).orEmpty(),
    ): ThreadStatus {
        return when {
            normalizedStatus.isEmpty() -> ThreadStatus.Completed
            normalizedStatus.contains("fail") || normalizedStatus.contains("error") -> ThreadStatus.Failed
            normalizedStatus.contains("complete")
                || normalizedStatus.contains("done")
                || normalizedStatus.contains("finished") -> ThreadStatus.Completed

            else -> ThreadStatus.Waiting
        }
    }

    private fun isInterruptibleTurnStatus(normalizedStatus: String): Boolean {
        if (
            normalizedStatus.contains("inprogress")
            || normalizedStatus.contains("running")
            || normalizedStatus.contains("pending")
            || normalizedStatus.contains("started")
        ) {
            return true
        }

        if (
            normalizedStatus.contains("complete")
            || normalizedStatus.contains("failed")
            || normalizedStatus.contains("error")
            || normalizedStatus.contains("interrupt")
            || normalizedStatus.contains("cancel")
            || normalizedStatus.contains("stopped")
        ) {
            return false
        }

        return true
    }

    private fun parseThreadInstant(threadObject: JSONObject, vararg keys: String): Instant? {
        for (key in keys) {
            if (!threadObject.has(key)) {
                continue
            }
            val rawValue = threadObject.opt(key)
            when (rawValue) {
                is Number -> {
                    val number = rawValue.toLong()
                    return if (number > 2_000_000_000L) Instant.ofEpochMilli(number) else Instant.ofEpochSecond(number)
                }

                is String -> {
                    val trimmed = rawValue.trim()
                    if (trimmed.isEmpty()) {
                        continue
                    }
                    trimmed.toLongOrNull()?.let { numeric ->
                        return if (numeric > 2_000_000_000L) Instant.ofEpochMilli(numeric) else Instant.ofEpochSecond(numeric)
                    }
                    runCatching { Instant.parse(trimmed) }.getOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    private fun formatThreadUpdatedLabel(instant: Instant): String {
        val duration = Duration.between(instant, now()).abs()
        return when {
            duration.toMinutes() < 1 -> "Just now"
            duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
            duration.toHours() < 24 -> "${duration.toHours()}h ago"
            else -> threadDateFormatter.format(instant.atZone(ZoneId.systemDefault()))
        }
    }

    private fun shouldContinueThreadListPagination(
        nextCursor: Any?,
        limit: Int?,
        hasRequestedFirstPage: Boolean,
    ): Boolean {
        if (!hasRequestedFirstPage || limit != null) {
            return false
        }
        return when (nextCursor) {
            null, JSONObject.NULL -> false
            is String -> nextCursor.isNotBlank()
            else -> true
        }
    }

    private suspend fun initializeSecureSession(session: ActiveSecureRelaySession): String {
        val requestId = "initialize-${UUID.randomUUID()}"
        val initializeRequest = JSONObject()
            .put("id", requestId)
            .put("method", "initialize")
            .put(
                "params",
                JSONObject()
                    .put(
                        "clientInfo",
                        JSONObject()
                            .put("name", "codexmobile_android")
                            .put("title", "Remodex Android")
                            .put("version", BuildConfig.VERSION_NAME),
                    )
                    .put(
                        "capabilities",
                        JSONObject()
                            .put("experimentalApi", true),
                    ),
            )

        sendEncryptedAppPayload(session, initializeRequest.toString())
        val response = awaitEncryptedRpcResponse(session, requestId)
        if (response.optJSONObject("error") != null) {
            val message = response.optJSONObject("error")?.optString("message")
                ?: "The bridge rejected the initialize request."
            logError(
                bootstrapLogTag,
                "initialize error session=${maskIdentifier(session.sessionId)} message=$message",
                null,
            )
            throw RelayBootstrapException(message)
        }

        val initializedNotification = JSONObject()
            .put("method", "initialized")
            .put("params", JSONObject.NULL)
        sendEncryptedAppPayload(session, initializedNotification.toString())

        val bridgeManaged = response.optJSONObject("result")?.optBoolean("bridgeManaged") == true
        return if (bridgeManaged) {
            "Initialize complete"
        } else {
            "Initialize returned"
        }
    }

    private suspend fun awaitEncryptedRpcResponse(
        session: ActiveSecureRelaySession,
        expectedRequestId: String,
    ): JSONObject {
        var matchedResponse: JSONObject? = null
        withTimeout(relayWaitTimeoutMs) {
            while (matchedResponse == null) {
                when (val event = session.events.receive()) {
                    RelaySocketEvent.Open -> continue
                    is RelaySocketEvent.Failure -> {
                        val snapshot = lastSessionDisconnect
                            ?: transportFailureSnapshot(
                                throwable = event.throwable,
                                duringHandshake = false,
                            )
                        throw RelayBootstrapException(snapshot.message, snapshot.failureKind)
                    }

                    is RelaySocketEvent.Closed -> {
                        val snapshot = lastSessionDisconnect
                            ?: relayDisconnectSnapshot(
                                code = event.code,
                                reason = event.reason,
                                duringHandshake = false,
                            )
                        throw RelayBootstrapException(snapshot.message, snapshot.failureKind)
                    }

                    is RelaySocketEvent.Message -> {
                        val json = parseJsonOrNull(event.text) ?: continue
                        when (json.optString("kind")) {
                            "secureError" -> throw RelayBootstrapException(
                                json.optString("message", "The bridge rejected the encrypted message."),
                            )

                            "encryptedEnvelope" -> {
                                val payloadText = RelaySecureCrypto.decryptEnvelopePayload(
                                    envelope = json,
                                    session = session,
                                ) ?: continue
                                val payloadObject = parseJsonOrNull(payloadText) ?: continue
                                val responseId = payloadObject.opt("id")?.toString()
                                if (responseId == expectedRequestId) {
                                    matchedResponse = payloadObject
                                } else {
                                    trackIncomingRuntimeNotification(payloadObject)
                                }
                            }
                        }
                    }
                }
            }
        }
        return matchedResponse ?: throw RelayBootstrapException(
            "The bridge did not return an encrypted response before timing out.",
        )
    }

    private fun sendEncryptedAppPayload(
        session: ActiveSecureRelaySession,
        plaintext: String,
    ) {
        if (!session.isOpen) {
            val snapshot = lastSessionDisconnect
                ?: SessionDisconnectSnapshot(
                    message = "The live relay connection dropped before the request could be sent.",
                    failureKind = RelayBootstrapFailureKind.SessionDisconnected,
                )
            throw RelayBootstrapException(snapshot.message, snapshot.failureKind)
        }
        val envelope = RelaySecureCrypto.encryptEnvelopePayload(
            payloadText = plaintext,
            session = session,
        )
        if (!session.socket.send(envelope.toString())) {
            val snapshot = lastSessionDisconnect
                ?: SessionDisconnectSnapshot(
                    message = "The live relay connection dropped before the request could be sent.",
                    failureKind = RelayBootstrapFailureKind.SessionDisconnected,
                )
            handleSocketDisconnected(session.connectionToken, snapshot)
            throw RelayBootstrapException(snapshot.message, snapshot.failureKind)
        }
    }
}

internal object RelaySecureCrypto {
    private val secureRandom = SecureRandom()

    fun generatePhoneIdentity(): RuntimePhoneIdentity {
        val privateKey = Ed25519PrivateKeyParameters(secureRandom)
        return RuntimePhoneIdentity(
            deviceId = UUID.randomUUID().toString(),
            privateKey = privateKey,
            publicKeyBase64 = Base64.getEncoder().encodeToString(privateKey.generatePublicKey().encoded),
        )
    }

    fun generateX25519KeyPair(): RuntimeEphemeralKeyPair {
        val privateKey = X25519PrivateKeyParameters(secureRandom)
        return RuntimeEphemeralKeyPair(
            privateKey = privateKey,
            publicKeyBase64 = Base64.getEncoder().encodeToString(privateKey.generatePublicKey().encoded),
            algorithm = "BC-X25519",
        )
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

    fun buildTranscriptBytes(
        sessionId: String,
        protocolVersion: Int,
        handshakeMode: String,
        keyEpoch: Int,
        macDeviceId: String,
        phoneDeviceId: String,
        macIdentityPublicKey: String,
        phoneIdentityPublicKey: String,
        macEphemeralPublicKey: String,
        phoneEphemeralPublicKey: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        expiresAtForTranscript: Long,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.appendLengthPrefixedUtf8(secureHandshakeTag)
        output.appendLengthPrefixedUtf8(sessionId)
        output.appendLengthPrefixedUtf8(protocolVersion.toString())
        output.appendLengthPrefixedUtf8(handshakeMode)
        output.appendLengthPrefixedUtf8(keyEpoch.toString())
        output.appendLengthPrefixedUtf8(macDeviceId)
        output.appendLengthPrefixedUtf8(phoneDeviceId)
        output.appendLengthPrefixed(Base64.getDecoder().decode(macIdentityPublicKey))
        output.appendLengthPrefixed(Base64.getDecoder().decode(phoneIdentityPublicKey))
        output.appendLengthPrefixed(Base64.getDecoder().decode(macEphemeralPublicKey))
        output.appendLengthPrefixed(Base64.getDecoder().decode(phoneEphemeralPublicKey))
        output.appendLengthPrefixed(clientNonce)
        output.appendLengthPrefixed(serverNonce)
        output.appendLengthPrefixedUtf8(expiresAtForTranscript.toString())
        return output.toByteArray()
    }

    fun buildTrustedSessionResolveBytes(
        macDeviceId: String,
        phoneDeviceId: String,
        phoneIdentityPublicKey: String,
        nonce: String,
        timestamp: Long,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.appendLengthPrefixedUtf8(trustedSessionResolveTag)
        output.appendLengthPrefixedUtf8(macDeviceId)
        output.appendLengthPrefixedUtf8(phoneDeviceId)
        output.appendLengthPrefixed(Base64.getDecoder().decode(phoneIdentityPublicKey))
        output.appendLengthPrefixedUtf8(nonce)
        output.appendLengthPrefixedUtf8(timestamp.toString())
        return output.toByteArray()
    }

    fun signClientAuthTranscript(
        privateKey: Ed25519PrivateKeyParameters,
        transcriptBytes: ByteArray,
    ): String {
        return signEd25519(privateKey, clientAuthTranscript(transcriptBytes))
    }

    fun signTranscript(
        privateKey: Ed25519PrivateKeyParameters,
        transcriptBytes: ByteArray,
    ): String {
        return signEd25519(privateKey, transcriptBytes)
    }

    fun signTrustedSessionResolveRequest(
        privateKey: Ed25519PrivateKeyParameters,
        transcriptBytes: ByteArray,
    ): String {
        return signEd25519(privateKey, transcriptBytes)
    }

    fun verifyMacSignature(
        macIdentityPublicKey: String,
        transcriptBytes: ByteArray,
        macSignature: String,
    ) {
        if (!isMacSignatureValid(macIdentityPublicKey, transcriptBytes, macSignature)) {
            throw RelayBootstrapException("The secure Mac signature could not be verified.")
        }
    }

    fun isMacSignatureValid(
        macIdentityPublicKey: String,
        transcriptBytes: ByteArray,
        macSignature: String,
    ): Boolean {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(Base64.getDecoder().decode(macIdentityPublicKey), 0))
        verifier.update(transcriptBytes, 0, transcriptBytes.size)
        return verifier.verifySignature(Base64.getDecoder().decode(macSignature))
    }

    fun deriveSessionKeys(
        macEphemeralPublicKey: String,
        phoneEphemeralPrivateKey: X25519PrivateKeyParameters,
        transcriptBytes: ByteArray,
        sessionId: String,
        macDeviceId: String,
        phoneDeviceId: String,
        keyEpoch: Int,
    ): Pair<ByteArray, ByteArray> {
        val sharedSecret = ByteArray(32)
        phoneEphemeralPrivateKey.generateSecret(
            X25519PublicKeyParameters(Base64.getDecoder().decode(macEphemeralPublicKey), 0),
            sharedSecret,
            0,
        )
        val salt = MessageDigest.getInstance("SHA-256").digest(transcriptBytes)
        val infoPrefix = "$secureHandshakeTag|$sessionId|$macDeviceId|$phoneDeviceId|$keyEpoch"
        return hkdfSha256(sharedSecret, salt, "$infoPrefix|phoneToMac") to
            hkdfSha256(sharedSecret, salt, "$infoPrefix|macToPhone")
    }

    fun deriveSessionKeysFromMacPerspective(
        phoneEphemeralPublicKey: String,
        macEphemeralPrivateKey: X25519PrivateKeyParameters,
        transcriptBytes: ByteArray,
        sessionId: String,
        macDeviceId: String,
        phoneDeviceId: String,
        keyEpoch: Int,
    ): Pair<ByteArray, ByteArray> {
        val sharedSecret = ByteArray(32)
        macEphemeralPrivateKey.generateSecret(
            X25519PublicKeyParameters(Base64.getDecoder().decode(phoneEphemeralPublicKey), 0),
            sharedSecret,
            0,
        )
        val salt = MessageDigest.getInstance("SHA-256").digest(transcriptBytes)
        val infoPrefix = "$secureHandshakeTag|$sessionId|$macDeviceId|$phoneDeviceId|$keyEpoch"
        return hkdfSha256(sharedSecret, salt, "$infoPrefix|phoneToMac") to
            hkdfSha256(sharedSecret, salt, "$infoPrefix|macToPhone")
    }

    fun encryptEnvelopePayload(
        payloadText: String,
        session: ActiveSecureRelaySession,
        sender: String = "iphone",
        bridgeOutboundSeq: Int? = null,
    ): JSONObject {
        val payloadObject = JSONObject()
            .put("bridgeOutboundSeq", bridgeOutboundSeq ?: JSONObject.NULL)
            .put("payloadText", payloadText)
        val payloadBytes = payloadObject.toString().toByteArray(StandardCharsets.UTF_8)
        val key = if (sender == "mac") session.macToPhoneKey else session.phoneToMacKey
        val nonce = nonceForDirection(sender = sender, counter = session.nextOutboundCounter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        val encrypted = cipher.doFinal(payloadBytes)
        val ciphertext = encrypted.copyOfRange(0, encrypted.size - 16)
        val tag = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)

        val envelope = JSONObject()
            .put("kind", "encryptedEnvelope")
            .put("v", secureProtocolVersion)
            .put("sessionId", session.sessionId)
            .put("keyEpoch", session.keyEpoch)
            .put("sender", sender)
            .put("counter", session.nextOutboundCounter)
            .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
            .put("tag", Base64.getEncoder().encodeToString(tag))
        session.nextOutboundCounter += 1
        return envelope
    }

    fun decryptEnvelopePayload(
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
            || sender != "mac"
            || counter <= session.lastInboundCounter
        ) {
            return null
        }

        val ciphertext = Base64.getDecoder().decode(envelope.optString("ciphertext"))
        val tag = Base64.getDecoder().decode(envelope.optString("tag"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(session.macToPhoneKey, "AES"),
            GCMParameterSpec(128, nonceForDirection(sender = "mac", counter = counter)),
        )
        val plaintext = runCatching { cipher.doFinal(ciphertext + tag) }.getOrNull() ?: return null
        session.lastInboundCounter = counter
        val payloadObject = JSONObject(String(plaintext, StandardCharsets.UTF_8))
        return payloadObject.optString("payloadText").ifBlank { null }
    }

    fun shortFingerprint(publicKeyBase64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(Base64.getDecoder().decode(publicKeyBase64))
        return digest.take(6).joinToString("") { byte -> "%02X".format(byte) }
    }

    fun verifiedAtLabel(instant: Instant, zoneId: ZoneId = ZoneId.systemDefault()): String {
        return bootstrapVerifiedFormatter.format(instant.atZone(zoneId))
    }

    fun serializePhoneIdentityPrivateKey(privateKey: Ed25519PrivateKeyParameters): String {
        return Base64.getEncoder().encodeToString(privateKey.encoded)
    }

    private fun clientAuthTranscript(transcriptBytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(transcriptBytes)
        output.appendLengthPrefixedUtf8(secureHandshakeLabel)
        return output.toByteArray()
    }

    private fun signEd25519(
        privateKey: Ed25519PrivateKeyParameters,
        message: ByteArray,
    ): String {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        return Base64.getEncoder().encodeToString(signer.generateSignature())
    }

    private fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: String,
        length: Int = 32,
    ): ByteArray {
        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extractMac.doFinal(inputKeyMaterial)

        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))

        val output = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (output.size() < length) {
            expandMac.reset()
            expandMac.update(previous)
            expandMac.update(info.toByteArray(StandardCharsets.UTF_8))
            expandMac.update(counter.toByte())
            previous = expandMac.doFinal()
            output.write(previous)
            counter += 1
        }
        return output.toByteArray().copyOf(length)
    }

    private fun nonceForDirection(sender: String, counter: Int): ByteArray {
        val nonce = ByteArray(12)
        nonce[0] = if (sender == "mac") 1 else 2
        var value = counter.toLong()
        for (index in 11 downTo 1) {
            nonce[index] = (value and 0xff).toByte()
            value = value shr 8
        }
        return nonce
    }

    private fun ByteArrayOutputStream.appendLengthPrefixedUtf8(value: String) {
        appendLengthPrefixed(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun ByteArrayOutputStream.appendLengthPrefixed(value: ByteArray) {
        write(ByteBuffer.allocate(4).putInt(value.size).array())
        write(value)
    }
}

internal data class RuntimePhoneIdentity(
    val deviceId: String,
    val privateKey: Ed25519PrivateKeyParameters,
    val publicKeyBase64: String,
)

private fun TrustedReconnectRecord.toRuntimePhoneIdentity(): RuntimePhoneIdentity {
    return RuntimePhoneIdentity(
        deviceId = phoneDeviceId,
        privateKey = Ed25519PrivateKeyParameters(Base64.getDecoder().decode(phoneIdentityPrivateKey), 0),
        publicKeyBase64 = phoneIdentityPublicKey,
    )
}

internal data class RuntimeEphemeralKeyPair(
    val privateKey: X25519PrivateKeyParameters,
    val publicKeyBase64: String,
    val algorithm: String,
)

internal data class ServerHelloMessage(
    val protocolVersion: Int,
    val sessionId: String,
    val handshakeMode: String,
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val macEphemeralPublicKey: String,
    val serverNonce: String,
    val keyEpoch: Int,
    val expiresAtForTranscript: Long,
    val macSignature: String,
    val clientNonce: String?,
)

internal data class ActiveSecureRelaySession(
    val connectionToken: String,
    val socket: RelaySocketConnection,
    val events: Channel<RelaySocketEvent>,
    val sessionId: String,
    val keyEpoch: Int,
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val phoneDeviceId: String,
    val phoneIdentityPublicKey: String,
    val phoneToMacKey: ByteArray,
    val macToPhoneKey: ByteArray,
    var nextOutboundCounter: Int,
    var lastInboundCounter: Int,
    @Volatile var isOpen: Boolean = true,
)

internal sealed interface RelaySocketEvent {
    data object Open : RelaySocketEvent
    data class Message(val text: String) : RelaySocketEvent
    data class Closed(val code: Int, val reason: String?) : RelaySocketEvent
    data class Failure(val throwable: Throwable) : RelaySocketEvent
}

interface RelaySocketFactory {
    fun connect(
        url: String,
        headers: Map<String, String>,
        listener: RelaySocketListener,
    ): RelaySocketConnection
}

interface RelaySocketConnection {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String?)
}

interface RelaySocketListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosed(code: Int, reason: String?)
    fun onFailure(throwable: Throwable)
}

class OkHttpRelaySocketFactory(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) : RelaySocketFactory {
    private val noProxyClient: OkHttpClient by lazy {
        client.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .build()
    }

    override fun connect(
        url: String,
        headers: Map<String, String>,
        listener: RelaySocketListener,
    ): RelaySocketConnection {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (name, value) ->
            requestBuilder.addHeader(name, value)
        }
        val webSocketClient = if (relayEndpointProfile(url).prefersDirectNoProxy) {
            noProxyClient
        } else {
            client
        }
        val webSocket = webSocketClient.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    listener.onMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    listener.onMessage(bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onFailure(t)
                }
            },
        )
        return object : RelaySocketConnection {
            override fun send(text: String): Boolean = webSocket.send(text)

            override fun close(code: Int, reason: String?) {
                webSocket.close(code, reason)
            }
        }
    }
}

private data class TrustedReconnectTarget(
    val payload: PairingQrPayload,
    val trustedRecord: TrustedReconnectRecord,
    val reconnectPath: TrustedReconnectPath,
)
