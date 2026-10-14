package com.deanwaylabs.quietos.network

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
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
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.deanwaylabs.quietos.R
import com.deanwaylabs.quietos.ai.ModelProvider
import com.deanwaylabs.quietos.ai.ModelState
import com.deanwaylabs.quietos.attention.AttentionClass
import com.deanwaylabs.quietos.attention.AttentionRecord
import com.deanwaylabs.quietos.attention.AttentionRepository
import com.deanwaylabs.quietos.calls.BlocklistStore
import com.deanwaylabs.quietos.calls.CallScreeningLog
import com.deanwaylabs.quietos.calls.ScreenedCall
import com.deanwaylabs.quietos.voice.GemmaCommandRouter
import com.deanwaylabs.quietos.voice.VoiceIntent
import com.deanwaylabs.quietos.messages.MessageSpamLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.random.Random

/**
 * QuietOS Network Hub — MOTOROLA flavor, build #5.
 *
 * FAITHFUL recreation of Jon's concept graphic (1230x1278, square).
 * Zero rearrangement: the top of the page is a "GraphicStage" — a
 * fixed-proportion section (width = screen width, height = width *
 * 1278/1230) where every element sits at its measured proportional
 * position from the graphic via PercentFrameLayout. Header, flanking
 * NOW/SOON panels, Gemma medallion, five glowing orbit nodes, luminous
 * connection lines, DIGEST/QUIET/SPAM SHIELD panels, footer — exactly
 * the graphic's composition.
 *
 * Build #5 fixes (Jon field test 2026-10-12):
 * - Panels refresh live: onResume re-reads every bucket, plus a 5s
 *   Handler loop while resumed — captures land in panels as they arrive.
 * - "email"/"mail" added to every Messages tab filter; each bucket's
 *   DEFAULT tab is a show-all tab (NOW→Alerts, DIGEST→All) so no captured
 *   item is ever invisible.
 * - Node taps are unmistakable: pulse + smooth-scroll panel to center +
 *   neon border flash.
 * - Tapping a workspace panel opens a full-screen expanded view: full tab
 *   row, full list (up to 50), same neon styling, CLOSE button.
 *
 * Below the stage: the required phone controls absent from the graphic
 * (NETWORK panel, SYSTEM panel) in the same neon style.
 *
 * Flavor override of NetworkHubActivity (same package + class name; the
 * class lives ONLY in flavor source sets, never in main — AGP treats a
 * main+flavor duplicate as a redeclaration error). Phone B untouched.
 *
 * The hub IS the app on Motorola (MainActivity auto-forwards here).
 *
 * Data honesty: lists are wired to real sources (attention log, token
 * claims, message spam log). No live source → honest empty state.
 * Nothing fake is ever presented as real.
 */
class NetworkHubActivity : AppCompatActivity() {

    // ---------- palette (saturated neon, from Jon's concept graphic) ----------
    private val TEXT = Color.rgb(0xEB, 0xF0, 0xFA)
    private val DIM = Color.rgb(0x9A, 0xA3, 0xB5)
    private val GREEN = Color.rgb(0x00, 0xFF, 0x88)   // NOW
    private val ORANGE = Color.rgb(0xFF, 0xAA, 0x00)  // SOON
    private val BLUE = Color.rgb(0x00, 0xAA, 0xFF)    // DIGEST
    private val PURPLE = Color.rgb(0xCC, 0x66, 0xFF)  // QUIET
    private val RED = Color.rgb(0xFF, 0x33, 0x44)    // SPAM SHIELD
    private val VIOLET = Color.rgb(0x8A, 0x4D, 0xFF)  // Gemma / network
    private val SCRIPT_LAV = Color.rgb(0xC9, 0xA0, 0xFF)
    private val HDR_BLUE = Color.rgb(0x4D, 0xA6, 0xFF)
    private val HDR_LAV = Color.rgb(0xB3, 0x66, 0xFF)

    private data class BucketUi(
        val key: String,
        val title: String,
        val tagline: String,
        val accent: Int,
        val tabs: List<String>,
        val actionLabel: String,
        /** Default tab: the one that shows EVERY captured item (never a filter). */
        val defaultTab: String
    )

    private val buckets = listOf(
        BucketUi("now", "NOW", "HANDLE IT NOW", GREEN,
            listOf("Calls", "Messages", "Alerts", "Apps"), "Clear Completed", "Alerts"),
        BucketUi("soon", "SOON", "FOR LATER", ORANGE,
            listOf("Scheduled", "Snoozed", "Follow Up", "Later"), "Add to SOON", "Scheduled"),
        BucketUi("digest", "DIGEST", "BATCH & REVIEW", BLUE,
            listOf("All", "Calls", "Messages", "Apps", "Social"), "Mark as Read", "All"),
        BucketUi("quiet", "QUIET", "KEEP IT SILENT", PURPLE,
            listOf("Blocked", "Filtered", "Settings"), "View Block List", "Blocked"),
        BucketUi("spam", "SPAM SHIELD", "BLOCK • LOG • PROTECT", RED,
            listOf("Calls", "Block List", "Logs", "Settings"), "Manage Spam Protection", "Calls")
    )

    private val bucketIcons = mapOf(
        "now" to "⚡",
        "soon" to "◷",
        "digest" to "☰",
        "quiet" to "☾",
        "spam" to "🛡"
    )

    private data class ItemRow(val title: String, val sub: String)

    /** Max item rows rendered per stage workspace panel. */
    private val STAGE_ROWS = 2

    private lateinit var manager: NetworkManager
    private var statusStrip: TextView? = null
    private var logText: TextView? = null
    private var relayModeButton: Button? = null
    private val logBuffer = StringBuilder()

    // GraphicStage refs
    private var pageScroll: ScrollView? = null
    private var networkPanelView: View? = null
    private val stagePanels = mutableMapOf<String, NeonPanelView>()
    private val stageNodes = mutableMapOf<String, View>()
    private val stageHosts = mutableMapOf<String, LinearLayout>()
    private val stageTabs = mutableMapOf<String, Int>()
    private var spamTodayText: TextView? = null
    private var spamTotalText: TextView? = null
    private var spamCallsHost: LinearLayout? = null
    /** Connection-lines view: drives the electrical-current animation. */
    private var stageLines: LinesView? = null
    /**
     * Runtime-computed stage row heights (dp) per bucket, derived from each
     * panel's real graphic height and the screen — Jon 2026-10-13: panel
     * content (esp. the bottom action button) must never overflow the neon
     * border on any screen size.
     */
    private val stageRowH = mutableMapOf<String, Float>()
    /** Measured host height (dp) for the SPAM SHIELD stage call rows. */
    private var spamCallHostDp = 0f

