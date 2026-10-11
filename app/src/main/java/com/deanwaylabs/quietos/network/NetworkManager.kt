package com.deanwaylabs.quietos.network

import android.content.Context
import android.content.SharedPreferences
import com.deanwaylabs.quietos.network.guardian.GuardianModule
import com.deanwaylabs.quietos.network.pairing.PairedDevice
import com.deanwaylabs.quietos.network.pairing.newPairingCode
import com.deanwaylabs.quietos.network.relay.FirebaseRelay
import com.deanwaylabs.quietos.network.relay.RelayRouter
import com.deanwaylabs.quietos.network.spamshield.SpamShield
import com.deanwaylabs.quietos.network.token.Bucket
import com.deanwaylabs.quietos.network.token.TokenCoordinator
import com.deanwaylabs.quietos.network.transport.NetworkNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Owns the QuietOS Network stack for the Android app.
 *
 * Singleton lifecycle:
 *   - PairedDevice is created once from a pairing code and persisted
 *     in SharedPreferences (device id + channel key).
 *   - NetworkNode starts/stops with the app's network service.
 *   - TokenCoordinator, SpamShield, RelayRouter, GuardianModule are
 *     wired together here.
 *
 * This is the Android production home for the bench-proven Python
 * modules (255 tests passing, 2026-10-11).
 */
class NetworkManager private constructor(private val appContext: Context) {

