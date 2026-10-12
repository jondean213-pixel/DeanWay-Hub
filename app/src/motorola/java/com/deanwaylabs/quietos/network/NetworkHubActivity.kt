package com.deanwaylabs.quietos.network

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.deanwaylabs.quietos.R
import com.deanwaylabs.quietos.ai.ModelProvider
import com.deanwaylabs.quietos.ai.ModelState
import com.deanwaylabs.quietos.attention.AttentionClass
import com.deanwaylabs.quietos.attention.AttentionRecord
import com.deanwaylabs.quietos.attention.AttentionRepository
import com.deanwaylabs.quietos.messages.MessageSpamLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * QuietOS Network Hub — MOTOROLA flavor.
 *
 * Flavor override of NetworkHubActivity (same package + class name; the
 * class lives ONLY in flavor source sets, never in main — AGP treats a
 * main+flavor duplicate as a redeclaration error).
 *
 * Built to Jon's concept graphic, exactly: Gemma at center as the
 * activator, NOW / SOON / DIGEST / QUIET / SPAM SHIELD orbiting as neon
 * satellite nodes. Revision 2026-10-12 (Jon's field verdict on CI194):
 * the hub IS the app on Motorola — MainActivity auto-forwards here —
 * and EVERYTHING lives on one scrolling page. All five workspace panels
 * render inline, permanently open; tapping an orbit node smooth-scrolls
 * to its panel. The old MainActivity functions (model picker, attention
 * engine) fold into the SYSTEM panel below NETWORK. Neon/glow treatment
 * Jon-approved for this screen.
 *
 * Data honesty: lists are wired to real sources (attention log, token
 * claims, message spam log). No live source → honest empty state.
 * Nothing fake is ever presented as real.
 */
class NetworkHubActivity : AppCompatActivity() {

    // ---------- palette (from Jon's concept graphic) ----------
    private val BG_TOP = Color.rgb(0x05, 0x08, 0x14)
    private val BG_MID = Color.rgb(0x0A, 0x0F, 0x2E)
    private val BG_BOT = Color.rgb(0x14, 0x0B, 0x33)
    private val PANEL = Color.argb(242, 13, 20, 38)
    private val TEXT = Color.rgb(0xEB, 0xF0, 0xFA)
    private val DIM = Color.rgb(0x9A, 0xA3, 0xB5)
    private val GREEN = Color.rgb(0x2B, 0xFF, 0x88)   // NOW
    private val ORANGE = Color.rgb(0xFF, 0xAA, 0x33)  // SOON
    private val BLUE = Color.rgb(0x3F, 0xA9, 0xFF)    // DIGEST
    private val PURPLE = Color.rgb(0xB3, 0x66, 0xFF)  // QUIET
    private val RED = Color.rgb(0xFF, 0x44, 0x55)     // SPAM SHIELD
    private val VIOLET = Color.rgb(0xAA, 0x66, 0xFF)  // Gemma / network

    private data class BucketUi(
        val key: String,
        val title: String,
        val tagline: String,
        val accent: Int,
        val tabs: List<String>,
        val actionLabel: String
    )

    private val buckets = listOf(
        BucketUi("now", "NOW", "HANDLE IT NOW", GREEN,
            listOf("Calls", "Messages", "Alerts", "Apps"), "Clear Completed"),
        BucketUi("soon", "SOON", "FOR LATER", ORANGE,
            listOf("Scheduled", "Snoozed", "Follow Up", "Later"), "Add to SOON"),
        BucketUi("digest", "DIGEST", "BATCH & REVIEW", BLUE,
            listOf("All", "Calls", "Messages", "Apps", "Social"), "Mark as Read"),
        BucketUi("quiet", "QUIET", "KEEP IT SILENT", PURPLE,
            listOf("Blocked", "Filtered", "Settings"), "View Block List"),
        BucketUi("spam", "SPAM SHIELD", "BLOCK • LOG • PROTECT", RED,
            listOf("Calls", "Block List", "Logs", "Settings"), "Manage Spam Protection")
    )

    private data class ItemRow(val title: String, val sub: String)

    /** Max rows rendered per inline panel — the page is one scroll, no nested scrollers. */
    private val MAX_ROWS = 15

    private lateinit var manager: NetworkManager
    private var statusStrip: TextView? = null
    private var logText: TextView? = null
    private var relayModeButton: Button? = null
    private val logBuffer = StringBuilder()

    // single-page dashboard refs
    private var pageScroll: ScrollView? = null
    private val panelViews = mutableMapOf<String, View>()
    private val bucketHosts = mutableMapOf<String, LinearLayout>()
    private val bucketTabs = mutableMapOf<String, Int>()

    // inline NETWORK panel refs
    private var netStatusText: TextView? = null
    private var netPeerText: TextView? = null

    // SYSTEM panel (folded-in MainActivity functions)
    private val hubModel = ModelProvider.create()
    private val attentionRepository by lazy { AttentionRepository(this) }
    private var modelStatus: TextView? = null
    private var modelChoose: Button? = null
    private var attentionStatus: TextView? = null
    private var interceptionButton: Button? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            lifecycleScope.launch { importAndLoad(uri) }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "QuietOS Network"
        manager = NetworkManager.get(this)
        setContentView(buildUi())
        wireCallbacks()
        refreshStatus()
        lifecycleScope.launch { autoLoadExistingModel() }
    }

    // ================= single-page dashboard =================

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(BG_TOP, BG_MID, BG_BOT)
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        pageScroll = scroll
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        // ---- 1. header ----
        col.addView(centerLabel("QuietOS", 34f, TEXT, bold = true).apply {
            letterSpacing = 0.1f
        })
        col.addView(centerLabel("BY DEANWAY LABS", 12f, DIM).apply {
            letterSpacing = 0.35f
        })
        col.addView(centerLabel("FOCUS  •  CONTROL  •  PEACE", 12f, DIM).apply {
            letterSpacing = 0.3f
            setPadding(0, dp(2), 0, dp(4))
        })

        // ---- 2. orbit field (compact; nodes scroll to panels) ----
        col.addView(buildOrbit())

        // ---- network status strip (tap → scroll to NETWORK panel) ----
        statusStrip = TextView(this).apply {
            textSize = 13f
            setTextColor(DIM)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            background = roundedBg(Color.argb(160, 13, 20, 38), VIOLET, dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { scrollToPanel("network") }
        }
        col.addView(statusStrip)

        // ---- 3. all five workspace panels, inline and permanently open ----
        for (b in buckets) {
            val panel = inlinePanel(b)
            panelViews[b.key] = panel
            col.addView(panel)
        }

        // ---- 4. NETWORK panel (classic hub controls) ----
        val netPanel = buildNetworkPanel()
        panelViews["network"] = netPanel
        col.addView(netPanel)

        // ---- 5. SYSTEM panel (folded-in MainActivity functions) ----
        val sysPanel = buildSystemPanel()
        panelViews["system"] = sysPanel
        col.addView(sysPanel)

        // ---- 6. footer ----
        col.addView(centerLabel("DEANWAY LABS", 13f, TEXT, bold = true).apply {
            letterSpacing = 0.35f
            setPadding(0, dp(14), 0, 0)
        })
        col.addView(centerLabel("BUILDING A QUIETER TOMORROW", 10f, DIM).apply {
            letterSpacing = 0.25f
        })
        col.addView(centerLabel("LESS NOISE  •  MORE LIFE  •  YOU’RE IN CONTROL",
            10f, DIM).apply {
            letterSpacing = 0.15f
            setPadding(0, dp(6), 0, 0)
        })
        col.addView(centerLabel("QuietOS Alpha 0.1", 10f, DIM).apply {
            setPadding(0, dp(2), 0, 0)
        })

        scroll.addView(col)
        root.addView(scroll)
        return root
    }

    private fun scrollToPanel(key: String) {
        val v = panelViews[key] ?: return
        pageScroll?.smoothScrollTo(0, v.top)
    }

    private fun buildOrbit(): View {
        val orbit = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(470))
        }

        // Gemma medallion, centered
        val gemmaStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER)
        }
        val medallion = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(176), dp(176))
            background = gemmaRing()
            isClickable = true
            isFocusable = true
            setOnClickListener { showGemma() }
        }
        medallion.addView(ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(156), dp(156), Gravity.CENTER)
            setImageResource(R.drawable.gemma_portrait)
            contentDescription = "Gemma"
        })
        gemmaStack.addView(medallion)
        gemmaStack.addView(centerLabel("Gemma", 22f, VIOLET).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            setPadding(0, dp(4), 0, 0)
        })
        gemmaStack.addView(centerLabel("YOUR AI ASSISTANT", 11f, DIM).apply {
            letterSpacing = 0.3f
        })
        gemmaStack.addView(centerLabel("“I’ve got you.”", 13f, TEXT).apply {
            setPadding(0, dp(2), 0, 0)
        })
        orbit.addView(gemmaStack)

        // Satellite nodes — positions mirror the graphic; tap scrolls to the panel
        val nodeDefs = listOf(
            buckets[0] to (Gravity.TOP or Gravity.START),      // NOW top-left
            buckets[1] to (Gravity.TOP or Gravity.END),        // SOON top-right
            buckets[2] to (Gravity.START or Gravity.CENTER_VERTICAL), // DIGEST mid-left
            buckets[3] to (Gravity.END or Gravity.CENTER_VERTICAL),   // QUIET mid-right
            buckets[4] to (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) // SPAM SHIELD bottom-center
        )
        for ((b, grav) in nodeDefs) {
            val node = nodeButton(b)
            val lp = FrameLayout.LayoutParams(dp(96), dp(96), grav)
            val m = dp(10)
            when (grav) {
                Gravity.TOP or Gravity.START -> lp.setMargins(m, dp(4), 0, 0)
                Gravity.TOP or Gravity.END -> lp.setMargins(0, dp(4), m, 0)
                Gravity.START or Gravity.CENTER_VERTICAL -> lp.setMargins(dp(4), 0, 0, 0)
                Gravity.END or Gravity.CENTER_VERTICAL -> lp.setMargins(0, 0, dp(4), 0)
                else -> lp.setMargins(0, 0, 0, dp(2))
            }
            node.layoutParams = lp
            node.setOnClickListener { scrollToPanel(b.key) }
            orbit.addView(node)
        }
        return orbit
    }

    // ================= inline workspace panels =================

    /**
     * Workspace card rendered INLINE on the page — permanently open, like
     * the concept graphic. Tabs re-render the list in place; the list is
     * fully expanded (capped at MAX_ROWS) so the page has exactly one
     * scroller and nothing hides behind a tap.
     */
    private fun inlinePanel(b: BucketUi): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply {
                    topMargin = dp(14)
                }
            background = GradientDrawable().apply {
                setColor(PANEL)
                setStroke(dp(2), b.accent)
                cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }

        // title
        card.addView(TextView(this).apply {
            text = "${b.title} Workspace"
            textSize = 19f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(2))
        })
        card.addView(centerLabel(b.tagline, 10f, b.accent).apply {
            letterSpacing = 0.2f
            setPadding(0, 0, 0, dp(4))
        })

        // tabs
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(4), 0, dp(8))
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val pills = mutableListOf<TextView>()
        b.tabs.forEachIndexed { i, name ->
            val pill = TextView(this).apply {
                text = name
                textSize = 13f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isClickable = true
                isFocusable = true
            }
            pill.setOnClickListener {
                bucketTabs[b.key] = i
                pills.forEachIndexed { j, p -> stylePill(p, j == i, b.accent) }
                refreshInlineBucket(b)
            }
            pills += pill
            tabRow.addView(pill.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(8) }
            })
        }
        pills.forEachIndexed { j, p -> stylePill(p, j == 0, b.accent) }
        tabScroll.addView(tabRow)
        card.addView(tabScroll)

        // list area — fully expanded, capped; the PAGE scrolls, not the panel
        val listHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        bucketHosts[b.key] = listHost
        bucketTabs[b.key] = 0
        card.addView(listHost)
        refreshInlineBucket(b)

        // bottom action
        val action = Button(this).apply {
            text = b.actionLabel
            textSize = 15f
            setTextColor(TEXT)
            background = GradientDrawable().apply {
                setColor(Color.argb(90,
                    Color.red(b.accent), Color.green(b.accent), Color.blue(b.accent)))
                setStroke(dp(1), b.accent)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
            setOnClickListener { onBucketAction(b) }
        }
        card.addView(action)
        return card
    }

    private fun refreshInlineBucket(b: BucketUi) {
        val host = bucketHosts[b.key] ?: return
        val tab = bucketTabs[b.key] ?: 0
        host.removeAllViews()
        val tabName = b.tabs.getOrElse(tab) { "" }
        when (b.key) {
            "now" -> {
                val keys = when (tabName) {
                    "Calls" -> listOf("call", "phone", "missed")
                    "Messages" -> listOf("message", "sms", "text", "mms")
                    "Alerts" -> listOf("alert", "alarm")
                    else -> emptyList() // Apps: show all
                }
                val items = filterItems(nowItems(), keys).take(MAX_ROWS)
                if (items.isEmpty()) host.addView(emptyState())
                else items.forEach { host.addView(itemRow(it.title, it.sub, b.accent)) }
            }
            "soon" -> host.addView(emptyState("Nothing scheduled yet."))
            "digest" -> {
                val keys = when (tabName) {
                    "Calls" -> listOf("call", "phone")
                    "Messages" -> listOf("message", "sms", "text")
                    "Apps" -> listOf("app")
                    "Social" -> listOf("social")
                    else -> emptyList()
                }
                val items = filterItems(digestItems(), keys).take(MAX_ROWS)
                if (items.isEmpty()) host.addView(emptyState())
                else items.forEach { host.addView(itemRow(it.title, it.sub, b.accent)) }
            }
            "quiet" -> when (tabName) {
                "Blocked" -> {
                    val items = spamEntries().map { ItemRow(it.title, it.sub) }
                        .take(MAX_ROWS)
                    if (items.isEmpty()) host.addView(emptyState("Nothing blocked yet."))
                    else items.forEach { host.addView(itemRow(it.title, it.sub, b.accent)) }
                }
                "Filtered" -> {
                    val items = quietItems().take(MAX_ROWS)
                    if (items.isEmpty()) host.addView(emptyState())
                    else items.forEach { host.addView(itemRow(it.title, it.sub, b.accent)) }
                }
                else -> host.addView(infoBlock(listOf(
                    "QUIET keeps it silent.",
                    "Silenced notifications land here instead of buzzing Jon.",
                    "Nothing to configure — the attention engine decides."
                )))
            }
            "spam" -> when (tabName) {
                "Settings" -> host.addView(infoBlock(listOf(
                    "SPAM SHIELD is ON.",
                    "",
                    "CLEAN → relayed now",
                    "SUSPECT → relayed flagged, Jon decides",
                    "SPAM → held on the home phone",
                    "",
                    "VIPs (Noah Dean, Vanessa Brzeski, doctors) always come through."
                )))
                else -> {
                    val entries = spamEntries()
                    val items = (if (tabName == "Calls")
                        entries.filter {
                            it.title.contains("call", true) || it.sub.contains("call", true)
                        } else entries).take(MAX_ROWS)
                    // stats header, like the graphic
                    val stats = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        setPadding(0, 0, 0, dp(10))
                    }
                    val today = startOfToday()
                    val blockedToday = entries.count { it.timeMs >= today }
                    stats.addView(statCell("Blocked\nToday", blockedToday.toString(), b.accent))
                    stats.addView(statCell("Total\nBlocked", entries.size.toString(), b.accent))
                    host.addView(stats)
                    if (items.isEmpty()) host.addView(emptyState("Nothing blocked yet."))
                    else items.forEach {
                        host.addView(itemRow(it.title,
                            listOf(it.sub, fmtTime(it.timeMs))
                                .filter { s -> s.isNotBlank() }.joinToString("  •  "),
                            b.accent))
                    }
                }
            }
        }
    }

    private fun statCell(label: String, value: String, accent: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@NetworkHubActivity).apply {
                text = label
                textSize = 11f
                setTextColor(DIM)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@NetworkHubActivity).apply {
                text = value
                textSize = 26f
                setTextColor(TEXT)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
        }

    private fun onBucketAction(b: BucketUi) {
        when (b.key) {
            "now" -> AlertDialog.Builder(this)
                .setTitle("Clear completed")
                .setMessage("Clear the attention log? This removes the " +
                    "listed NOW items from this device.")
                .setPositiveButton("Clear") { _, _ ->
                    try { AttentionRepository(this).clear() } catch (e: Exception) { }
                    refreshInlineBucket(b)
                    toast("Attention log cleared.")
                }
                .setNegativeButton("Cancel", null)
                .show()
            "soon" -> toast("The Soon list isn’t wired yet — nothing to add.")
            "digest" -> AlertDialog.Builder(this)
                .setTitle("Mark as read")
                .setMessage("Mark all digest items read? This clears the " +
                    "attention log on this device.")
                .setPositiveButton("Mark read") { _, _ ->
                    try { AttentionRepository(this).clear() } catch (e: Exception) { }
                    refreshInlineBucket(b)
                    toast("Digest marked read.")
                }
                .setNegativeButton("Cancel", null)
                .show()
            "quiet" -> scrollToPanel("spam")
            "spam" -> AlertDialog.Builder(this)
                .setTitle("Spam Protection")
                .setMessage("SPAM SHIELD is ON.\n\nCLEAN → relay now\n" +
                    "SUSPECT → relay flagged, Jon decides\n" +
                    "SPAM → holds on the home phone\n\n" +
                    "VIPs (Noah Dean, Vanessa Brzeski, doctors) always come through.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ================= style helpers =================

    private fun centerLabel(text: String, size: Float, color: Int,
                            bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun roundedBg(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = radiusDp.toFloat()
        }

    /** Neon ring: soft glow oval behind a crisp bright oval. */
    private fun neonCircle(accent: Int): LayerDrawable {
        val glow = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(8), Color.argb(70,
                Color.red(accent), Color.green(accent), Color.blue(accent)))
            setColor(Color.TRANSPARENT)
        }
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), accent)
            setColor(Color.argb(215, 8, 12, 26))
        }
        return LayerDrawable(arrayOf(glow, ring)).apply {
            setLayerInset(1, dp(5), dp(5), dp(5), dp(5))
        }
    }

    private fun gemmaRing(): LayerDrawable {
        val halo = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(10), Color.argb(60, 170, 102, 255))
            setColor(Color.TRANSPARENT)
        }
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(3), VIOLET)
            setColor(Color.TRANSPARENT)
        }
        val inner = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), Color.argb(180, 230, 210, 255))
            setColor(Color.TRANSPARENT)
        }
        return LayerDrawable(arrayOf(halo, ring, inner)).apply {
            setLayerInset(1, dp(6), dp(6), dp(6), dp(6))
            setLayerInset(2, dp(11), dp(11), dp(11), dp(11))
        }
    }

    private fun nodeButton(b: BucketUi): FrameLayout {
        val frame = FrameLayout(this).apply { background = neonCircle(b.accent) }
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        stack.addView(TextView(this).apply {
            text = b.title
            textSize = 13f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        stack.addView(TextView(this).apply {
            text = b.tagline
            textSize = 7.5f
            setTextColor(b.accent)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        })
        frame.addView(stack)
        return frame
    }

    private fun showGemma() {
        AlertDialog.Builder(this)
            .setTitle("Gemma")
            .setMessage("Gemma voice interface: coming with the assistant brain.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun stylePill(pill: TextView, selected: Boolean, accent: Int) {
        pill.setTextColor(if (selected) Color.WHITE else DIM)
        pill.background = GradientDrawable().apply {
            if (selected) {
                setColor(Color.argb(110,
                    Color.red(accent), Color.green(accent), Color.blue(accent)))
                setStroke(dp(1), accent)
            } else {
                setColor(Color.argb(160, 20, 28, 48))
                setStroke(dp(1), Color.argb(90, 120, 130, 160))
            }
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun itemRow(title: String, sub: String, accent: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(9), dp(4), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.argb(120, 20, 28, 50))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10), 0, dp(6), 0)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(TEXT)
        })
        if (sub.isNotBlank()) texts.addView(TextView(this).apply {
            text = sub
            textSize = 12f
            setTextColor(DIM)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(texts)
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 22f
            setTextColor(accent)
            setPadding(0, 0, dp(8), 0)
        })
        return row
    }

    private fun emptyState(text: String = "Nothing here yet."): View =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(DIM)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    private fun infoBlock(lines: List<String>): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            for (l in lines) addView(TextView(this@NetworkHubActivity).apply {
                text = l
                textSize = 13.5f
                setTextColor(TEXT)
                setPadding(0, dp(4), 0, dp(4))
            })
        }

    private fun sectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(VIOLET)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(10), dp(4), dp(4))
        }

    // ---------- real data providers ----------

    private fun attentionRecords(): List<AttentionRecord> =
        try { AttentionRepository(this).readAll() } catch (e: Exception) { emptyList() }

    private fun fmtTime(ms: Long): String =
        if (ms <= 0) "" else SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ms))

    private fun nowItems(): List<ItemRow> {
        val items = mutableListOf<ItemRow>()
        try {
            manager.tokens.liveTokens().forEach { (event, owner) ->
                items += ItemRow("Token: $event", "Attention token held by ${owner.take(8)}…")
            }
        } catch (e: Exception) { /* not paired yet */ }
        attentionRecords()
            .filter { it.classification == AttentionClass.NOW }
            .forEach {
                items += ItemRow(
                    it.title.ifBlank { "Attention event" },
                    listOf(it.text.take(120), fmtTime(it.timestampMs))
                        .filter { s -> s.isNotBlank() }.joinToString("  •  "))
            }
        return items
    }

    private fun digestItems(): List<ItemRow> =
        attentionRecords()
            .filter { it.classification == AttentionClass.DIGEST ||
                    it.classification == AttentionClass.SOON }
            .map {
                ItemRow(
                    it.title.ifBlank { "Attention event" },
                    listOf(it.text.take(120), fmtTime(it.timestampMs))
                        .filter { s -> s.isNotBlank() }.joinToString("  •  "))
            }

    private fun quietItems(): List<ItemRow> =
        attentionRecords()
            .filter { it.classification == AttentionClass.QUIET }
            .map {
                ItemRow(
                    it.title.ifBlank { "Silenced notification" },
                    listOf(it.text.take(120), fmtTime(it.timestampMs))
                        .filter { s -> s.isNotBlank() }.joinToString("  •  "))
            }

    private data class SpamEntry(val title: String, val sub: String, val timeMs: Long)

    private fun spamEntries(): List<SpamEntry> =
        try {
            MessageSpamLog(this).readAll().mapNotNull { e ->
                val lines = e.lines()
                val title = lines.getOrNull(0)?.take(90) ?: return@mapNotNull null
                val sub = lines.getOrNull(1)?.take(140).orEmpty()
                val time = lines.firstOrNull { it.startsWith("Time: ") }
                    ?.substringAfter("Time: ")?.toLongOrNull() ?: 0L
                SpamEntry(title, sub, time)
            }
        } catch (e: Exception) { emptyList() }

    private fun startOfToday(): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun filterItems(items: List<ItemRow>, keys: List<String>): List<ItemRow> {
        if (keys.isEmpty()) return items
        return items.filter { r ->
            keys.any { k -> r.title.contains(k, true) || r.sub.contains(k, true) }
        }
    }

    // ================= NETWORK panel (inline, classic hub controls) =================

    private fun buildNetworkPanel(): View {
        val b = BucketUi("network", "NETWORK", "RELAY • PAIR • STATUS", VIOLET,
            emptyList(), "")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) }
            background = GradientDrawable().apply {
                setColor(PANEL)
                setStroke(dp(2), b.accent)
                cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        card.addView(TextView(this).apply {
            text = "NETWORK Workspace"
            textSize = 19f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        card.addView(centerLabel(b.tagline, 10f, b.accent).apply {
            letterSpacing = 0.2f
            setPadding(0, 0, 0, dp(6))
        })

        netStatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(TEXT)
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        card.addView(netStatusText)
        netPeerText = TextView(this).apply {
            textSize = 13f
            setTextColor(DIM)
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        card.addView(netPeerText)

        fun netButton(text: String, action: () -> Unit): Button =
            Button(this).apply {
                this.text = text
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(6) }
            }

        card.addView(netButton("Pair / Setup") { showPairing() })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Start"
            setOnClickListener { startNetwork() }
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { stopNetwork() }
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(row)
        card.addView(netButton("Connect to peer (IP)") { showConnect() })

        card.addView(sectionLabel("Cloud Relay"))
        card.addView(netButton("Start cloud relay") {
            manager.startCloudRelay { _, msg ->
                runOnUiThread {
                    log("Cloud relay: $msg")
                    refreshStatus()
                }
            }
            log("Cloud relay: starting…")
        })
        card.addView(netButton("Stop cloud") {
            manager.stopCloudRelay()
            log("Cloud relay stopped.")
            refreshStatus()
        })
        card.addView(TextView(this).apply {
            text = "Phone B at home + cloud relay on = missed calls & texts " +
                "reach you anywhere. Firebase setup required (one time)."
            textSize = 12f
            setTextColor(DIM)
            setPadding(dp(4), 0, dp(4), dp(4))
        })

        card.addView(sectionLabel("Relay Mode"))
        relayModeButton = Button(this).apply {
            setOnClickListener { toggleRelayMode() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        card.addView(relayModeButton)
        card.addView(TextView(this).apply {
            text = "ON = this phone takes calls/texts and relays them through " +
                "SPAM SHIELD. Turn ON on Phone B, leave OFF on the phone you carry."
            textSize = 12f
            setTextColor(DIM)
            setPadding(dp(4), 0, dp(4), dp(4))
        })

        card.addView(netButton("Guardian Module") { showGuardian() })

        card.addView(sectionLabel("Log"))
        logText = TextView(this).apply {
            textSize = 12f
            setTextColor(TEXT)
            text = logBuffer.toString()
        }
        card.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180))
            setBackgroundColor(Color.argb(160, 20, 28, 48))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(ScrollView(this@NetworkHubActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT)
                addView(logText)
            })
        })
        return card
    }

    // ================= SYSTEM panel (folded-in MainActivity functions) =================

    private fun buildSystemPanel(): View {
        val accent = VIOLET
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) }
            background = GradientDrawable().apply {
                setColor(PANEL)
                setStroke(dp(2), accent)
                cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        card.addView(TextView(this).apply {
            text = "SYSTEM Workspace"
            textSize = 19f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        card.addView(centerLabel("MODEL • ATTENTION ENGINE", 10f, accent).apply {
            letterSpacing = 0.2f
            setPadding(0, 0, 0, dp(6))
        })

        // ---- local model ----
        card.addView(sectionLabel("Local model"))
        modelStatus = TextView(this).apply {
            text = "Model: not loaded"
            textSize = 13f
            setTextColor(TEXT)
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        card.addView(modelStatus)
        modelChoose = Button(this).apply {
            text = "Choose model file (.litertlm)"
            setOnClickListener { picker.launch(arrayOf("*/*")) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(6) }
        }
        card.addView(modelChoose)

        // ---- attention engine ----
        card.addView(sectionLabel("Attention Engine"))
        attentionStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(TEXT)
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        card.addView(attentionStatus)
        val listenerStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(TEXT)
            text = "Listener: checking..."
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        card.addView(listenerStatus)
        card.addView(Button(this).apply {
            text = "Open notification access settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(6) }
        })
        lifecycleScope.launch {
            val enabled = NotificationManagerCompat.getEnabledListenerPackages(
                this@NetworkHubActivity).contains(packageName)
            listenerStatus.text = if (enabled) "Listener: ENABLED"
                else "Listener: DISABLED - tap above to enable"
        }
        interceptionButton = Button(this).apply {
            text = "QuietOS Attention Mode: OFF"
            setOnClickListener {
                val prefs = getSharedPreferences("quietos_attention", MODE_PRIVATE)
                val enabled = prefs.getBoolean("interception_enabled", false)
                setAttentionMode(!enabled)
            }
        }
        card.addView(interceptionButton)
        refreshAttentionStatus()
        return card
    }

    // ---------- SYSTEM: model (from MainActivity) ----------

    private suspend fun autoLoadExistingModel() {
        val existing = withContext(Dispatchers.IO) {
            File(filesDir, "models").listFiles()
                ?.filter { it.isFile && it.extension.equals("litertlm", true) }
                ?.maxByOrNull { it.lastModified() }
        } ?: return
        modelChoose?.isEnabled = false
        modelStatus?.text = "Model: loading saved ${existing.name}..."
        try {
            val metrics = hubModel.load(existing.absolutePath)
            modelStatus?.text =
                "Model READY | load ${metrics.loadTimeMs} ms | warmup ${metrics.warmupTimeMs} ms"
            log("QuietOS: saved model loaded automatically.")
        } catch (t: Throwable) {
            modelStatus?.text = "Saved model load FAILED: ${t.message ?: t.javaClass.simpleName}"
            log("QuietOS: saved model failed to load. Choose a model file to replace it.")
        } finally {
            modelChoose?.isEnabled = true
        }
    }

    private suspend fun importAndLoad(uri: Uri) {
        modelChoose?.isEnabled = false
        modelStatus?.text = "Model: importing..."
        try {
            val displayName = queryDisplayName(uri) ?: "model.litertlm"
            require(displayName.endsWith(".litertlm", ignoreCase = true)) {
                "Select a .litertlm model file."
            }
            val local = withContext(Dispatchers.IO) {
                val modelDir = File(filesDir, "models").apply { mkdirs() }
                val destination = File(modelDir, displayName)
                contentResolver.openInputStream(uri).use { source ->
                    requireNotNull(source) { "Could not open selected model." }
                    FileOutputStream(destination).use { target ->
                        source.copyTo(target, 1024 * 1024)
                    }
                }
                require(destination.length() > 0L) { "Imported model is empty." }
                destination
            }
            modelStatus?.text = "Model: loading ${local.name}..."
            val metrics = hubModel.load(local.absolutePath)
            modelStatus?.text =
                "Model READY | load ${metrics.loadTimeMs} ms | warmup ${metrics.warmupTimeMs} ms"
            log("QuietOS: model loaded locally.")
        } catch (t: Throwable) {
            modelStatus?.text = "Model load FAILED: ${t.message ?: t.javaClass.simpleName}"
            log("QuietOS: model load failed.")
        } finally {
            modelChoose?.isEnabled = true
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    // ---------- SYSTEM: attention engine (from MainActivity) ----------

    private fun setAttentionMode(enabled: Boolean) {
        getSharedPreferences("quietos_attention", MODE_PRIVATE)
            .edit()
            .putBoolean("interception_enabled", enabled)
            .apply()
        updateInterceptionButton()
        refreshAttentionStatus()
    }

    private fun updateInterceptionButton() {
        val enabled = getSharedPreferences("quietos_attention", MODE_PRIVATE)
            .getBoolean("interception_enabled", false)
        interceptionButton?.text = if (enabled) {
            "QuietOS Attention Mode: ON"
        } else {
            "QuietOS Attention Mode: OFF"
        }
    }

    private fun refreshAttentionStatus() {
        updateInterceptionButton()
        val records = try { attentionRepository.readAll() } catch (e: Exception) {
            emptyList<AttentionRecord>()
        }
        attentionStatus?.text = if (records.isEmpty()) {
            "Attention Engine: no captured notifications yet"
        } else {
            val counts = records.groupingBy { it.classification }.eachCount()
            val cancelled = records.count { it.originalCancelled }
            "Attention Engine: ${records.size} captured | NOW ${counts[AttentionClass.NOW] ?: 0} | " +
                "SOON ${counts[AttentionClass.SOON] ?: 0} | DIGEST ${counts[AttentionClass.DIGEST] ?: 0} | " +
                "QUIET ${counts[AttentionClass.QUIET] ?: 0} | intercepted $cancelled"
        }
    }

    // ---------- wiring (same behavior as the classic hub) ----------

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
        val d = manager.device
        statusStrip?.text = when {
            d == null -> "○  Not paired — tap for Network setup"
            manager.isCloudRelayActive ->
                "●  Cloud relay LIVE · ${manager.peerIds.size} peer(s) — tap for Network"
            manager.isRunning ->
                "●  Node running · ${manager.peerIds.size} peer(s) — tap for Network"
            else -> "○  Paired · node stopped — tap for Network"
        }
        statusStrip?.setTextColor(if (d == null) DIM else GREEN)

        // inline NETWORK panel fields
        netStatusText?.text = manager.statusSummary()
        val peers = manager.peerIds
        netPeerText?.text =
            if (peers.isEmpty()) "No peers connected"
            else "Peers: ${peers.joinToString(", ") { it.take(12) }}"
        relayModeButton?.text = if (manager.isRelayMode)
            "Relay Mode: ON (this is the home phone)"
        else
            "Relay Mode: OFF"
    }

    private fun log(line: String) {
        logBuffer.append(line).append('\n')
        logText?.append(line + "\n")
    }

    // ---------- actions (same behavior as the classic hub) ----------

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
        val code = manager.newCodeForDisplay()
        val input = EditText(this).apply {
            hint = "Or type the other phone's code here"
            setTextColor(TEXT)
            setHintTextColor(DIM)
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
                    val dd = manager.pairWithCode(typed)
                    log("Paired as ${dd.displayName}, key ${dd.keyId}")
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
            setTextColor(TEXT)
            setHintTextColor(DIM)
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
                        withContext(Dispatchers.IO) { manager.connectTo(host) }
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

    private fun toggleRelayMode() {
        val nowOn = !manager.isRelayMode
        if (nowOn) {
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
        refreshAttentionStatus()
    }

    override fun onDestroy() {
        // Leave the network node running if it was started — Jon's call.
        try { hubModel.close() } catch (e: Exception) { }
        super.onDestroy()
    }
}
