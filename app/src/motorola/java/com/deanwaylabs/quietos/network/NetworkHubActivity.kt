package com.deanwaylabs.quietos.network

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
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
 * QuietOS Network Hub — MOTOROLA flavor, build #4.
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
 * Below the stage: the required phone controls absent from the graphic
 * (NETWORK panel, SYSTEM panel) in the same neon style. Node taps pulse
 * their adjacent panel — everything is already visible, nothing hides.
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

        init {
            setWillNotDraw(false)
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            orientation = VERTICAL
        }

        override fun onDraw(canvas: Canvas) {
            val pad = dpf(11f)
            rect.set(pad, pad, width - pad, height - pad)
            glowPaint.strokeWidth = dpf(2.5f)
            glowPaint.setShadowLayer(dpf(10f), 0f, 0f, accent)
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
     * Luminous connection lines. Endpoints are declared in GRAPHIC pixels
     * (1230x1278 space) and scaled to the view in onDraw.
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
        private val path = Path()

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun setLines(l: List<Line>) {
            lines = l
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val s = if (width > 0) width / 1230f else 1f
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
                path.quadTo(mx + bow, my, x2, y2)
                glowPaint.strokeWidth = dpf(5f)
                glowPaint.color = ln.color
                glowPaint.alpha = 210
                glowPaint.setShadowLayer(dpf(16f), 0f, 0f, ln.color)
                canvas.drawPath(path, glowPaint)
                glowPaint.clearShadowLayer()
                corePaint.strokeWidth = dpf(1.8f)
                canvas.drawPath(path, corePaint)
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
        val panelRects = mapOf(
            "now" to intArrayOf(8, 52, 354, 496),
            "soon" to intArrayOf(868, 52, 354, 496),
            "digest" to intArrayOf(8, 558, 354, 454),
            "quiet" to intArrayOf(868, 558, 354, 454),
            "spam" to intArrayOf(378, 948, 474, 284)
        )
        for (b in buckets) {
            val r = panelRects[b.key] ?: continue
            val panel = buildStagePanel(b)
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
            node.setOnClickListener { pulsePanel(b.key) }
        }

        // ---- 5. Gemma medallion, center ----
        val gemma = buildGemmaBlock()
        stage.addView(gemma, stage.PParams(425, 285, 380, 480))

        // ---- 6. footer ----
        stage.addView(TextView(this).apply {
            text = "D"
            textSize = 40f
            setTextColor(VIOLET)
            typeface = gemmaScript
            gravity = Gravity.CENTER
        }, stage.PParams(105, 1025, 130, 120))
        stage.addView(stageText("DEANWAY LABS", 8f, TEXT, bold = true, ls = 0.15f),
            stage.PParams(70, 1148, 250, 28))
        stage.addView(stageText("BUILDING A QUIETER TOMORROW", 6f, DIM, ls = 0.1f),
            stage.PParams(35, 1178, 320, 24))
        stage.addView(stageText("DeanWay LLC | Evansville, IN", 6f, DIM),
            stage.PParams(10, 1242, 320, 24))
        stage.addView(TextView(this).apply {
            text = "LESS NOISE\nMORE LIFE\nYOU'RE IN CONTROL"
            textSize = 7f
            setTextColor(DIM)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }, stage.PParams(945, 1080, 270, 95))
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
     */
    private fun buildStagePanel(b: BucketUi): NeonPanelView {
        val card = NeonPanelView(this, b.accent)
        card.setPadding(dp(7), dp(6), dp(7), dp(7))

        // title bar: ‹ TITLE Workspace        icon
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "‹"
            textSize = 13f
            setTextColor(b.accent)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(3), 0)
        })
        titleRow.addView(TextView(this).apply {
            text = "${b.title} Workspace"
            textSize = 11f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = bucketIcons[b.key] ?: "◉"
            textSize = 12f
            setTextColor(b.accent)
            gravity = Gravity.CENTER
        })
        card.addView(titleRow)

        if (b.key == "spam") {
            // stats + action (rect too short for rows at legible sizes)
            val stats = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(2))
            }
            val entries = spamEntries()
            val today = startOfToday()
            val blockedToday = entries.count { it.timeMs >= today }
            stats.addView(compactStat("Blocked\nToday", blockedToday.toString()))
            stats.addView(compactStat("Total\nBlocked", entries.size.toString()))
            card.addView(stats)
        } else {
            // tabs (first 3, like the graphic's pill row)
            val tabs = b.tabs.take(3)
            val tabScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, dp(5), 0, dp(5))
            }
            val tabRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val pills = mutableListOf<TextView>()
            tabs.forEachIndexed { i, name ->
                val pill = TextView(this).apply {
                    text = name
                    textSize = 10f
                    setPadding(dp(9), dp(4), dp(9), dp(4))
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
            pills.forEachIndexed { j, p -> stylePill(p, j == 0, b.accent) }
            tabScroll.addView(tabRow)
            card.addView(tabScroll)

            // list area — capped at STAGE_ROWS; the PAGE scrolls, not the panel
            val host = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            stageHosts[b.key] = host
            stageTabs[b.key] = 0
            card.addView(host)
            refreshStageBucket(b)
        }

        // bottom action
        val action = Button(this).apply {
            text = b.actionLabel
            textSize = 11f
            setTextColor(TEXT)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.argb(110,
                    Color.red(b.accent), Color.green(b.accent), Color.blue(b.accent)))
                setStroke(dp(1), b.accent)
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
            setOnClickListener { onBucketAction(b) }
        }
        card.addView(action)
        return card
    }

    private fun compactStat(label: String, value: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@NetworkHubActivity).apply {
                text = label
                textSize = 8f
                setTextColor(DIM)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@NetworkHubActivity).apply {
                text = value
                textSize = 15f
                setTextColor(TEXT)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
        }

    /** Compact data row: real title, single line, chevron. */
    private fun stageRow(title: String, accent: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.argb(120, 20, 28, 50))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(4) }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 11f
            setTextColor(TEXT)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 16f
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
        when (b.key) {
            "now" -> {
                val keys = when (tabName) {
                    "Calls" -> listOf("call", "phone", "missed")
                    "Messages" -> listOf("message", "sms", "text", "mms")
                    else -> emptyList() // Alerts: show all
                }
                val items = filterItems(nowItems(), keys).take(STAGE_ROWS)
                if (items.isEmpty()) host.addView(compactEmpty())
                else items.forEach { host.addView(stageRow(it.title, b.accent)) }
            }
            "soon" -> host.addView(compactEmpty("Nothing scheduled yet."))
            "digest" -> {
                val keys = when (tabName) {
                    "Calls" -> listOf("call", "phone")
                    "Messages" -> listOf("message", "sms", "text")
                    else -> emptyList() // All: show all
                }
                val items = filterItems(digestItems(), keys).take(STAGE_ROWS)
                if (items.isEmpty()) host.addView(compactEmpty())
                else items.forEach { host.addView(stageRow(it.title, b.accent)) }
            }
            "quiet" -> when (tabName) {
                "Blocked" -> {
                    val items = spamEntries().take(STAGE_ROWS)
                    if (items.isEmpty()) host.addView(compactEmpty("Nothing blocked yet."))
                    else items.forEach { host.addView(stageRow(it.title, b.accent)) }
                }
                "Filtered" -> {
                    val items = quietItems().take(STAGE_ROWS)
                    if (items.isEmpty()) host.addView(compactEmpty())
                    else items.forEach { host.addView(stageRow(it.title, b.accent)) }
                }
                else -> host.addView(compactEmpty("QUIET keeps it silent."))
            }
        }
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
        refreshStatus()
        refreshAttentionStatus()
    }

    override fun onDestroy() {
        // Leave the network node running if it was started — Jon's call.
        try { hubModel.close() } catch (e: Exception) { }
        super.onDestroy()
    }
}
