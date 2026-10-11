package com.deanwaylabs.quietos

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.deanwaylabs.quietos.ai.ModelProvider
import com.deanwaylabs.quietos.ai.ModelState
import com.deanwaylabs.quietos.attention.AttentionClass
import com.deanwaylabs.quietos.attention.AttentionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * QuietOS stripped build: basic interface.
 * Attention engine, no-spam message/call protection, local model chat.
 * No voice, no branding, no showpiece. A quiet tool.
 */
class MainActivity : AppCompatActivity() {
    private val model = ModelProvider.create()
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var choose: Button
    private lateinit var attentionStatus: TextView
    private lateinit var interceptionButton: Button
    private val attentionRepository by lazy { AttentionRepository(this) }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            lifecycleScope.launch { importAndLoad(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "QuietOS"
        setContentView(buildUi())
        lifecycleScope.launch { autoLoadExistingModel() }
    }

    // ---------- UI ----------

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val bg = android.graphics.Color.rgb(2, 7, 22)
        val gold = android.graphics.Color.rgb(212, 175, 55)
        val textColor = android.graphics.Color.rgb(235, 240, 250)

        fun label(text: String, size: Float = 14f, color: Int = gold): TextView =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
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
            setBackgroundColor(bg)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(pad, bars.top, pad, bars.bottom)
            insets
        }

        // Header
        root.addView(label("QuietOS", 22f, textColor))
        status = TextView(this).apply {
            text = "Model: not loaded"
            textSize = 14f
            setTextColor(textColor)
        }
        root.addView(status)

        // Model section
        root.addView(label("Local model"))
        choose = button("Choose model file (.litertlm)") { launchModelPicker() }
        root.addView(choose)

        // Network Hub
        root.addView(label("QuietOS Network"))
        root.addView(button("Open Network Hub") {
            startActivity(Intent(this, com.deanwaylabs.quietos.network.NetworkHubActivity::class.java))
        })