    /** Live panel refresh: re-reads sources every 5s while resumed. */
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshAllStageBuckets()
            refreshHandler.postDelayed(this, 5000)
        }
    }

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

    private val gemmaScript: Typeface by lazy {
        try {
            ResourcesCompat.getFont(this, R.font.gemma_script)
                ?: Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        } catch (e: Exception) {
            Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            lifecycleScope.launch { importAndLoad(uri) }
        }
    }

    // ---------- Gemma voice-command workspace state ----------
    private var gemmaMicListening = false
    private var gemmaSpeech: SpeechRecognizer? = null
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            gemmaMicStatus?.text =
                if (granted) "Microphone ready — tap the mic and talk."
                else "Microphone blocked — type your command below instead."
            if (granted) startGemmaListening()
        }
    // Updated by the workspace dialog while it is open; null when closed.
    private var gemmaMicStatus: TextView? = null
    private var gemmaMicButton: Button? = null
    private var gemmaLogHost: LinearLayout? = null
    private var gemmaLogScroll: ScrollView? = null
    private var gemmaHeard: TextView? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    // ================= custom visual layer =================

    /** Procedural cosmic background: near-black space, nebula blobs, starfield. */
    private inner class CosmosView(context: android.content.Context) : View(context) {
        private var bmp: Bitmap? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) return
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(Color.rgb(0x05, 0x05, 0x10))
            // nebula blobs: (x%, y%, radius%, r, g, b, alpha)
            val blobs = listOf(
                floatArrayOf(0.50f, 0.16f, 0.55f, 0x6A.toFloat(), 0x3D.toFloat(), 0xF0.toFloat(), 88f),
                floatArrayOf(0.10f, 0.44f, 0.42f, 0x2A.toFloat(), 0x1A.toFloat(), 0x8A.toFloat(), 80f),
                floatArrayOf(0.90f, 0.55f, 0.44f, 0x10.toFloat(), 0x2A.toFloat(), 0x6A.toFloat(), 80f),
                floatArrayOf(0.50f, 0.86f, 0.52f, 0x3A.toFloat(), 0x0A.toFloat(), 0x6A.toFloat(), 70f),
                floatArrayOf(0.20f, 0.07f, 0.30f, 0x0A.toFloat(), 0x2A.toFloat(), 0x6A.toFloat(), 60f),
                floatArrayOf(0.82f, 0.30f, 0.26f, 0x7A.toFloat(), 0x2B.toFloat(), 0xD0.toFloat(), 55f)
            )
            val rnd = Random(7)
            for (bl in blobs) {
                val cx = bl[0] * w
                val cy = bl[1] * h
                val rad = bl[2] * min(w, h)
                val col = Color.argb(bl[6].toInt(), bl[3].toInt(), bl[4].toInt(), bl[5].toInt())
                paint.shader = RadialGradient(cx, cy, rad, col, Color.TRANSPARENT,
                    Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, rad, paint)
            }
            paint.shader = null
            // starfield
            for (i in 0 until 150) {
                val sx = rnd.nextFloat() * w
                val sy = rnd.nextFloat() * h
                val sr = (0.6f + rnd.nextFloat() * 1.6f) * resources.displayMetrics.density / 3f
                val tint = rnd.nextFloat()
                paint.color = when {
                    tint < 0.12f -> Color.argb(200, 170, 204, 255)
                    tint < 0.20f -> Color.argb(200, 204, 153, 255)
                    else -> Color.argb(90 + rnd.nextInt(165), 255, 255, 255)
                }
                c.drawCircle(sx, sy, sr, paint)
            }
            bmp = b
        }

        override fun onDraw(canvas: Canvas) {
            bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                ?: canvas.drawColor(Color.rgb(0x05, 0x05, 0x10))
        }
    }

    /** Dark sphere with a THICK neon halo (real shadow-layer glow). */
    private inner class GlowCircleView(context: android.content.Context, val accent: Int,
                                       val haloDp: Float = 16f) : View(context) {
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = accent
        }
        private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(200, 255, 255, 255)
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val pad = dpf(haloDp) + dpf(4f)
            val r = min(width, height) / 2f - pad
            if (r <= 0) return
            glowPaint.strokeWidth = dpf(7f)
            glowPaint.setShadowLayer(dpf(haloDp), 0f, 0f, accent)
            canvas.drawCircle(cx, cy, r, glowPaint)
            glowPaint.clearShadowLayer()
            spherePaint.shader = RadialGradient(cx, cy - r * 0.25f, r * 0.15f, Color.rgb(0x2E, 0x2E, 0x58),
                Color.rgb(0x0A, 0x0A, 0x1C), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r - dpf(4f), spherePaint)
            rimPaint.strokeWidth = dpf(1.5f)
            canvas.drawCircle(cx, cy, r - dpf(4f), rimPaint)
        }
    }

    /** Glassy panel: dark translucent fill, glowing neon border, top highlight. */
    private inner class NeonPanelView(context: android.content.Context, val accent: Int)
        : LinearLayout(context) {
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = accent
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(208, 15, 17, 38)
        }
        private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(80, 255, 255, 255)
        }
        private val rect = RectF()
        private val radius = dpf(18f)
        private var flashBoost = 0f

        init {
            setWillNotDraw(false)
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            orientation = VERTICAL
        }

        /** Brief border flash — used to acknowledge a node tap. */
        fun flashBorder() {
            val anim = ValueAnimator.ofFloat(0f, 1f, 0f)
            anim.duration = 650
            anim.addUpdateListener {
                flashBoost = it.animatedValue as Float
                invalidate()
            }
            anim.start()
        }

        override fun onDraw(canvas: Canvas) {
            val pad = dpf(11f)
            rect.set(pad, pad, width - pad, height - pad)
            glowPaint.strokeWidth = dpf(2.5f + 2.5f * flashBoost)
            glowPaint.setShadowLayer(dpf(10f + 18f * flashBoost), 0f, 0f, accent)
            canvas.drawRoundRect(rect, radius, radius, glowPaint)
            glowPaint.clearShadowLayer()
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            hiPaint.strokeWidth = dpf(1f)
            canvas.drawLine(rect.left + radius, rect.top + dpf(2f),
                rect.right - radius, rect.top + dpf(2f), hiPaint)
            super.onDraw(canvas)
        }
    }

    private data class Line(val x1: Float, val y1: Float,
                            val x2: Float, val y2: Float, val color: Int)

    /**
     * Luminous connection lines with live electrical current. Endpoints are
     * declared in GRAPHIC pixels (1230x1278 space) and scaled to the view in
     * onDraw. A single linear animator drives energy pulses racing along each
     * line from Gemma's medallion out to the workspace node, in that line's
     * bucket color, with a subtle flicker and a breathing base glow. Cheap —
     * a few dozen primitives per frame — and paused with the activity, so no
     * meaningful battery cost. No new permissions, no TTS.
     */
    private inner class LinesView(context: android.content.Context) : View(context) {
        private var lines: List<Line> = emptyList()
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(200, 255, 255, 255)
        }
        private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val pulseCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val path = Path()
        private var phase = 0f
        private var currentAnim: ValueAnimator? = null

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun setLines(l: List<Line>) {
            lines = l
            invalidate()
        }

        /** Start the current animation (called from onResume). */
        fun startCurrent() {
            if (currentAnim?.isRunning == true) return
            currentAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1500L
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        /** Stop the current animation (called from onPause) — saves battery. */
        fun stopCurrent() {
            currentAnim?.cancel()
            currentAnim = null
        }

        override fun onDraw(canvas: Canvas) {
            val s = if (width > 0) width / 1230f else 1f
            // gentle "live wire" breathing on the base glow
            val breathe = 0.82f + 0.18f *
                kotlin.math.sin(phase * 2f * kotlin.math.PI.toFloat())
            for (ln in lines) {
                val x1 = ln.x1 * s
                val y1 = ln.y1 * s
                val x2 = ln.x2 * s
                val y2 = ln.y2 * s
                path.reset()
                path.moveTo(x1, y1)
                val mx = (x1 + x2) / 2f
                val my = (y1 + y2) / 2f
                // gentle outward bow
                val bow = if (x2 >= x1) -dpf(18f) else dpf(18f)
                val cx = mx + bow
                path.quadTo(cx, my, x2, y2)
                glowPaint.strokeWidth = dpf(5f)
                glowPaint.color = ln.color
                glowPaint.alpha = (210 * breathe).toInt()
                glowPaint.setShadowLayer(dpf(16f), 0f, 0f, ln.color)
                canvas.drawPath(path, glowPaint)
                glowPaint.clearShadowLayer()
                corePaint.strokeWidth = dpf(1.8f)
                canvas.drawPath(path, corePaint)
                // electrical pulses: bright arcs racing Gemma -> node
                val pulses = 3
                for (k in 0 until pulses) {
                    val t = (phase + k.toFloat() / pulses) % 1f
                    val mt = 1f - t
                    val px = mt * mt * x1 + 2 * mt * t * cx + t * t * x2
                    val py = mt * mt * y1 + 2 * mt * t * my + t * t * y2
                    val flick = 0.7f + 0.3f * kotlin.math.sin(
                        (phase * 2f + k * 0.37f) * 2f *
                            kotlin.math.PI.toFloat())
                    pulsePaint.color = ln.color
                    pulsePaint.alpha = (235 * flick).toInt()
                    pulsePaint.strokeWidth = dpf(6.5f)
                    pulsePaint.setShadowLayer(dpf(13f), 0f, 0f, ln.color)
                    canvas.drawPoint(px, py, pulsePaint)
                    pulsePaint.clearShadowLayer()
                    pulseCore.color = Color.argb((255 * flick).toInt(),
                        255, 255, 255)
                    canvas.drawCircle(px, py, dpf(2f), pulseCore)
                }
            }
        }
    }

    /** Tiny waveform bars under Gemma's name, like the graphic. */
    private inner class WaveformView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = VIOLET }
        private val heights = intArrayOf(4, 7, 10, 14, 18, 24, 30, 36, 30, 24, 18,
            14, 10, 8, 10, 14, 20, 28, 34, 28, 20, 14, 10, 7, 5, 4)

        override fun onDraw(canvas: Canvas) {
            val n = heights.size
            val gap = dpf(3f)
            val bw = dpf(3f)
            val totalW = n * (bw + gap) - gap
            var x = (width - totalW) / 2f
            val maxH = heights.maxOrNull() ?: 1
            for (h in heights) {
                val bh = height * h / maxH.toFloat()
                canvas.drawRect(x, (height - bh) / 2f, x + bw, (height + bh) / 2f, paint)
                x += bw + gap
            }
        }
    }

    /**
     * Percentage-positioned container. Children declare rects in a fixed
     * graphic-pixel space (gw x gh); the container scales them uniformly to
     * its measured width. Height is derived: h = w * gh / gw.
     */
    private inner class PercentFrameLayout(context: android.content.Context,
                                           val gw: Int, val gh: Int) : FrameLayout(context) {
        inner class PParams(val gx: Int, val gy: Int,
                            val pw: Int, val ph: Int) : FrameLayout.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT)

        override fun generateDefaultLayoutParams(): FrameLayout.LayoutParams =
            PParams(0, 0, 10, 10)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val scale = if (gw > 0 && w > 0) w / gw.toFloat() else 1f
            val h = (w * gh / gw.toFloat()).toInt()
            setMeasuredDimension(w, h)
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val lp = c.layoutParams as PParams
                c.measure(
                    MeasureSpec.makeMeasureSpec((lp.pw * scale).toInt(),
                        MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec((lp.ph * scale).toInt(),
                        MeasureSpec.EXACTLY))
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val scale = if (gw > 0 && width > 0) width / gw.toFloat() else 1f
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val lp = c.layoutParams as PParams
                val x = (lp.gx * scale).toInt()
                val y = (lp.gy * scale).toInt()
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "QuietOS Network"
        manager = NetworkManager.get(this)
        setContentView(buildUi())
        wireCallbacks()
        refreshStatus()
        lifecycleScope.launch { autoLoadExistingModel() }
    }

    // ================= page: GraphicStage + phone controls =================

    private fun buildUi(): View {
        val root = FrameLayout(this)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // cosmic background, full-bleed behind everything
        root.addView(CosmosView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
        })

        val scroll = ScrollView(this).apply { isFillViewport = true }
        pageScroll = scroll

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        // ---- the graphic, faithfully recreated, fixed proportion ----
        val stage = buildStage()
        stage.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        col.addView(stage)

        // ---- slim status strip (not in the graphic; keeps pairing state visible) ----
        statusStrip = TextView(this).apply {
            textSize = 12f
            setTextColor(DIM)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.argb(150, 15, 17, 38))
                setStroke(dp(1), VIOLET)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply {
                    leftMargin = dp(14)
                    rightMargin = dp(14)
                    topMargin = dp(8)
                    bottomMargin = dp(2)
                }
            isClickable = true
            isFocusable = true
            setOnClickListener { scrollToNetwork() }
        }
        col.addView(statusStrip)

        // ---- NETWORK panel (phone controls absent from the graphic) ----
        networkPanelView = buildNetworkPanel()
        (networkPanelView as View).layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply {
                leftMargin = dp(14)
                rightMargin = dp(14)
                topMargin = dp(8)
            }
        col.addView(networkPanelView)

        // ---- SYSTEM panel ----
        val sysPanel = buildSystemPanel()
        sysPanel.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply {
                leftMargin = dp(14)
                rightMargin = dp(14)
                topMargin = dp(10)
                bottomMargin = dp(18)
            }
        col.addView(sysPanel)

        scroll.addView(col)
        root.addView(scroll)
        return root
    }

    private fun scrollToNetwork() {
        val target = networkPanelView ?: return
        val scroll = pageScroll ?: return
        scroll.post {
            var y = 0
            var c: View? = target
            while (c != null && c !== scroll) {
                y += c.top
                c = c.parent as? View
            }
            scroll.smoothScrollTo(0, y)
        }
    }

    // ================= GraphicStage: the concept graphic, exactly =================
    // All rects below are GRAPHIC PIXELS in the 1230x1278 concept image,
    // measured directly off Jon's artwork. PercentFrameLayout scales them
    // uniformly to the phone's width. Nothing is rearranged.

    private fun stageText(text: String, sizeSp: Float, color: Int,
                          bold: Boolean = false, ls: Float = 0f,
                          gravity: Int = Gravity.CENTER): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            this.gravity = gravity
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (ls != 0f) letterSpacing = ls
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun buildStage(): View {
        val stage = PercentFrameLayout(this, 1230, 1278)

        // ---- 1. glowing connection lines (behind everything else) ----
        val lv = LinesView(this)
        stageLines = lv
        lv.setLines(listOf(
            Line(498f, 344f, 445f, 283f, GREEN),    // Gemma -> NOW
            Line(757f, 370f, 872f, 283f, ORANGE),   // Gemma -> SOON
            Line(495f, 610f, 447f, 663f, BLUE),    // Gemma -> DIGEST
            Line(759f, 583f, 868f, 663f, PURPLE),  // Gemma -> QUIET
            Line(590f, 648f, 578f, 795f, RED),     // Gemma -> SPAM SHIELD (left)
            Line(640f, 648f, 652f, 795f, RED)      // Gemma -> SPAM SHIELD (right)
        ))
        stage.addView(lv, stage.PParams(0, 0, 1230, 1278))

        // ---- 2. header ----
        stage.addView(stageText("DeanWay LLC", 8f, HDR_BLUE),
            stage.PParams(10, 6, 190, 30))
        stage.addView(stageText("Your Phone. Your Rules.", 7f, HDR_LAV),
            stage.PParams(900, 6, 320, 30))
        stage.addView(stageText("QuietOS", 18f, TEXT, bold = true),
            stage.PParams(485, 36, 260, 92))
        stage.addView(stageText("BY DEANWAY LABS", 7f, DIM, ls = 0.1f),
            stage.PParams(500, 128, 230, 28))
        stage.addView(stageText("FOCUS • CONTROL • PEACE", 7f, DIM, ls = 0.1f),
            stage.PParams(440, 156, 350, 30))

        // ---- 3. workspace panels (glassy, glowing, compact) ----
        // Jon 2026-10-13: panels a little bigger; every child (title, pills,
        // rows, the solid bottom action button) must sit fully INSIDE the
        // neon border — the buttons were hanging outside the panel lines.
        val panelRects = mapOf(
            "now" to intArrayOf(8, 40, 354, 546),
            "soon" to intArrayOf(868, 40, 354, 546),
            "digest" to intArrayOf(8, 598, 354, 499),
            "quiet" to intArrayOf(868, 598, 354, 499),
            "spam" to intArrayOf(378, 926, 474, 324)
        )
        for (b in buckets) {
            val r = panelRects[b.key] ?: continue
            val panel = buildStagePanel(b, r[3])
            stagePanels[b.key] = panel
            stage.addView(panel, stage.PParams(r[0], r[1], r[2], r[3]))
        }

        // ---- 4. orbit nodes (above panels, like the graphic) ----
        val nodeRects = mapOf(
            "now" to intArrayOf(357, 195, 176, 176),
            "soon" to intArrayOf(784, 195, 176, 176),
            "digest" to intArrayOf(365, 581, 164, 164),
            "quiet" to intArrayOf(786, 581, 164, 164),
            "spam" to intArrayOf(527, 770, 176, 176)
        )
        for (b in buckets) {
            val r = nodeRects[b.key] ?: continue
            val node = buildStageNode(b)
            stageNodes[b.key] = node
            stage.addView(node, stage.PParams(r[0], r[1], r[2], r[3]))
            node.setOnClickListener { onNodeTap(b.key) }
        }

        // ---- 5. Gemma medallion, center ----
        val gemma = buildGemmaBlock()
        stage.addView(gemma, stage.PParams(425, 285, 380, 480))

        // ---- 6. footer ----
        stage.addView(TextView(this).apply {
            text = "D"
            textSize = 32f
            setTextColor(VIOLET)
            typeface = gemmaScript
            gravity = Gravity.CENTER
        }, stage.PParams(105, 1105, 130, 85))
        stage.addView(stageText("DEANWAY LABS", 8f, TEXT, bold = true, ls = 0.15f),
            stage.PParams(70, 1195, 250, 26))
        stage.addView(stageText("BUILDING A QUIETER TOMORROW", 6f, DIM, ls = 0.1f),
            stage.PParams(35, 1223, 320, 22))
        stage.addView(stageText("DeanWay LLC | Evansville, IN", 6f, DIM),
            stage.PParams(10, 1248, 310, 22))
        stage.addView(TextView(this).apply {
            text = "LESS NOISE\nMORE LIFE\nYOU'RE IN CONTROL"
            textSize = 7f
            setTextColor(DIM)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }, stage.PParams(945, 1140, 270, 95))
        stage.addView(stageText("QuietOS Alpha 0.1", 6f, DIM),
            stage.PParams(1000, 1242, 215, 22))

        return stage
    }

    /** Gemma medallion block: violet halo, portrait, script name, waveform, quote. */
    private fun buildGemmaBlock(): View {
        // nested percentage frame: 380x480 graphic px
        val block = PercentFrameLayout(this, 380, 480)
        block.addView(GlowCircleView(this, VIOLET, 12f),
            block.PParams(12, 12, 356, 356))
        val portrait = ImageView(this).apply {
            setImageResource(R.drawable.gemma_portrait)
            contentDescription = "Gemma"
            isClickable = true
            isFocusable = true
            setOnClickListener { showGemma() }
        }
        block.addView(portrait, block.PParams(27, 27, 326, 326))
        block.addView(TextView(this).apply {
            text = "Gemma"
            textSize = 18f
            setTextColor(SCRIPT_LAV)
            typeface = gemmaScript
            gravity = Gravity.CENTER
        }, block.PParams(80, 300, 220, 70))
        block.addView(TextView(this).apply {
            text = "YOUR AI ASSISTANT"
            textSize = 7f
            setTextColor(DIM)
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
            setSingleLine()
        }, block.PParams(40, 378, 300, 26))
        block.addView(WaveformView(this),
            block.PParams(125, 408, 130, 24))
        block.addView(TextView(this).apply {
            text = "“I’ve got you.”"
            textSize = 7f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            setSingleLine()
        }, block.PParams(90, 436, 200, 30))
        return block
    }

    /** One orbit node: dark sphere, thick neon halo, icon + name + tagline. */
    private fun buildStageNode(b: BucketUi): View {
        val node = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
        }
        node.addView(GlowCircleView(this, b.accent, 6f).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
        })
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        stack.addView(TextView(this).apply {
            text = bucketIcons[b.key] ?: "◉"
            textSize = 16f
            setTextColor(b.accent)
            gravity = Gravity.CENTER
        })
        stack.addView(TextView(this).apply {
            text = b.title
            textSize = 9f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        })
        stack.addView(TextView(this).apply {
            text = b.tagline
            textSize = 6f
            setTextColor(b.accent)
            gravity = Gravity.CENTER
            setPadding(0, dp(1), 0, 0)
        })
        node.addView(stack)
        return node
    }

    /** Node tap: unmistakable — pulse, smooth-scroll its panel to center, border flash. */
    private fun onNodeTap(key: String) {
        pulsePanel(key)
        stagePanels[key]?.flashBorder()
        scrollPanelToCenter(key)
    }

    /** Smooth-scrolls the page so the tapped bucket's panel sits centered on screen. */
    private fun scrollPanelToCenter(key: String) {
        val panel = stagePanels[key] ?: return
        val scroll = pageScroll ?: return
        panel.post {
            val content = if (scroll.childCount > 0) scroll.getChildAt(0) else null
            var top = 0
            var v: View? = panel
            while (v != null && v !== content && v !== scroll) {
                top += v.top
                v = v.parent as? View
            }
            val target = (top - (scroll.height - panel.height) / 2).coerceAtLeast(0)
            scroll.smoothScrollTo(0, target)
        }
    }

    /** Node tap: pulse its adjacent panel (everything is already visible). */
    private fun pulsePanel(key: String) {
        val panel = stagePanels[key] ?: return
        panel.post {
            panel.pivotX = panel.width / 2f
            panel.pivotY = panel.height / 2f
            panel.animate().scaleX(1.05f).scaleY(1.05f).setDuration(130)
                .withEndAction {
                    panel.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                }.start()
        }
    }

    // ================= stage workspace panels (compact, faithful) =================

    /**
     * Compact workspace card at the graphic's measured position. Keeps the
     * graphic's look (glassy, glowing bucket-color border, title bar, tab
     * pills) with compact content: title, 3 tab pills, up to 2 data rows,
     * 1 action button. SPAM SHIELD shows live stats + button (its rect is
     * too short for rows at legible sizes).
     *
     * SELF-FITTING (Jon 2026-10-13): the panel's real dp height is derived
     * from its graphic rect and the screen; the chrome (title/tabs/button)
     * uses fixed compact heights and the list host takes the rest via
     * weight. The solid bottom action button is the last child, so it always
     * lands INSIDE the neon border — never hanging outside the panel lines,
     * on any screen size or density.
     */
    private fun buildStagePanel(b: BucketUi, panelGh: Int): NeonPanelView {
        val card = NeonPanelView(this, b.accent)
        // Content padding exceeds the neon border's 11dp draw inset, so every
        // child sits fully inside the border lines.
        val padSide = 10; val padTop = 9; val padBot = 12
        card.setPadding(dp(padSide), dp(padTop), dp(padSide), dp(padBot))

        val dm = resources.displayMetrics
        val panelDpH = panelGh * (dm.widthPixels / 1230f) / dm.density
        val titleDp = 19f; val tabsDp = 24f; val btnDp = 34f
        val chromeDp = padTop + padBot + titleDp + tabsDp + btnDp
        // list host gets the rest; rows split it evenly, floored for legibility
        val rowHdp = ((panelDpH - chromeDp - 4f) / STAGE_ROWS).coerceAtLeast(22f)
        stageRowH[b.key] = rowHdp

        // title bar: ‹ TITLE Workspace        icon
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(titleDp.toInt()))
        }
        titleRow.addView(TextView(this).apply {
            text = "‹"
            textSize = 11f
            setTextColor(b.accent)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(3), 0)
        })
        titleRow.addView(TextView(this).apply {
            text = "${b.title} Workspace"
            textSize = 9.5f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = bucketIcons[b.key] ?: "◉"
            textSize = 10f
            setTextColor(b.accent)
            gravity = Gravity.CENTER
        })
        card.addView(titleRow)

        if (b.key == "spam") {
            // stats + recent screened calls (with Block buttons) + action.
            // The spam rect is short: stats and the button always fit; the
            // call rows get whatever the host measures (2, 1, or 0 rows —
            // never overflowing the border).
            val statsDp = 26f
            val stats = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(statsDp.toInt()))
            }
            stats.addView(compactStat("Blocked\nToday", "0", 7f, 12f) { spamTodayText = it })
            stats.addView(compactStat("Total\nBlocked", "0", 7f, 12f) { spamTotalText = it })
            card.addView(stats)
            val callsHost = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            spamCallsHost = callsHost
            card.addView(callsHost)
            val spamChrome = padTop + padBot + titleDp + statsDp + btnDp
            spamCallHostDp = (panelDpH - spamChrome - 4f).coerceAtLeast(0f)
            refreshSpamStage()
        } else {
            // tabs (first 3, like the graphic's pill row)
            val tabs = b.tabs.take(3)
            val defaultIdx = tabs.indexOf(b.defaultTab).takeIf { it >= 0 } ?: 0
            val tabScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(tabsDp.toInt()))
            }
            val tabRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT)
            }
            val pills = mutableListOf<TextView>()
            tabs.forEachIndexed { i, name ->
                val pill = TextView(this).apply {
                    text = name
                    textSize = 9f
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    setSingleLine()
                    isClickable = true
                    isFocusable = true
                }
                pill.setOnClickListener {
                    stageTabs[b.key] = i
                    pills.forEachIndexed { j, p -> stylePill(p, j == i, b.accent) }
                    refreshStageBucket(b)
                }
                pills += pill
                tabRow.addView(pill.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { marginEnd = dp(6) }
                })
            }
            pills.forEachIndexed { j, p -> stylePill(p, j == defaultIdx, b.accent) }
            tabScroll.addView(tabRow)
            card.addView(tabScroll)

            // list area — weight 1 takes the remaining space (rows are sized
            // to fit it exactly); the PAGE scrolls, not the panel
            val host = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            stageHosts[b.key] = host
            stageTabs[b.key] = defaultIdx
            card.addView(host)
            refreshStageBucket(b)
        }

        // bottom action — fixed height and the last child, so it always
        // sits INSIDE the panel border, pinned near the panel's bottom
        val action = Button(this).apply {
            text = b.actionLabel
            textSize = 10f
            setTextColor(TEXT)
            isAllCaps = false
            minimumHeight = 0
            background = GradientDrawable().apply {
                setColor(Color.argb(110,
                    Color.red(b.accent), Color.green(b.accent), Color.blue(b.accent)))
                setStroke(dp(1), b.accent)
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(btnDp.toInt()))
                .apply { topMargin = dp(4) }
            setOnClickListener { onBucketAction(b) }
        }
        card.addView(action)

        // tap the panel → full-screen expanded view (enlargement overlay;
        // the dashboard behind stays as-is, nothing is hidden or replaced)
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { showExpandedBucket(b) }
        return card
    }

    private fun compactStat(label: String, value: String,
                            labelSp: Float = 8f, valueSp: Float = 15f,
                            onValue: (TextView) -> Unit = {}): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@NetworkHubActivity).apply {
                text = label
                textSize = labelSp
                setTextColor(DIM)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@NetworkHubActivity).apply {
                text = value
                textSize = valueSp
                setTextColor(TEXT)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                onValue(this)
            })
        }

    /**
     * Compact data row: real title, single line, chevron. On the stage the
     * row gets a fixed height (derived from the panel's measured space) so
     * rows can never push the action button outside the panel border.
     */
    private fun stageRow(title: String, accent: Int,
                         fixedHdp: Float = 0f): View {
        val h = if (fixedHdp > 0) dp(fixedHdp.toInt())
        else LinearLayout.LayoutParams.WRAP_CONTENT
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(4), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.argb(120, 20, 28, 50))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h)
                .apply { bottomMargin = dp(3) }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 9.5f
            setTextColor(TEXT)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 12f
            setTextColor(accent)
            setPadding(dp(2), 0, dp(2), 0)
        })
        return row
    }

    private fun refreshStageBucket(b: BucketUi) {
        val host = stageHosts[b.key] ?: return
        val tab = stageTabs[b.key] ?: 0
        host.removeAllViews()
        val tabs = b.tabs.take(3)
        val tabName = tabs.getOrElse(tab) { "" }
        bucketListViews(b, tabName, STAGE_ROWS, detailed = false)
            .forEach { host.addView(it) }
    }

    /**
     * Tab keyword filters. "email"/"mail" are included in every Messages
     * list so a captured email is never filtered out of every tab.
     * An empty key list means "show all" (Alerts, All, Apps, Social...).
     */
    private fun bucketTabKeys(b: BucketUi, tabName: String): List<String> = when (b.key) {
        "now" -> when (tabName) {
            "Calls" -> listOf("call", "phone", "missed")
            "Messages" -> listOf("message", "sms", "text", "mms", "email", "mail")
            else -> emptyList()
        }
        "digest" -> when (tabName) {
            "Calls" -> listOf("call", "phone")
            "Messages" -> listOf("message", "sms", "text", "email", "mail")
            else -> emptyList()
        }
        else -> emptyList()
    }

    /**
     * Row views for a bucket + tab. Shared by the compact stage panel and
     * the full-screen expanded view. `detailed` adds the subtitle line.
     */
    private fun bucketListViews(b: BucketUi, tabName: String, limit: Int,
                                detailed: Boolean,
                                onChanged: () -> Unit = {}): List<View> {
        val views = mutableListOf<View>()
        fun addItems(items: List<ItemRow>) {
            if (items.isEmpty()) views += compactEmpty()
            else items.forEach {
                views += if (detailed) detailRow(it.title, it.sub, b.accent)
                else stageRow(it.title, b.accent, stageRowH[b.key] ?: 0f)
            }
        }
        fun addSpam(items: List<SpamEntry>) {
            if (items.isEmpty()) views += compactEmpty("Nothing blocked yet.")
            else items.forEach {
                views += if (detailed) detailRow(it.title, it.sub, b.accent)
                else stageRow(it.title, b.accent, stageRowH[b.key] ?: 0f)
            }
        }
        when (b.key) {
            "now" -> addItems(filterItems(nowItems(), bucketTabKeys(b, tabName)).take(limit))
            "soon" -> views += compactEmpty("Nothing scheduled yet.")
            "digest" -> addItems(filterItems(digestItems(), bucketTabKeys(b, tabName)).take(limit))
            "quiet" -> when (tabName) {
                "Blocked" -> addSpam(spamEntries().take(limit))
                "Filtered" -> addItems(quietItems().take(limit))
                else -> views += compactEmpty("QUIET keeps it silent.")
            }
            "spam" -> when (tabName) {
                "Calls" -> views += spamCallViews(limit, detailed,
                    onChanged = onChanged)
                "Block List" -> views += blocklistViews(limit, detailed, onChanged)
                "Logs" -> addSpam(spamEntries().take(limit))
                else -> views += compactEmpty(
                    "Unknown callers are silenced — no ring, they can leave voicemail.\n" +
                    "Saved contacts always ring through.\n" +
                    "Numbers you block are rejected outright.")
            }
        }
        return views
    }

    /** Two-line data row for the expanded view: title + subtitle. */
    private fun detailRow(title: String, sub: String, accent: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(120, 20, 28, 50))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(6) }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
        })
        if (sub.isNotBlank()) row.addView(TextView(this).apply {
            text = sub
            textSize = 12f
            setTextColor(DIM)
            setPadding(0, dp(2), 0, 0)
        })
        return row
    }

    /** Re-reads the spam log into the stage SPAM SHIELD stats + recent screened calls. */
    private fun refreshSpamStage() {
        val entries = spamEntries()
        spamTodayText?.text = entries.count { it.timeMs >= startOfToday() }.toString()
        // Total Blocked counts screened calls AND Jon's manual blocklist —
        // Jon 2026-10-13: it read 0 with a number blocked.
        val blockedCount = BlocklistStore(this).all().size
        spamTotalText?.text = (entries.size + blockedCount).toString()
        val host = spamCallsHost ?: return
        host.removeAllViews()
        // Fit the call rows into the measured host: 2 rows when they fit,
        // else 1, else stats+button only — never overflowing the border.
        val minRowDp = 28f
        val show = when {
            spamCallHostDp >= minRowDp * 2 -> 2
            spamCallHostDp >= minRowDp -> 1
            else -> 0
        }
        if (show > 0) {
            spamCallViews(limit = show, detailed = false,
                rowHdp = spamCallHostDp / show) { refreshSpamStage() }
                .forEach { host.addView(it) }
        }
    }

    /**
     * Screened-call rows with Block/Unblock toggle buttons. Shared by the
     * compact stage panel and the full-screen expanded view.
     */
    private fun spamCallViews(limit: Int, detailed: Boolean,
                              rowHdp: Float = 0f,
                              onChanged: () -> Unit): List<View> {
        val accent = buckets.first { it.key == "spam" }.accent
        val store = BlocklistStore(this)
        val calls = screenedCalls().take(limit)
        if (calls.isEmpty()) return listOf(compactEmpty("No screened calls yet."))
        return calls.map { call ->
            spamCallRow(call, accent, detailed, store, rowHdp, onChanged)
        }
    }

    private fun spamCallRow(call: ScreenedCall, accent: Int, detailed: Boolean,
                            store: BlocklistStore, rowHdp: Float = 0f,
                            onChanged: () -> Unit): View {
        val blocked = store.isBlocked(call.number)
        // Old log entries stored masked numbers ("***1234") — show them, but no Block button.
        val canBlock = !call.number.startsWith("***") &&
            BlocklistStore.normalize(call.number).isNotEmpty()
        // Fixed height on the stage: the row can never push the action
        // button outside the panel border.
        val fixed = rowHdp > 0f
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(if (fixed) 2 else 4), dp(6), dp(if (fixed) 2 else 4))
            background = GradientDrawable().apply {
                setColor(Color.argb(120, 20, 28, 50))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (fixed) dp(rowHdp.toInt())
                else LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(if (fixed) 2 else 4) }
        }
        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textBox.addView(TextView(this).apply {
            text = (if (blocked) "🚫 " else "") +
                call.number.ifBlank { "Hidden caller" }
            textSize = if (detailed) 14f else if (fixed) 10f else 11f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        })
        val sub = listOf(call.risk.lowercase(), call.reason, fmtTime(call.timeMs))
            .filter { it.isNotBlank() }.joinToString("  •  ")
        textBox.addView(TextView(this).apply {
            text = sub
            textSize = if (detailed) 12f else if (fixed) 8.5f else 9f
            setTextColor(DIM)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(textBox)
        if (canBlock) {
            row.addView(Button(this).apply {
                text = if (blocked) "Unblock" else "Block"
                textSize = if (detailed) 12f else if (fixed) 9f else 10f
                setTextColor(TEXT)
                isAllCaps = false
                minimumWidth = 0
                if (fixed) minimumHeight = 0
                setPadding(dp(8), dp(if (fixed) 1 else 2),
                    dp(8), dp(if (fixed) 1 else 2))
                background = GradientDrawable().apply {
                    setColor(Color.argb(if (blocked) 150 else 90,
                        Color.red(accent), Color.green(accent), Color.blue(accent)))
                    setStroke(dp(1), accent)
                    cornerRadius = dp(8).toFloat()
                }
                // A clickable child consumes the tap, so the panel's
                // "open expanded view" listener won't fire on Block taps.
                setOnClickListener {
                    if (store.isBlocked(call.number)) {
                        store.remove(call.number)
                        toast("Unblocked ${call.number}.")
                    } else {
                        store.add(call.number)
                        toast("Blocked ${call.number} — future calls rejected.")
                    }
                    onChanged()
                }
            })
        }
        return row
    }

    /**
     * Manually blocked numbers with an Unblock affordance each.
     * Shown on the SPAM SHIELD "Block List" tab.
     */
    private fun blocklistViews(limit: Int, detailed: Boolean,
                               onChanged: () -> Unit): List<View> {
        val accent = buckets.first { it.key == "spam" }.accent
        val store = BlocklistStore(this)
        val numbers = store.all().take(limit)
        if (numbers.isEmpty()) return listOf(compactEmpty("No blocked numbers yet."))
        return numbers.map { number ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.argb(120, 20, 28, 50))
                    cornerRadius = dp(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
            }
            row.addView(TextView(this).apply {
                text = "🚫 $number"
                textSize = if (detailed) 14f else 11f
                setTextColor(TEXT)
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "Unblock"
                textSize = if (detailed) 12f else 10f
                setTextColor(TEXT)
                isAllCaps = false
                minimumWidth = 0
                setPadding(dp(10), dp(2), dp(10), dp(2))
                background = GradientDrawable().apply {
                    setColor(Color.argb(150,
                        Color.red(accent), Color.green(accent), Color.blue(accent)))
                    setStroke(dp(1), accent)
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener {
                    store.remove(number)
                    toast("Unblocked $number.")
                    onChanged()
                }
            })
            row
        }
    }

    /** Re-reads all live sources into every stage panel. Safe to call repeatedly. */
    private fun refreshAllStageBuckets() {
        for (b in buckets) {
            if (b.key == "spam") refreshSpamStage()
            else refreshStageBucket(b)
        }
        refreshAttentionStatus()
    }

    private fun compactEmpty(text: String = "Nothing here yet."): View =
        TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(DIM)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    // ================= expanded workspace view =================

    /**
     * Full-screen expanded view of a workspace panel (enlargement overlay).
     * Same bucket, FULL tab row (all tabs), FULL list (up to 50), same neon
     * styling, clear CLOSE button. The dashboard behind is untouched —
     * nothing is hidden or replaced. Tapping a tab filters in place.
     */
    private fun showExpandedBucket(b: BucketUi) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0x05, 0x05, 0x10))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val card = NeonPanelView(this, b.accent).apply {
            setPadding(dp(16), dp(14), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT)
        }

        card.addView(TextView(this).apply {
            text = "${b.title} Workspace"
            textSize = 20f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(2))
        })
        card.addView(centerLabel(b.tagline, 11f, b.accent).apply {
            letterSpacing = 0.2f
            setPadding(0, 0, 0, dp(8))
        })

        // full tab row (ALL tabs, not just the stage's first 3)
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(8))
        }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var selTab = b.tabs.indexOf(b.defaultTab).takeIf { it >= 0 } ?: 0
        val pills = mutableListOf<TextView>()
        fun renderList() {
            listHost.removeAllViews()
            val tabName = b.tabs.getOrElse(selTab) { "" }
            if (b.key == "spam" && tabName == "Calls") {
                val entries = spamEntries()
                val statsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(8))
                }
                statsRow.addView(compactStat("Blocked\nToday",
                    entries.count { it.timeMs >= startOfToday() }.toString()))
                statsRow.addView(compactStat("Total\nBlocked",
                    (entries.size + BlocklistStore(this).all().size).toString()))
                listHost.addView(statsRow)
            }
            bucketListViews(b, tabName, 50, detailed = true) { renderList() }
                .forEach { listHost.addView(it) }
        }
        b.tabs.forEachIndexed { i, name ->
            val pill = TextView(this).apply {
                text = name
                textSize = 13f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setSingleLine()
                isClickable = true
                isFocusable = true
            }
            pill.setOnClickListener {
                selTab = i
                pills.forEachIndexed { j, p -> stylePill(p, j == i, b.accent) }
                renderList()
            }
            pills += pill
            tabRow.addView(pill.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(8) }
            })
        }
        pills.forEachIndexed { j, p -> stylePill(p, j == selTab, b.accent) }
        tabScroll.addView(tabRow)
        card.addView(tabScroll)

        // scrollable full list (its own scroller; the dialog is fixed)
        val listScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(listHost)
        }
        card.addView(listScroll)
        renderList()

        card.addView(Button(this).apply {
            text = "CLOSE"
            textSize = 14f
            setTextColor(TEXT)
            background = GradientDrawable().apply {
                setColor(Color.argb(110, Color.red(b.accent),
                    Color.green(b.accent), Color.blue(b.accent)))
                setStroke(dp(1), b.accent)
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
            setOnClickListener { dialog.dismiss() }
        })

        root.addView(card)
        dialog.setContentView(root)
        dialog.window?.setLayout(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawable(
            ColorDrawable(Color.rgb(0x05, 0x05, 0x10)))
        dialog.show()
    }

    // ================= shared style helpers =================

    private fun centerLabel(text: String, size: Float, color: Int,
                            bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    // ================= Gemma voice-command workspace =================
    // Jon's design (2026-10-12): tap the medallion -> her workspace opens ->
    // tap-to-talk button + text input inside. She hears, she acts, the
    // workspace confirms visually. Command layer, not a chatbot: no TTS.

    private fun showGemma() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener {
            stopGemmaListening()
            gemmaMicStatus = null
            gemmaMicButton = null
            gemmaLogHost = null
            gemmaLogScroll = null
            gemmaHeard = null
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0x05, 0x05, 0x10))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val card = NeonPanelView(this, VIOLET).apply {
            setPadding(dp(16), dp(14), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT)
        }

        card.addView(TextView(this).apply {
            text = "Gemma"
            textSize = 20f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(2))
        })
        card.addView(centerLabel("VOICE COMMAND", 11f, VIOLET).apply {
            letterSpacing = 0.2f
            setPadding(0, 0, 0, dp(8))
        })

        val brainLine = when (hubModel.state) {
            ModelState.READY -> "Brain: READY — on-device 1.7B"
            ModelState.LOADING -> "Brain: still loading — keyword commands work now."
            else -> "Brain: not loaded — keyword commands work; free-form needs the model."
        }
        card.addView(centerLabel(brainLine, 12f, DIM).apply {
            setPadding(0, 0, 0, dp(4))
        })

        val micStatus = TextView(this).apply {
            text = "Tap the mic and talk, or type below."
            textSize = 12f
            setTextColor(DIM)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        gemmaMicStatus = micStatus
        card.addView(micStatus)

        val heard = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            minHeight = dp(30)
        }
        gemmaHeard = heard
        card.addView(heard)

        val micBtn = Button(this).apply {
            text = "🎤 TAP TO TALK"
            textSize = 16f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.argb(150, Color.red(VIOLET),
                    Color.green(VIOLET), Color.blue(VIOLET)))
                setStroke(dp(2), VIOLET)
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4); bottomMargin = dp(8) }
            setOnClickListener { onGemmaMicTap() }
        }
        gemmaMicButton = micBtn
        card.addView(micBtn)

        // text input fallback
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "Type a command — “block 812 555 1234”"
            setHintTextColor(DIM)
            setTextColor(TEXT)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = GradientDrawable().apply {
                setColor(Color.argb(160, 20, 28, 48))
                setStroke(dp(1), Color.argb(90, 120, 130, 160))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val sendBtn = Button(this).apply {
            text = "SEND"
            textSize = 13f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.argb(150, Color.red(VIOLET),
                    Color.green(VIOLET), Color.blue(VIOLET)))
                setStroke(dp(1), VIOLET)
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8) }
            setOnClickListener {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    input.setText("")
                    handleGemmaText(t)
                }
            }
        }
        inputRow.addView(input)
        inputRow.addView(sendBtn)
        card.addView(inputRow)

        card.addView(centerLabel("COMMAND LOG", 10f, DIM).apply {
            letterSpacing = 0.2f
            setPadding(0, dp(10), 0, dp(4))
        })
        val logHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        gemmaLogHost = logHost
        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(logHost)
        }
        gemmaLogScroll = logScroll
        card.addView(logScroll)

        card.addView(Button(this).apply {
            text = "CLOSE"
            textSize = 14f
            setTextColor(TEXT)
            background = GradientDrawable().apply {
                setColor(Color.argb(110, Color.red(VIOLET),
                    Color.green(VIOLET), Color.blue(VIOLET)))
                setStroke(dp(1), VIOLET)
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
            setOnClickListener { dialog.dismiss() }
        })

        gemmaLog("Say “block” + a number, or “call” + a name or number.")
        root.addView(card)
        dialog.setContentView(root)
        dialog.window?.setLayout(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawable(
            ColorDrawable(Color.rgb(0x05, 0x05, 0x10)))
        dialog.show()
    }

    private fun gemmaLog(line: String): TextView? {
        val host = gemmaLogHost ?: return null
        val tv = TextView(this).apply {
            text = line
            textSize = 13f
            setTextColor(TEXT)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        host.addView(tv)
        gemmaLogScroll?.post { gemmaLogScroll?.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    // ---------- mic handling ----------

    private fun onGemmaMicTap() {
        if (gemmaMicListening) {
            stopGemmaListening()
            gemmaMicStatus?.text = "Stopped. Tap the mic to talk again, or type below."
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            gemmaMicStatus?.text =
                "Speech recognition isn't on this phone — type your command below."
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) {
            startGemmaListening()
        } else {
            gemmaMicStatus?.text = "QuietOS needs the microphone to hear you."
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startGemmaListening() {
        if (gemmaMicListening) return
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        gemmaSpeech = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                setGemmaMicUi(true, "Listening… talk now.")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (gemmaMicListening) gemmaMicStatus?.text = "Heard you — working…"
            }
            override fun onError(error: Int) {
                stopGemmaListening()
                gemmaMicStatus?.text =
                    "Didn't catch that — tap to try again, or type below."
            }
            override fun onResults(results: Bundle?) {
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                stopGemmaListening()
                if (heard.isBlank()) {
                    gemmaMicStatus?.text =
                        "Didn't catch that — tap to try again, or type below."
                } else {
                    gemmaMicStatus?.text = "Tap the mic and talk, or type below."
                    handleGemmaText(heard)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val part = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!part.isNullOrBlank()) gemmaHeard?.text = "“$part…”"
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val ri = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        gemmaMicListening = true
        setGemmaMicUi(true, "Starting…")
        runCatching { sr.startListening(ri) }.onFailure {
            stopGemmaListening()
            gemmaMicStatus?.text = "Couldn't start listening — type your command below."
        }
    }

    private fun stopGemmaListening() {
        gemmaMicListening = false
        runCatching { gemmaSpeech?.stopListening() }
        runCatching { gemmaSpeech?.destroy() }
        gemmaSpeech = null
        gemmaMicButton?.text = "🎤 TAP TO TALK"
    }

    private fun setGemmaMicUi(listening: Boolean, status: String) {
        gemmaMicListening = listening
        gemmaMicButton?.text = if (listening) "🔴 TAP TO STOP" else "🎤 TAP TO TALK"
        gemmaMicStatus?.text = status
    }

    // ---------- command handling ----------

    private fun handleGemmaText(text: String) {
        gemmaHeard?.text = ""
        gemmaLog("Heard: “$text”")
        val router = GemmaCommandRouter(llmParse = { utterance ->
            if (hubModel.state != ModelState.READY) null
            else hubModel.send(
                "Parse this voice command into exactly one line like: " +
                    "INTENT: block | TARGET: 8125551234\n" +
                    "Valid intents: block, unblock, call, unknown. " +
                    "TARGET is phone number digits or a contact name, or empty.\n" +
                    "Command: \"$utterance\""
            ).text
        })
        lifecycleScope.launch {
            val thinking = gemmaLog("…")
            val cmd = router.parse(text)
            thinking?.let { gemmaLogHost?.removeView(it) }
            executeVoiceCommand(cmd)
        }
    }

    private fun executeVoiceCommand(cmd: com.deanwaylabs.quietos.voice.VoiceCommand) {
        when (cmd.intent) {
            VoiceIntent.BLOCK -> {
                val n = BlocklistStore.normalize(cmd.target)
                if (n.length < 7) {
                    gemmaLog("→ I need a number — say “block” followed by the digits.")
                } else {
                    val added = BlocklistStore(this).add(n)
                    gemmaLog(if (added) "→ Blocked ${prettyNum(n)} — future calls rejected."
                    else "→ ${prettyNum(n)} was already on the block list.")
                }
            }
            VoiceIntent.UNBLOCK -> {
                val n = BlocklistStore.normalize(cmd.target)
                if (n.length < 7) {
                    gemmaLog("→ I need a number — say “unblock” followed by the digits.")
                } else {
                    val removed = BlocklistStore(this).remove(n)
                    gemmaLog(if (removed) "→ Unblocked ${prettyNum(n)}."
                    else "→ ${prettyNum(n)} wasn't on the block list.")
                }
            }
            VoiceIntent.CALL -> {
                val digits = cmd.target.filter { it.isDigit() }
                when {
                    digits.length >= 7 -> {
                        gemmaLog("→ Opening dialer for ${prettyNum(digits)}…")
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
                    }
                    cmd.target.isBlank() ->
                        gemmaLog("→ Who should I call — say “call” followed by a name or number.")
                    else -> lifecycleScope.launch {
                        val found = withContext(Dispatchers.IO) {
                            lookupContactNumber(cmd.target)
                        }
                        if (found != null) {
                            gemmaLog("→ Opening dialer for ${cmd.target.trim()}…")
                            startActivity(Intent(Intent.ACTION_DIAL,
                                Uri.parse("tel:${found.filter { it.isDigit() }}")))
                        } else {
                            gemmaLog("→ I couldn't find “${cmd.target.trim()}” in your contacts.")
                        }
                    }
                }
            }
            VoiceIntent.UNKNOWN ->
                gemmaLog("→ I didn't catch a command in that — try “block …” or “call …”.")
        }
    }

    private fun lookupContactNumber(name: String): String? = runCatching {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%${name.trim()}%"),
            null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun prettyNum(digits: String): String {
        val d = digits.filter { it.isDigit() }
        return if (d.length == 10)
            "(${d.substring(0, 3)}) ${d.substring(3, 6)}-${d.substring(6)}"
        else d
    }

    private fun stylePill(pill: TextView, selected: Boolean, accent: Int) {
        pill.setTextColor(if (selected) Color.WHITE else DIM)
        pill.background = GradientDrawable().apply {
            if (selected) {
                setColor(Color.argb(150,
                    Color.red(accent), Color.green(accent), Color.blue(accent)))
                setStroke(dp(2), accent)
            } else {
                setColor(Color.argb(160, 20, 28, 48))
                setStroke(dp(1), Color.argb(90, 120, 130, 160))
            }
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun sectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(VIOLET)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(10), dp(4), dp(4))
        }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun onBucketAction(b: BucketUi) {
        when (b.key) {
            "now" -> AlertDialog.Builder(this)
                .setTitle("Clear completed")
                .setMessage("Clear the attention log? This removes the " +
                    "listed NOW items from this device.")
                .setPositiveButton("Clear") { _, _ ->
                    try { AttentionRepository(this).clear() } catch (e: Exception) { }
                    refreshStageBucket(b)
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
                    refreshStageBucket(b)
                    toast("Digest marked read.")
                }
                .setNegativeButton("Cancel", null)
                .show()
            "quiet" -> pulsePanel("spam")
            "spam" -> AlertDialog.Builder(this)
                .setTitle("Spam Protection")
                .setMessage("SPAM SHIELD is ON.\n\nCLEAN → relay now\n" +
                    "SUSPECT → relay flagged, Jon decides\n" +
                    "SPAM → held on the home phone\n\n" +
                    "VIPs (Noah Dean, Vanessa Brzeski, doctors) always come through.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ---------- real data providers ----------

    private fun attentionRecords(): List<AttentionRecord> =
        try { AttentionRepository(this).readAll() } catch (e: Exception) { emptyList() }

    private fun fmtTime(ms: Long): String =
        if (ms <= 0L) "" else SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ms))

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

    /** Screened incoming calls (CallScreeningService log), newest first. */
    private fun screenedCalls(): List<ScreenedCall> =
        try { CallScreeningLog(this).readAll() } catch (e: Exception) { emptyList() }

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

    // ================= NETWORK panel (phone controls, neon style) =================

    private fun buildNetworkPanel(): View {
        val accent = VIOLET
        val card = NeonPanelView(this, accent).apply {
            setPadding(dp(22), dp(20), dp(22), dp(22))
        }
        card.addView(TextView(this).apply {
            text = "NETWORK Workspace"
            textSize = 19f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        card.addView(centerLabel("RELAY • PAIR • STATUS", 10f, accent).apply {
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
        val card = NeonPanelView(this, accent).apply {
            setPadding(dp(22), dp(20), dp(22), dp(22))
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
        stageLines?.startCurrent()
        refreshStatus()
        refreshAllStageBuckets()
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 5000)
    }

    override fun onPause() {
        stageLines?.stopCurrent()
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        // Leave the network node running if it was started — Jon's call.
        try { hubModel.close() } catch (e: Exception) { }
        super.onDestroy()
    }
}
