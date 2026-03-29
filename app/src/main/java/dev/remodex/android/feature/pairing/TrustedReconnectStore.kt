package dev.remodex.android.feature.pairing

import android.content.Context
import org.json.JSONObject
import java.net.URI

data class TrustedReconnectRecord(
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val relay: String,
    val phoneDeviceId: String,
    val phoneIdentityPrivateKey: String,
    val phoneIdentityPublicKey: String,
    val lastPairedAt: Long,
    val displayName: String? = null,
    val lastResolvedSessionId: String? = null,
    val lastResolvedAt: Long? = null,
    val lastUsedAt: Long? = null,
)

data class TrustedSessionResolveResponse(
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val displayName: String?,
    val sessionId: String,
)

interface TrustedReconnectPersistence {
    fun read(): TrustedReconnectRecord?
    fun write(record: TrustedReconnectRecord)
    fun clear()
}

class SharedPrefsTrustedReconnectStore(
    context: Context,
) : TrustedReconnectPersistence {
    private val preferences = context.getSharedPreferences(
        "dev.remodex.android.trusted_reconnect",
        Context.MODE_PRIVATE,
    )

    override fun read(): TrustedReconnectRecord? {
        val raw = preferences.getString(KEY_RECORD, null)?.trim().orEmpty()
        if (raw.isEmpty()) {
            return null
        }

        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val macDeviceId = json.optString("macDeviceId").trim()
        val macIdentityPublicKey = json.optString("macIdentityPublicKey").trim()
        val relay = json.optString("relay").trim()
        val phoneDeviceId = json.optString("phoneDeviceId").trim()
        val phoneIdentityPrivateKey = json.optString("phoneIdentityPrivateKey").trim()
        val phoneIdentityPublicKey = json.optString("phoneIdentityPublicKey").trim()
        val lastPairedAt = json.optLong("lastPairedAt")

        if (
            macDeviceId.isEmpty()
            || macIdentityPublicKey.isEmpty()
            || relay.isEmpty()
            || phoneDeviceId.isEmpty()
            || phoneIdentityPrivateKey.isEmpty()
            || phoneIdentityPublicKey.isEmpty()
            || lastPairedAt <= 0L
        ) {
            return null
        }

        return TrustedReconnectRecord(
            macDeviceId = macDeviceId,
            macIdentityPublicKey = macIdentityPublicKey,
            relay = relay,
            phoneDeviceId = phoneDeviceId,
            phoneIdentityPrivateKey = phoneIdentityPrivateKey,
            phoneIdentityPublicKey = phoneIdentityPublicKey,
            lastPairedAt = lastPairedAt,
            displayName = readMeaningfulOptionalString(json.optString("displayName", "")),
            lastResolvedSessionId = readMeaningfulOptionalString(json.optString("lastResolvedSessionId", "")),
            lastResolvedAt = json.optLong("lastResolvedAt").takeIf { it > 0L },
            lastUsedAt = json.optLong("lastUsedAt").takeIf { it > 0L },
        )
    }

    override fun write(record: TrustedReconnectRecord) {
        val serialized = JSONObject()
            .put("macDeviceId", record.macDeviceId)
            .put("macIdentityPublicKey", record.macIdentityPublicKey)
            .put("relay", record.relay)
            .put("phoneDeviceId", record.phoneDeviceId)
            .put("phoneIdentityPrivateKey", record.phoneIdentityPrivateKey)
            .put("phoneIdentityPublicKey", record.phoneIdentityPublicKey)
            .put("lastPairedAt", record.lastPairedAt)
            .put("displayName", record.displayName ?: JSONObject.NULL)
            .put("lastResolvedSessionId", record.lastResolvedSessionId ?: JSONObject.NULL)
            .put("lastResolvedAt", record.lastResolvedAt ?: JSONObject.NULL)
            .put("lastUsedAt", record.lastUsedAt ?: JSONObject.NULL)
            .toString()
        preferences.edit().putString(KEY_RECORD, serialized).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_RECORD).apply()
    }

    private companion object {
        private const val KEY_RECORD = "trusted_reconnect_record"
    }
}

// --- Device History Registry ---

data class DeviceHistoryEntry(
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val relay: String,
    val phoneDeviceId: String,
    val phoneIdentityPrivateKey: String,
    val phoneIdentityPublicKey: String,
    val customName: String? = null,
    val pairedAt: Long,
    val lastUsedAt: Long? = null,
    val lastResolvedSessionId: String? = null,
)

interface DeviceHistoryPersistence {
    fun readAll(): List<DeviceHistoryEntry>
    fun write(entry: DeviceHistoryEntry)
    fun remove(macDeviceId: String)
    fun rename(macDeviceId: String, newName: String)
}

