package com.deanwaylabs.quietos.network

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.deanwaylabs.quietos.network.token.Bucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * QuietOS Network Hub — the branded cross-device interface.
 *
 * Gemma sits at the center as the activator. The attention buckets
 * (NOW / SOON / DIGEST / QUIET / SPAM SHIELD) orbit as satellites
 * with side workspaces. Navy and gold throughout, Circle D, DeanWay
 * Labs identity — per Jon's brand law.
 *
 * This is the Network interface Jon described 2026-10-09: the hub
 * concept with Gemma glowing at center.
 */
class NetworkHubActivity : AppCompatActivity() {

    companion object {
        // DeanWay brand: navy + gold
        val NAVY = Color.rgb(0x0B, 0x1F, 0x3A)
        val NAVY_LIGHT = Color.rgb(0x14, 0x33, 0x5E)
        val GOLD = Color.rgb(0xC9, 0xA2, 0x27)
        val GOLD_DIM = Color.rgb(0x8A, 0x70, 0x1B)
        val TEXT_LIGHT = Color.rgb(0xEB, 0xF0, 0xFA)
        val TEXT_DIM = Color.rgb(0x9A, 0xA3, 0xB5)
    }

    private lateinit var manager: NetworkManager
    private lateinit var statusText: TextView
    private lateinit var peerText: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "QuietOS Network"
        manager = NetworkManager.get(this)
        setContentView(buildUi())
        wireCallbacks()
        refreshStatus()
    }

    // ---------- UI ----------

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val density = resources.displayMetrics.density

        fun label(text: String, size: Float = 14f, color: Int = GOLD,
                  bold: Boolean = false): TextView =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, pad / 2, 0, pad / 4)
            }

        fun button(text: String, action: () -> Unit): Button =
            Button(this).apply {
                this.text = text
                setOnClickListener { action() }
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(NAVY)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(pad, bars.top, pad, bars.bottom)
            insets
        }

        // -- DeanWay Labs header --
        root.addView(label("DEANWAY LABS", 12f, TEXT_DIM, bold = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
        })
        root.addView(label("◉  QuietOS Network", 22f, TEXT_LIGHT, bold = true).apply {
            gravity = Gravity.CENTER
        })
        root.addView(label("Cross-device attention, encrypted", 13f, TEXT_DIM).apply {
            gravity = Gravity.CENTER
        })

        // -- Gemma center (the activator) --
        root.addView(label("GEMMA", 16f, GOLD, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad / 4)
        })
        val gemmaButton = button("✦  Talk to Gemma  ✦") {
            log("Gemma voice interface: coming with the assistant brain.")
        }.apply {
            textSize = 18f
        }
        // Center the Gemma button in its own row
        val gemmaRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(gemmaButton.apply {
                layoutParams = LinearLayout.LayoutParams(
                    (260 * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }
        root.addView(gemmaRow)

        // -- Satellite buckets --
        root.addView(label("Attention Satellites", 14f, GOLD, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad / 4)
        })
        val satellites = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val bucketDefs = listOf(
            "NOW" to Bucket.NOW,
            "SOON" to Bucket.SOON,
            "DIGEST" to Bucket.DIGEST,
            "QUIET" to Bucket.QUIET
        )
        for ((name, bucket) in bucketDefs) {
            satellites.addView(button(name) { showBucketWorkspace(name, bucket) }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (4 * density).toInt()
                }
                textSize = 12f
            })
        }
        root.addView(satellites)

        // SPAM SHIELD gets its own row — it's a module, not just a bucket
        root.addView(button("🛡  SPAM SHIELD") { showSpamShield() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
        })

        // -- Network status --
        root.addView(label("Network", 14f, GOLD, bold = true))
        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(TEXT_LIGHT)
            setPadding(0, 0, 0, pad / 4)
        }
        root.addView(statusText)
        peerText = TextView(this).apply {
            textSize = 13f
            setTextColor(TEXT_DIM)
            setPadding(0, 0, 0, pad / 4)
        }
        root.addView(peerText)

        val netRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        netRow.addView(button("Pair / Setup") { showPairing() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        netRow.addView(button("Start") { startNetwork() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        netRow.addView(button("Stop") { stopNetwork() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(netRow)
        root.addView(button("Connect to peer (IP)") { showConnect() })

        // -- Cloud relay (forgotten-phone path) --
        root.addView(label("Cloud Relay", 14f, GOLD, bold = true))        val cloudRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        cloudRow.addView(button("Start cloud relay") {
            manager.startCloudRelay { ok, msg ->
                runOnUiThread {
                    log("Cloud relay: $msg")
                    refreshStatus()
                }
            }
            log("Cloud relay: starting…")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        cloudRow.addView(button("Stop cloud") {
            manager.stopCloudRelay()
            log("Cloud relay stopped.")
            refreshStatus()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(cloudRow)
        root.addView(label(
            "Phone B at home + cloud relay on = missed calls & texts " +
            "reach you anywhere. Firebase setup required (one time).",
            12f, TEXT_DIM))

        // -- Relay mode (this device is the home phone) --
        root.addView(label("Relay Mode", 14f, GOLD, bold = true))
        val relayModeButton = button("") { toggleRelayMode() }
        root.addView(relayModeButton)
        // Store for refresh
        relayModeButton.tag = "relay_mode_button"
        root.addView(label(
            "ON = this phone takes calls/texts and relays them through " +
            "SPAM SHIELD. Turn ON on Phone B, leave OFF on the phone " +
            "you carry.",
            12f, TEXT_DIM))

        // -- Guardian module --
        root.addView(button("Guardian Module") { showGuardian() })

        // -- Log --
        root.addView(label("Log", 14f, GOLD, bold = true))
        logText = TextView(this).apply {
            textSize = 13f
            setTextColor(TEXT_LIGHT)
        }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(logText)
            setBackgroundColor(NAVY_LIGHT)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        })

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    // ---------- Wiring ----------

    private fun wireCallbacks() {
        manager.onPeerChanged = { peerId, connected ->
            runOnUiThread {
                log(if (connected) "Peer connected: ${peerId.take(8)}…"
                    else "Peer disconnected: ${peerId.take(8)}…")
                refreshStatus()
            }
        }
        manager.onNetworkEvent = { eventType, payload, fromDevice ->
            runOnUiThread {
                log("Event [$eventType] from ${fromDevice.take(8)}: " +
                    payload.toString().take(120))
            }
        }
        manager.onRelayDelivery = { eventType, payload, fromDevice ->
            runOnUiThread {
                val flag = payload.optBoolean("spamshield_flag", false)
                val verdict = payload.optString("spamshield_verdict", "?")
                log("Relay [$eventType] verdict=$verdict" +
                    (if (flag) " FLAGGED" else "") +
                    " from ${fromDevice.take(8)}")
            }
        }
    }

    private fun refreshStatus() {
        statusText.text = manager.statusSummary()
        val peers = manager.peerIds
        peerText.text = if (peers.isEmpty()) "No peers connected"
            else "Peers: ${peers.joinToString(", ") { it.take(12) }}"
        updateRelayModeButton()
    }

    private fun updateRelayModeButton() {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val btn = findTaggedButton(root, "relay_mode_button")
        btn?.text = if (manager.isRelayMode)
            "Relay Mode: ON (this is the home phone)"
        else
            "Relay Mode: OFF"
    }

    private fun findTaggedButton(view: android.view.View, tag: String): Button? {
        if (view is Button && view.tag == tag) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findTaggedButton(view.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }

    private fun toggleRelayMode() {
        val nowOn = !manager.isRelayMode
        if (nowOn) {
            // Relay mode needs call-log access for missed-call detection.
            if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.READ_CALL_LOG), 42)
                log("Relay mode: requesting call-log permission…")
                return
            }
        }
        manager.setRelayMode(nowOn)
        log(if (nowOn) "Relay mode ON — this phone now relays via SPAM SHIELD."
            else "Relay mode OFF.")
        refreshStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) {
            if (grantResults.firstOrNull() ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                manager.setRelayMode(true)
                log("Relay mode ON — this phone now relays via SPAM SHIELD.")
            } else {
                log("Relay mode needs call-log access to detect missed calls.")
            }
            refreshStatus()
        }
    }

    private fun log(line: String) {
        logText.append(line + "\n")
    }

    // ---------- Actions ----------

    private fun showPairing() {
        if (manager.isPaired) {
            val d = manager.device!!
            AlertDialog.Builder(this)
                .setTitle("Paired")
                .setMessage("This device: ${d.displayName}\n" +
                    "ID: ${d.deviceId}\nKey: ${d.keyId}\n\n" +
                    "Unpair to start over with a new code.")
                .setPositiveButton("OK", null)
                .setNegativeButton("Unpair") { _, _ ->
                    manager.unpair()
                    log("Unpaired.")
                    refreshStatus()
                }
                .show()
            return
        }
        // Show a fresh code AND offer to enter one.
        val code = manager.newCodeForDisplay()
        val input = EditText(this).apply {
            hint = "Or type the other phone's code here"
            setTextColor(TEXT_LIGHT)
            setHintTextColor(TEXT_DIM)
        }
        AlertDialog.Builder(this)
            .setTitle("Pair devices")
            .setMessage("Show this code on THIS phone and type it " +
                "into the other phone.\n\nYour code:\n$code\n")
            .setView(input)
            .setPositiveButton("Pair with typed code") { _, _ ->
                val typed = input.text.toString()
                if (typed.isBlank()) {
                    log("Pairing: no code typed — showing code $code for the other phone.")
                    return@setPositiveButton
                }
                try {
                    val d = manager.pairWithCode(typed)
                    log("Paired as ${d.displayName}, key ${d.keyId}")
                } catch (e: Exception) {
                    log("Pairing failed: ${e.message}")
                }
                refreshStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
        log("Your pairing code: $code (type it into the other phone)")
    }

    private fun startNetwork() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { manager.start() }
            if (ok) log("Network node started.")
            else log("Start failed: pair this device first.")
            refreshStatus()
        }
    }

    private fun stopNetwork() {
        manager.stop()
        log("Network node stopped.")
        refreshStatus()
    }

    private fun showConnect() {
        val input = EditText(this).apply {
            hint = "192.168.x.x"
            setTextColor(TEXT_LIGHT)
            setHintTextColor(TEXT_DIM)
        }
        AlertDialog.Builder(this)
            .setTitle("Connect to peer")
            .setMessage("Enter the other phone's Wi-Fi IP address.\n" +
                "(Tip: Phone B's hotspot → Motorola joins it.)")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val host = input.text.toString().trim()
                if (host.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            manager.connectTo(host)
                        }
                        log("Connected to $host")
                    } catch (e: Exception) {
                        log("Connect failed: ${e.message}")
                    }
                    refreshStatus()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBucketWorkspace(name: String, bucket: Bucket) {
        val live = manager.tokens.liveTokens()
        val body = if (live.isEmpty()) {
            "No live token claims.\n\n" +
            "When an event arrives, the Attention Token decides which " +
            "device interrupts Jon. $name events " +
            when (bucket) {
                Bucket.NOW -> "can interrupt — but only on the token owner."
                Bucket.SOON, Bucket.DIGEST -> "digest quietly, no interrupt."
                else -> "stay on this device."
            }
        } else {
            live.entries.joinToString("\n") { (event, owner) ->
                "• $event → ${owner.take(8)}…"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("$name workspace")
            .setMessage(body)
            .setPositiveButton("BACK", null)
            .show()
    }

    private fun showSpamShield() {
        val verdicts = """
            SPAM SHIELD is ON.
            
            CLEAN → relay now
            SUSPECT → relay flagged, Jon decides
            SPAM → holds on the home phone
            
            VIPs (Noah Dean, Vanessa Brzeski, doctors) always come through.
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("🛡 SPAM SHIELD")
            .setMessage(verdicts)
            .setPositiveButton("BACK", null)
            .show()
    }

    private fun showGuardian() {
        val g = manager.guardian
        if (g == null) {
            log("Guardian module: pair first.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Guardian Module")
            .setMessage("Guardian folds into the Network here.\n\n" +
                "• Theft events claim the Attention Token\n" +
                "• Evidence photos use encrypted transfer\n" +
                "• Remote arm/disarm from a paired phone\n\n" +
                "Standalone Guardian v0.3.5 stays installed until " +
                "this module is field-proven.")
            .setPositiveButton("BACK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        // Leave the node running if it was started — Jon's call.
        super.onDestroy()
    }
}