    companion object {
        private const val PREFS = "quietos_network"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CHANNEL_KEY = "channel_key_hex"
        private const val KEY_DEVICE_NAME = "device_name"

        @Volatile
        private var instance: NetworkManager? = null

        fun get(context: Context): NetworkManager {
            return instance ?: synchronized(this) {
                instance ?: NetworkManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The paired device identity, or null if not yet paired. */
    var device: PairedDevice? = null
        private set

    var node: NetworkNode? = null
        private set

    val tokens = TokenCoordinator()
    var shield: SpamShield = SpamShield()
        private set
    var relay: RelayRouter? = null
        private set
    var guardian: GuardianModule? = null
        private set

    /** Firebase cloud relay (null until startCloudRelay succeeds). */
    var firebaseRelay: FirebaseRelay? = null
        private set

    /** True when the cloud relay is configured and listening. */
    val isCloudRelayActive: Boolean get() = firebaseRelay?.isListening == true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** (deviceId, connected) */
    var onPeerChanged: ((String, Boolean) -> Unit)? = null

    /** (eventType, payload, fromDeviceId) */
    var onNetworkEvent: ((String, JSONObject, String) -> Unit)? = null

    /** (eventType, payload, fromDeviceId) — relay deliveries. */
    var onRelayDelivery: ((String, JSONObject, String) -> Unit)? = null

    val isPaired: Boolean get() = device != null
    val isRunning: Boolean get() = node?.isRunning == true
    val peerIds: List<String> get() = node?.peerIds ?: emptyList()

    init {
        restoreDevice()
    }

    // -- pairing ----------------------------------------------------------

    /** Generate a code to SHOW on this device (the other device types it in). */
    fun newCodeForDisplay(): String = newPairingCode()

    /**
     * Pair this device using the code shown on the other device.
     * Persists the identity. Returns the PairedDevice.
     */
    fun pairWithCode(code: String, deviceName: String = ""): PairedDevice {
        val d = PairedDevice.pair(code, deviceName.ifEmpty { android.os.Build.MODEL })
        persistDevice(d)
        device = d
        wireModules()
        return d
    }

    /** Forget the pairing (unpair). Stops the node first. */
    fun unpair() {
        stop()
        stopCloudRelay()
        prefs.edit().clear().apply()
        device = null
        node = null
        relay = null
        guardian = null
        firebaseRelay = null
    }

    private fun persistDevice(d: PairedDevice) {
        prefs.edit()
            .putString(KEY_DEVICE_ID, d.deviceId)
            .putString(KEY_CHANNEL_KEY, d.channelKey.joinToString("") { "%02x".format(it) })
            .putString(KEY_DEVICE_NAME, d.displayName)
            .apply()
    }

    private fun restoreDevice() {
        val id = prefs.getString(KEY_DEVICE_ID, null) ?: return
        val keyHex = prefs.getString(KEY_CHANNEL_KEY, null) ?: return
        val name = prefs.getString(KEY_DEVICE_NAME, "") ?: ""
        val key = try {
            keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            return
        }
        if (key.size != 32) return
        device = PairedDevice(deviceId = id, channelKey = key, name = name)
        wireModules()
    }

    // -- modules ----------------------------------------------------------

    private fun wireModules() {
        val d = device ?: return
        // SPAM SHIELD: Jon's VIPs always come through. VIP numbers are
        // configured via setVipNumbers() when Jon provides them.
        // The shield defaults to empty VIPs (contacts still pass).
        guardian = GuardianModule(deviceId = d.deviceId)
        // RelayRouter is wired when the node starts (needs the node).
    }

    /** Set Jon's VIP numbers for SPAM SHIELD (Noah, Vanessa, doctors). */
    fun setVipNumbers(numbers: Collection<String>) {
        val blocked = prefs.getStringSet("blocked_prefixes", emptySet()) ?: emptySet()
        shield = SpamShield(vipNumbers = numbers, blockedPrefixes = blocked)
        rewireRelay()
    }

    private fun rewireRelay() {
        val d = device ?: return
        val n = node
        val r = RelayRouter(
            device = d,
            shield = shield,
            node = n,
            pushSend = null,  // set below when Firebase is ready
            onRelayEvent = { eventType, payload, fromDevice ->
                onRelayDelivery?.invoke(eventType, payload, fromDevice)
            }
        )
        // If the Firebase relay is already up, hook it into the new router.
        firebaseRelay?.let { fb ->
            r.pushSend = { envelope -> fb.pushSendBlocking(envelope) }
        }
        relay = r
    }

    // -- cloud relay (Firebase) -------------------------------------------

    /**
     * Start the Firebase cloud relay for forgotten-phone operation.
     *
     * This is what makes the relay work when the two phones are on
     * different networks: Phone B writes sealed envelopes to Firebase,
     * the carried phone picks them up.
     *
     * Requires google-services.json in the app (Jon creates the Firebase
     * project once — see FIREBASE_SETUP.md). If Firebase isn't
     * configured, this no-ops gracefully and local Wi-Fi still works.
     *
     * The callback receives true on success, false with a reason on failure.
     */
    fun startCloudRelay(done: (Boolean, String) -> Unit = { _, _ -> }) {
        val d = device
        if (d == null) {
            done(false, "Pair this device first.")
            return
        }
        if (firebaseRelay?.isListening == true) {
            done(true, "Cloud relay already running.")
            return
        }
        scope.launch {
            try {
                // Anonymous auth: each install gets a stable UID, no
                // account management needed.
                val authed = FirebaseRelay.ensureAuth()
                if (!authed) {
                    done(false, "Firebase auth failed. Check google-services.json.")
                    return@launch
                }
                val r = relay ?: run {
                    rewireRelay()
                    relay
                } ?: run {
                    done(false, "Relay router not ready.")
                    return@launch
                }
                val fb = FirebaseRelay(router = r, ownDeviceId = d.deviceId)
                fb.startListening()
                firebaseRelay = fb
                // Hook the push path into the live router.
                r.pushSend = { envelope -> fb.pushSendBlocking(envelope) }
                // Flush any envelopes queued while Firebase was down.
                r.retryDue()
                done(true, "Cloud relay live.")
            } catch (e: Exception) {
                // Most likely: google-services.json missing (Firebase
                // not configured yet). Local Wi-Fi is unaffected.
                done(false, "Firebase not configured: ${e.message}")
            }
        }
    }

    /** Stop the Firebase cloud relay listener. */
    fun stopCloudRelay() {
        firebaseRelay?.stopListening()
        firebaseRelay = null
        // Unhook push; the router falls back to queueing.
        relay?.pushSend = null
    }

    // -- node lifecycle ---------------------------------------------------

    /**
     * Start the network node (TCP server). Requires pairing first.
     * Returns false if not paired.
     */
    fun start(port: Int = com.deanwaylabs.quietos.network.transport.DEFAULT_PORT): Boolean {
        val d = device ?: return false
        if (node?.isRunning == true) return true
        val n = NetworkNode(device = d, port = port, tokens = tokens)
        n.onPeer = { peerId, connected ->
            onPeerChanged?.invoke(peerId, connected)
        }
        n.onEvent = { eventType, payload, fromDevice ->
            // Relay dedup lives in the router; deliver via router when present.
            val r = relay
            if (r != null &&
                (eventType == com.deanwaylabs.quietos.network.relay.MISSED_CALL ||
                 eventType == com.deanwaylabs.quietos.network.relay.SMS ||
                 eventType == com.deanwaylabs.quietos.network.relay.EMAIL_ALERT)) {
                r.receiveLocal(eventType, payload, fromDevice)
            } else {
                onNetworkEvent?.invoke(eventType, payload, fromDevice)
            }
        }
        n.start()
        node = n
        rewireRelay()
        return true
    }

    fun stop() {
        node?.stop()
        node = null
    }

    /** Full stop: local node + cloud relay. */
    fun stopAll() {
        stop()
        stopCloudRelay()
    }

    /** Dial a peer by IP. Throws on handshake failure. */
    fun connectTo(host: String, port: Int = com.deanwaylabs.quietos.network.transport.DEFAULT_PORT) {
        val n = node ?: throw IllegalStateException("Network node not started")
        n.connect(host, port)
    }

    // -- token API --------------------------------------------------------

    fun claimToken(eventId: String, bucket: Bucket): Boolean =
        node?.claimToken(eventId, bucket) ?: tokens.claim(
            eventId, device?.deviceId ?: "local", bucket)

    fun yieldToken(eventId: String): Boolean =
        node?.yieldToken(eventId) ?: tokens.yieldToken(
            eventId, device?.deviceId ?: "local")

    fun tokenOwner(eventId: String): String? =
        node?.tokenOwner(eventId) ?: tokens.owner(eventId)

    // -- events -----------------------------------------------------------

    fun sendEvent(eventType: String, payload: JSONObject = JSONObject()) {
        node?.sendEvent(eventType, payload)
            ?: onNetworkEvent?.invoke(eventType, payload, device?.deviceId ?: "local")
    }

    // -- diagnostics ------------------------------------------------------

    fun statusSummary(): String {
        val d = device
        if (d == null) return "Not paired"
        val peers = peerIds
        val pending = relay?.outboxPending()?.size ?: 0
        return buildString {
            append("Paired as ${d.displayName} (${d.deviceId.take(8)}…)\n")
            append("Key: ${d.keyId}\n")
            append("Node: ${if (isRunning) "RUNNING" else "stopped"}\n")
            append("Peers: ${if (peers.isEmpty()) "none" else peers.joinToString(", ") { it.take(8) }}\n")
            append("Cloud relay: ${if (isCloudRelayActive) "LIVE" else "off"}\n")
            if (pending > 0) append("Outbox: $pending queued\n")
        }
    }
}