class SharedPrefsDeviceHistoryStore(
    context: Context,
) : DeviceHistoryPersistence {
    private val preferences = context.getSharedPreferences(
        "dev.remodex.android.device_history",
        Context.MODE_PRIVATE,
    )

    override fun readAll(): List<DeviceHistoryEntry> {
        val raw = preferences.getString(KEY_REGISTRY, null)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val arr = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val json = arr.optJSONObject(i) ?: return@mapNotNull null
            parseDeviceHistoryEntry(json)
        }
    }

    override fun write(entry: DeviceHistoryEntry) {
        val current = readAll().toMutableList()
        current.removeAll { it.macDeviceId == entry.macDeviceId }
        current.add(0, entry)
        persist(current)
    }

    override fun remove(macDeviceId: String) {
        val current = readAll().toMutableList()
        current.removeAll { it.macDeviceId == macDeviceId }
        persist(current)
    }

    override fun rename(macDeviceId: String, newName: String) {
        val current = readAll().toMutableList()
        val index = current.indexOfFirst { it.macDeviceId == macDeviceId }
        if (index >= 0) {
            current[index] = current[index].copy(customName = newName.trim().ifEmpty { null })
            persist(current)
        }
    }

    private fun persist(entries: List<DeviceHistoryEntry>) {
        val arr = org.json.JSONArray()
        entries.forEach { entry ->
            arr.put(
                JSONObject()
                    .put("macDeviceId", entry.macDeviceId)
                    .put("macIdentityPublicKey", entry.macIdentityPublicKey)
                    .put("relay", entry.relay)
                    .put("phoneDeviceId", entry.phoneDeviceId)
                    .put("phoneIdentityPrivateKey", entry.phoneIdentityPrivateKey)
                    .put("phoneIdentityPublicKey", entry.phoneIdentityPublicKey)
                    .put("customName", entry.customName ?: JSONObject.NULL)
                    .put("pairedAt", entry.pairedAt)
                    .put("lastUsedAt", entry.lastUsedAt ?: JSONObject.NULL)
                    .put("lastResolvedSessionId", entry.lastResolvedSessionId ?: JSONObject.NULL),
            )
        }
        preferences.edit().putString(KEY_REGISTRY, arr.toString()).apply()
    }

    private fun parseDeviceHistoryEntry(json: JSONObject): DeviceHistoryEntry? {
        val macDeviceId = json.optString("macDeviceId").trim()
        val macIdentityPublicKey = json.optString("macIdentityPublicKey").trim()
        val relay = json.optString("relay").trim()
        val phoneDeviceId = json.optString("phoneDeviceId").trim()
        val phoneIdentityPrivateKey = json.optString("phoneIdentityPrivateKey").trim()
        val phoneIdentityPublicKey = json.optString("phoneIdentityPublicKey").trim()
        val pairedAt = json.optLong("pairedAt")
        if (macDeviceId.isEmpty() || relay.isEmpty() || phoneDeviceId.isEmpty() || pairedAt <= 0L) {
            return null
        }
        return DeviceHistoryEntry(
            macDeviceId = macDeviceId,
            macIdentityPublicKey = macIdentityPublicKey,
            relay = relay,
            phoneDeviceId = phoneDeviceId,
            phoneIdentityPrivateKey = phoneIdentityPrivateKey,
            phoneIdentityPublicKey = phoneIdentityPublicKey,
            customName = readMeaningfulOptionalString(json.optString("customName", "")),
            pairedAt = pairedAt,
            lastUsedAt = json.optLong("lastUsedAt").takeIf { it > 0L },
            lastResolvedSessionId = readMeaningfulOptionalString(json.optString("lastResolvedSessionId", "")),
        )
    }

    private companion object {
        private const val KEY_REGISTRY = "device_history_registry"
    }
}

internal fun readMeaningfulOptionalString(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) {
        return null
    }
    if (trimmed.equals("null", ignoreCase = true) || trimmed.equals("undefined", ignoreCase = true)) {
        return null
    }
    return trimmed
}

fun DeviceHistoryEntry.displayLabel(): String = readMeaningfulOptionalString(customName) ?: "Device ${maskIdentifier(macDeviceId)}"

fun DeviceHistoryEntry.relayLabel(): String {
    val uri = runCatching { URI(relay) }.getOrNull()
    val host = uri?.host.orEmpty()
    return if (host.isNotBlank()) host else relay
}

fun DeviceHistoryEntry.toTrustedReconnectRecord(): TrustedReconnectRecord = TrustedReconnectRecord(
    macDeviceId = macDeviceId,
    macIdentityPublicKey = macIdentityPublicKey,
    relay = relay,
    phoneDeviceId = phoneDeviceId,
    phoneIdentityPrivateKey = phoneIdentityPrivateKey,
    phoneIdentityPublicKey = phoneIdentityPublicKey,
    lastPairedAt = pairedAt,
    displayName = customName,
    lastUsedAt = lastUsedAt,
    lastResolvedSessionId = lastResolvedSessionId,
)

fun TrustedReconnectRecord.toDeviceHistoryEntry(): DeviceHistoryEntry = DeviceHistoryEntry(
    macDeviceId = macDeviceId,
    macIdentityPublicKey = macIdentityPublicKey,
    relay = relay,
    phoneDeviceId = phoneDeviceId,
    phoneIdentityPrivateKey = phoneIdentityPrivateKey,
    phoneIdentityPublicKey = phoneIdentityPublicKey,
    customName = displayName,
    pairedAt = lastPairedAt,
    lastUsedAt = lastUsedAt,
    lastResolvedSessionId = lastResolvedSessionId,
)

fun TrustedReconnectRecord.maskedMacDeviceLabel(): String =
    readMeaningfulOptionalString(displayName) ?: "Device ${maskIdentifier(macDeviceId)}"

fun TrustedReconnectRecord.maskedPhoneDeviceLabel(): String = "Phone ${maskIdentifier(phoneDeviceId)}"

fun TrustedReconnectRecord.relayDisplayLabel(): String {
    val uri = runCatching { URI(relay) }.getOrNull()
    val host = uri?.host.orEmpty()
    val port = when {
        uri == null -> -1
        uri.port >= 0 -> uri.port
        uri.scheme.equals("wss", ignoreCase = true) -> 443
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
    return when {
        host.isBlank() -> relay
        port > 0 -> "$host:$port${uri?.path.orEmpty()}"
        else -> host + uri?.path.orEmpty()
    }
}

fun TrustedReconnectRecord.publicKeyFingerprint(): String {
    return RelaySecureCrypto.shortFingerprint(macIdentityPublicKey)
}

private fun maskIdentifier(value: String, visibleTail: Int = 8): String {
    return if (value.length <= visibleTail) value else "...${value.takeLast(visibleTail)}"
}