        // Activity log (read-only)
        root.addView(label("Log"))
        log = TextView(this).apply {
            textSize = 14f
            setTextColor(textColor)
        }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(log)
        })

        // Attention engine
        root.addView(label("Attention Engine"))
        attentionStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(textColor)
        }
        root.addView(attentionStatus)
        val listenerStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(textColor)
            text = "Listener: checking..."
        }
        root.addView(listenerStatus)
        root.addView(button("Open notification access settings") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        lifecycleScope.launch {
            val enabled = NotificationManagerCompat.getEnabledListenerPackages(this@MainActivity)
                .contains(packageName)
            listenerStatus.text = if (enabled) "Listener: ENABLED" else "Listener: DISABLED - tap above to enable"
        }
        interceptionButton = button("QuietOS Attention Mode: OFF") {
            val prefs = getSharedPreferences("quietos_attention", MODE_PRIVATE)
            val enabled = prefs.getBoolean("interception_enabled", false)
            setAttentionMode(!enabled)
        }
        root.addView(interceptionButton)

        val bucketRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (bucket in listOf("NOW", "SOON", "DIGEST", "QUIET")) {
            bucketRow.addView(button(bucket) { showAttentionBucket(bucket) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        root.addView(bucketRow)
        root.addView(button("Summarize digest") { summarizeDigest() })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        return scroll
    }

    // ---------- Model ----------

    private fun launchModelPicker() {
        picker.launch(arrayOf("*/*"))
    }

    private suspend fun autoLoadExistingModel() {
        val existing = withContext(Dispatchers.IO) {
            File(filesDir, "models").listFiles()
                ?.filter { it.isFile && it.extension.equals("litertlm", true) }
                ?.maxByOrNull { it.lastModified() }
        } ?: return
        choose.isEnabled = false
        status.text = "Model: loading saved ${existing.name}..."
        try {
            val metrics = model.load(existing.absolutePath)
            status.text = "Model READY | load ${metrics.loadTimeMs} ms | warmup ${metrics.warmupTimeMs} ms"
            log.append("\nQuietOS: saved model loaded automatically.\n")
        } catch (t: Throwable) {
            status.text = "Saved model load FAILED: ${t.message ?: t.javaClass.simpleName}"
            log.append("\nQuietOS: saved model failed to load. Choose a model file to replace it.\n")
        } finally {
            choose.isEnabled = true
        }
    }

    private suspend fun importAndLoad(uri: Uri) {
        choose.isEnabled = false
        status.text = "Model: importing..."
        try {
            val displayName = queryDisplayName(uri) ?: "model.litertlm"
            require(displayName.endsWith(".litertlm", ignoreCase = true)) { "Select a .litertlm model file." }
            val local = withContext(Dispatchers.IO) {
                val modelDir = File(filesDir, "models").apply { mkdirs() }
                val destination = File(modelDir, displayName)
                contentResolver.openInputStream(uri).use { source ->
                    requireNotNull(source) { "Could not open selected model." }
                    FileOutputStream(destination).use { target -> source.copyTo(target, 1024 * 1024) }
                }
                require(destination.length() > 0L) { "Imported model is empty." }
                destination
            }
            status.text = "Model: loading ${local.name}..."
            val metrics = model.load(local.absolutePath)
            status.text = "Model READY | load ${metrics.loadTimeMs} ms | warmup ${metrics.warmupTimeMs} ms"
            log.append("\nQuietOS: model loaded locally.\n")
        } catch (t: Throwable) {
            status.text = "Model load FAILED: ${t.message ?: t.javaClass.simpleName}"
            log.append("\nQuietOS: model load failed.\n")
        } finally {
            choose.isEnabled = true
        }
    }

    // ---------- Attention engine ----------

    private fun setAttentionMode(enabled: Boolean) {
        getSharedPreferences("quietos_attention", MODE_PRIVATE)
            .edit()
            .putBoolean("interception_enabled", enabled)
            .apply()
        updateInterceptionButton()
        refreshAttentionLog()
    }

    private fun summarizeDigest() {
        val digest = attentionRepository.readAll()
            .filter { it.classification == AttentionClass.DIGEST }
            .take(5)

        if (digest.isEmpty()) {
            log.append("\nQuietOS: no DIGEST notifications are waiting.\n")
            return
        }
        if (model.state != ModelState.READY) {
            log.append("\nQuietOS: model is not ready yet, so I kept the digest locally.\n")
            return
        }

        val digestContext = digest.mapIndexed { index, record ->
            val source = record.title.ifBlank { record.packageName }
            "${index + 1}. $source: ${record.text.take(120)}"
        }.joinToString("\n")

        val prompt = """These DIGEST notifications were collected. Summarize only the useful information in a calm, concise way. Do not invent details. If several items are repetitive, combine them.

$digestContext"""

        log.append("\nQuietOS: summarizing ${digest.size} DIGEST items locally.\n")
        status.text = "Model: summarizing digest..."

        lifecycleScope.launch {
            try {
                val result = model.send(prompt)
                log.append(result.text + "\n")
                status.text = "Model READY"
            } catch (t: Throwable) {
                log.append("[DIGEST FAILED: ${t.javaClass.simpleName}: ${t.message ?: "no message"}]\n")
                status.text = "Digest FAILED: ${t.javaClass.simpleName}"
            }
        }
    }

    private fun updateInterceptionButton() {
        val enabled = getSharedPreferences("quietos_attention", MODE_PRIVATE)
            .getBoolean("interception_enabled", false)
        interceptionButton.text = if (enabled) {
            "QuietOS Attention Mode: ON"
        } else {
            "QuietOS Attention Mode: OFF"
        }
    }

    private fun showAttentionBucket(bucket: String) {
        val classification = runCatching { AttentionClass.valueOf(bucket) }.getOrNull() ?: return
        val records = attentionRepository.readAll().filter { it.classification == classification }

        val body = if (records.isEmpty()) {
            "Nothing waiting in $bucket."
        } else {
            records.take(25).joinToString("\n\n") { record ->
                val action = if (record.originalCancelled) {
                    "QuietOS intercepted the original"
                } else {
                    "Original remains in Android notification flow"
                }
                "${record.title.ifBlank { record.packageName }}\n${record.text.take(240)}\nWhy: ${record.reason}\nAction: $action"
            }
        }

        val workspace = TextView(this).apply {
            text = body
            textSize = 18f
            val space = (20 * resources.displayMetrics.density).toInt()
            setPadding(space, space, space, space)
        }
        AlertDialog.Builder(this)
            .setTitle("$bucket • ${records.size} item(s)")
            .setView(ScrollView(this).apply { addView(workspace) })
            .setPositiveButton("BACK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun refreshAttentionLog() {
        val records = attentionRepository.readAll()
        attentionStatus.text = if (records.isEmpty()) {
            "Attention Engine: no captured notifications yet"
        } else {
            val counts = records.groupingBy { it.classification }.eachCount()
            val cancelled = records.count { it.originalCancelled }
            "Attention Engine: ${records.size} captured | NOW ${counts[AttentionClass.NOW] ?: 0} | " +
                "SOON ${counts[AttentionClass.SOON] ?: 0} | DIGEST ${counts[AttentionClass.DIGEST] ?: 0} | " +
                "QUIET ${counts[AttentionClass.QUIET] ?: 0} | intercepted $cancelled"
        }
    }

    // ---------- Helpers ----------

    private fun findTaggedTextView(view: View, wantedTag: String): TextView? {
        if (view is TextView && view.tag == wantedTag) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findTaggedTextView(view.getChildAt(i), wantedTag)?.let { return it }
            }
        }
        return null
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        if (::attentionStatus.isInitialized) refreshAttentionLog()
    }

    override fun onDestroy() {
        model.close()
        super.onDestroy()
    }
}
