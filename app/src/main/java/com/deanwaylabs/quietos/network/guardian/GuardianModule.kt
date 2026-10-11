package com.deanwaylabs.quietos.network.guardian

/**
 * Guardian module interface — Android integration contract.
 *
 * DeanWay Guardian (theft protection) as a QuietOS Network module.
 * The Guardian stays a native Android app (Kotlin); this module defines
 * the contract between the Guardian app and the Network core.
 *
 * Integration points:
 *   1. EVENT REPORTING — Guardian publishes theft events to the Network.
 *      The Network claims the Attention Token so Jon's OTHER devices
 *      alert (not just the stolen one).
 *   2. EVIDENCE SHARING — Alarm photos flow through the Network's
 *      encrypted file transfer, not just via Drive.
 *   3. REMOTE CONTROL — From a paired device, Jon can arm/disarm
 *      Guardian or trigger the alarm remotely.
 *
 * Ported from the Python bench (quietos/network/guardian.py), 2026-10-11.
 * Guardian v0.3.5 is the field-proven standalone; this folds it into the
 * Network as a module per Jon's product direction (2026-10-11).
 */

/** Events the Guardian app publishes to the Network. */
enum class GuardianEvent(val value: String) {
    ARMED("armed"),                         // Protection armed
    DISARMED("disarmed"),                   // Protection disarmed by owner
    PICKUP_DETECTED("pickup_detected"),     // Motion: possible snatch, grace running
    CHALLENGE_SHOWN("challenge_shown"),     // Biometric challenge displayed
    CHALLENGE_PASSED("challenge_passed"),   // Owner verified — stand down
    CHALLENGE_FAILED("challenge_failed"),   // Non-owner failed — escalate
    ALARM_TRIGGERED("alarm_triggered"),     // Siren + photo capture active
    PHOTO_CAPTURED("photo_captured"),       // Evidence photo ready for transfer
    ALARM_STOPPED("alarm_stopped");         // Alarm silenced (owner or timeout)

    companion object {
        fun fromValue(value: String): GuardianEvent? =
            entries.firstOrNull { it.value == value }
    }
}

/** Commands the Network can send to the Guardian app. */
enum class GuardianCommand(val value: String) {
    ARM("arm"),                 // Arm protection remotely
    DISARM("disarm"),           // Disarm (requires owner auth on target)
    TRIGGER_ALARM("trigger"),   // Force alarm now (panic button from other device)
    LOCATE("locate"),           // Request current location
    STATUS("status");           // Request status snapshot

    companion object {
        fun fromValue(value: String): GuardianCommand? =
            entries.firstOrNull { it.value == value }
    }
}

/** Snapshot of Guardian state on a device. */
data class GuardianStatus(
    val deviceId: String,
    val armed: Boolean = false,
    val alarmActive: Boolean = false,
    val batteryPct: Int? = null,
    val lastEvent: GuardianEvent? = null
)

/** What the Network decides to do with a Guardian event. */
sealed class GuardianAction {
    /** Claim the Attention Token so the other device alerts. */
    data class TokenClaim(val eventId: String, val bucket: String) : GuardianAction()
    /** Yield a held token (owner verified). */
    data class TokenYield(val eventId: String) : GuardianAction()
    /** Pull an evidence photo via encrypted transfer. */
    data class RequestEvidence(val fromDevice: String, val photoId: String?) : GuardianAction()
    /** Just log it. */
    object Log : GuardianAction()
}

/**
 * Network-side Guardian module.
 *
 * Subscribes to Guardian events from paired devices, claims the
 * Attention Token for theft events, and routes evidence via
 * the encrypted file transfer channel.
 */
class GuardianModule(val deviceId: String) {

    /**
     * Handle a Guardian event from a paired device.
     * Returns the Network action taken.
     */
    fun onEvent(
        event: GuardianEvent,
        fromDevice: String,
        payload: Map<String, String> = emptyMap()
    ): GuardianAction {
        return when (event) {
            GuardianEvent.PICKUP_DETECTED ->
                // Possible theft — claim token so OTHER devices can alert.
                // The stolen phone's own alarm handles locally; the Network
                // ensures Jon's carried device also knows.
                GuardianAction.TokenClaim(
                    eventId = "guardian-pickup-$fromDevice",
                    bucket = "NOW"
                )
            GuardianEvent.ALARM_TRIGGERED ->
                GuardianAction.TokenClaim(
                    eventId = "guardian-alarm-$fromDevice",
                    bucket = "NOW"
                )
            GuardianEvent.PHOTO_CAPTURED ->
                // Evidence photo ready — pull via encrypted transfer.
                GuardianAction.RequestEvidence(
                    fromDevice = fromDevice,
                    photoId = payload["photo_id"]
                )
            GuardianEvent.CHALLENGE_PASSED ->
                // Owner verified — yield any held token.
                GuardianAction.TokenYield(
                    eventId = "guardian-pickup-$fromDevice"
                )
            else -> GuardianAction.Log
        }
    }

    /**
     * Send a command to the Guardian app on a paired device.
     * Returns the command envelope for the encrypted channel.
     */
    fun sendCommand(
        toDevice: String,
        command: GuardianCommand,
        payload: Map<String, String> = emptyMap()
    ): Map<String, Any> = mapOf(
        "type" to "guardian_command",
        "to_device" to toDevice,
        "command" to command.value,
        "payload" to payload
    )
}

/**
 * Android-side contract — implemented by the Guardian app (Kotlin).
 *
 * Event → Network → Token flow:
 *   1. Snatch detected → Guardian publishes PICKUP_DETECTED
 *   2. Network claims Attention Token (NOW bucket)
 *   3. Jon's OTHER paired device interrupts: "Possible theft on your other phone"
 *   4. Challenge fails → ALARM_TRIGGERED → token held, siren locally
 *   5. PHOTO_CAPTURED → Network pulls photo via encrypted transfer
 *   6. Owner verifies → CHALLENGE_PASSED → token yielded
 *
 * This keeps the Guardian's local theft response (siren, photo, Drive)
 * intact while adding cross-device awareness via the Network.
 */
interface GuardianBridge {
    /** Publish events to the Network (via paired encrypted channel). */
    fun publishEvent(event: GuardianEvent, payload: Map<String, String>)

    /** Receive commands from the Network. */
    fun onCommand(command: GuardianCommand, payload: Map<String, String>)

    /** Provide evidence photos for Network transfer. */
    fun getEvidencePhoto(photoId: String): ByteArray
}
