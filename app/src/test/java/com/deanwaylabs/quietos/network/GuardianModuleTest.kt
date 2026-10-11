package com.deanwaylabs.quietos.network

import com.deanwaylabs.quietos.network.guardian.GuardianAction
import com.deanwaylabs.quietos.network.guardian.GuardianCommand
import com.deanwaylabs.quietos.network.guardian.GuardianEvent
import com.deanwaylabs.quietos.network.guardian.GuardianModule
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the Guardian module contract.
 * Mirrors the Python bench tests (quietos/tests/test_guardian.py).
 */
class GuardianModuleTest {

    private val module = GuardianModule(deviceId = "phone-a")

    @Test fun pickupDetectedClaimsToken() {
        val action = module.onEvent(
            GuardianEvent.PICKUP_DETECTED, fromDevice = "phone-b")
        assertTrue(action is GuardianAction.TokenClaim)
        val claim = action as GuardianAction.TokenClaim
        assertEquals("guardian-pickup-phone-b", claim.eventId)
        assertEquals("NOW", claim.bucket)
    }

    @Test fun alarmTriggeredClaimsToken() {
        val action = module.onEvent(
            GuardianEvent.ALARM_TRIGGERED, fromDevice = "phone-b")
        assertTrue(action is GuardianAction.TokenClaim)
        assertEquals("guardian-alarm-phone-b",
            (action as GuardianAction.TokenClaim).eventId)
    }

    @Test fun photoCapturedRequestsEvidence() {
        val action = module.onEvent(
            GuardianEvent.PHOTO_CAPTURED, fromDevice = "phone-b",
            payload = mapOf("photo_id" to "photo-123"))
        assertTrue(action is GuardianAction.RequestEvidence)
        val req = action as GuardianAction.RequestEvidence
        assertEquals("phone-b", req.fromDevice)
        assertEquals("photo-123", req.photoId)
    }

    @Test fun challengePassedYieldsToken() {
        val action = module.onEvent(
            GuardianEvent.CHALLENGE_PASSED, fromDevice = "phone-b")
        assertTrue(action is GuardianAction.TokenYield)
        assertEquals("guardian-pickup-phone-b",
            (action as GuardianAction.TokenYield).eventId)
    }

    @Test fun benignEventsJustLog() {
        for (event in listOf(
            GuardianEvent.ARMED, GuardianEvent.DISARMED,
            GuardianEvent.CHALLENGE_SHOWN, GuardianEvent.CHALLENGE_FAILED,
            GuardianEvent.ALARM_STOPPED
        )) {
            assertEquals(GuardianAction.Log,
                module.onEvent(event, fromDevice = "phone-b"))
        }
    }

    @Test fun sendCommandBuildsEnvelope() {
        val env = module.sendCommand(
            toDevice = "phone-b",
            command = GuardianCommand.TRIGGER_ALARM,
            payload = mapOf("reason" to "panic button"))
        assertEquals("guardian_command", env["type"])
        assertEquals("phone-b", env["to_device"])
        assertEquals("trigger", env["command"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("panic button",
            (env["payload"] as Map<String, String>)["reason"])
    }

    @Test fun eventValuesMatchBench() {
        // Wire values must match the Python bench exactly.
        assertEquals("pickup_detected", GuardianEvent.PICKUP_DETECTED.value)
        assertEquals("alarm_triggered", GuardianEvent.ALARM_TRIGGERED.value)
        assertEquals("photo_captured", GuardianEvent.PHOTO_CAPTURED.value)
        assertEquals("challenge_passed", GuardianEvent.CHALLENGE_PASSED.value)
        assertEquals("trigger", GuardianCommand.TRIGGER_ALARM.value)
        assertEquals("arm", GuardianCommand.ARM.value)
    }
}
