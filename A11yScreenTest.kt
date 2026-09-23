// TEMP-A11Y-TEST — isolated screen-reader test. Safe to delete this file + wiring in MainActivity.
// Purpose: verify AxonAccessibilityService.rootInActiveWindow can read another app's UI.
// No AI / OCR / clicks / gestures. Read-only traversal only.
//
// v2 changes (boxes accuracy + fewer, cleaner elements):
//  1. Screenshot + tree are captured as a PAIR and verified (tree before == tree after), else retry.
//  2. No Toast before the screenshot (it used to appear inside the screenshot).
//  3. Unique screenshot file name per capture (the old fixed name made Compose reuse the OLD image).
//  4. Tree walk keeps only VISIBLE bounds (clipped by every parent + screen), skips off-screen subtrees.
//  5. Nodes are MERGED into "elements": clickable/editable/checkable node = 1 element,
//     its non-clickable text/icon children are folded into its label (no extra boxes).
//  6. Every element has role + label + parent index.
//
// v3 changes (for the vision LLM):
//  7. compact(): drops unlabeled decoration (dividers, avatar/thumbnail inside a labeled row) and renumbers.
//  8. renderMarked(): boxes + numbered tags drawn on the ORIGINAL full-res bitmap; tags never overlap;
//     sizes scale with image width; fixed high-contrast colours per role.
//  9. toLlmList(): text list ("#13 ITEM "..." @(x,y) in #N") to send together with the marked image.
//     The untouched screenshot file is the clean image.
package com.example.app_abdelbaset

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app_abdelbaset.ui.theme.App_abdelbasetTheme
import com.example.app_abdelbaset.ui.theme.BgPrimary
import com.example.app_abdelbaset.ui.theme.CardBg
import com.example.app_abdelbaset.ui.theme.CardBorder
import com.example.app_abdelbaset.ui.theme.NeonCyan
import com.example.app_abdelbaset.ui.theme.NeonGreen
import com.example.app_abdelbaset.ui.theme.TextMuted
import com.example.app_abdelbaset.ui.theme.TextPrimary
import com.example.app_abdelbaset.ui.theme.AccentAmber

// ── Data ─────────────────────────────────────────────
// One item = one logical ELEMENT (not one raw accessibility node).
data class A11yNodeItem(
    val className: String,
    val text: String,          // node's OWN text ("-" if none) — used as click fingerprint
    val contentDesc: String,   // node's OWN desc ("-" if none) — used as click fingerprint
    val viewId: String,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val scrollable: Boolean,
    val bounds: String,        // VISIBLE bounds (clipped by parents + screen) — this is what gets drawn
    val role: String = "VIEW", // BUTTON / INPUT / TOGGLE / TAB / ITEM / ICON / TEXT / IMAGE
    val label: String = "",    // own label + labels of non-clickable children folded in ("a · b · c")
    val parent: Int = -1,      // index of nearest parent ELEMENT, -1 = top level
    val rawBounds: String = "" // un-clipped bounds, used only to re-find the live node for click
)

data class A11yCapture(
    val packageName: String,
    val nodes: List<A11yNodeItem>,
    // TEMP-A11Y-TEST screenshot preview: file reference only (no Bitmap in Compose state)
    val screenshotPath: String? = null,
    val shotW: Int = 0,
    val shotH: Int = 0,
    val rawCount: Int = 0      // how many raw nodes were visited (for comparison with nodes.size)
)

// TEMP-A11Y-TEST: tap-to-click target fingerprint (stable fields to re-find the live node)
data class A11yTarget(
    val pkg: String,
    val viewId: String,
    val text: String,
    val contentDesc: String,
    val className: String,
    val bounds: String
)

// Fixed, high-contrast colour per role (NOT theme colours — must read on dark AND light apps)
private fun roleArgb(role: String): Int = when (role) {
    "INPUT" -> 0xFFFFB300.toInt()          // amber
    "TEXT" -> 0xFF00E5FF.toInt()           // cyan
    "IMAGE", "ICON" -> 0xFFFF4DFF.toInt()  // magenta
    else -> 0xFF00E676.toInt()             // green: BUTTON / ITEM / TOGGLE / TAB
}

private fun roleColor(role: String): androidx.compose.ui.graphics.Color =
    androidx.compose.ui.graphics.Color(roleArgb(role))

// ── Reader: delay + capture via existing service ─────
// The delay runs on the process main looper, so it survives
// leaving Axon and switching to another app during the 2s wait.
object A11yScreenReader {

    private const val TAG = "AxonA11yTest"
    private const val MIN_PX = 8 // ignore boxes smaller than this (invisible / decorative)

    @Volatile var lastDiag: String = ""
        private set

    // TEMP-A11Y-TEST: on-device diagnostics, no logcat needed.
    fun statusDump(context: Context): String {
        val svc = AxonAccessibilityService.instance
            ?: return "instance=NULL (service not bound — toggle Axon Assistant OFF/ON)"
        val sb = StringBuilder("instance=OK\n")
        try {
            val si = svc.serviceInfo
            val retr = try { si?.canRetrieveWindowContent.toString() } catch (_: Exception) { "?" }
            sb.append("canRetrieve=").append(retr).append("\n")
            sb.append("flags=0x").append((si?.flags ?: 0).toString(16)).append("\n")
        } catch (e: Exception) { sb.append("serviceInfo? ").append(e.message).append("\n") }
        try {
            sb.append("windows=").append(svc.windows?.size ?: "?").append("\n")
        } catch (e: Exception) { sb.append("windows? ").append(e.message).append("\n") }
        sb.append("enabledInSystem=").append(isEnabledInSystem(context))
        return sb.toString()
    }

    fun isServiceConnected(): Boolean = AxonAccessibilityService.instance != null

    fun isEnabledInSystem(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                it.resolveInfo.serviceInfo.packageName == context.packageName &&
                    it.resolveInfo.serviceInfo.name.contains("AxonAccessibilityService")
            }
        } catch (_: Exception) { isServiceConnected() }
    }

    fun openAccessibilitySettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            Toast.makeText(context, "Cannot open Accessibility settings", Toast.LENGTH_SHORT).show()
        }
    }

    fun scheduleCapture(
        appContext: Context,
        delayMs: Long = 2000L,
        onDone: (A11yCapture?) -> Unit
    ) {
        val svc = AxonAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(appContext, "Enable Axon Assistant accessibility first", Toast.LENGTH_LONG).show()
            onDone(null)
            return
        }
        // NOTE: no Toast here on purpose — a Toast shown right before the screenshot ends up inside it.
        Handler(Looper.getMainLooper()).postDelayed({ attemptCapture(appContext, 2, onDone) }, delayMs)
    }

    // Tree → screenshot → tree again. If both trees are identical the screen was still,
    // so boxes match the picture. If not (animation / app switch), wait a bit and retry.
    private fun attemptCapture(appContext: Context, retriesLeft: Int, onDone: (A11yCapture?) -> Unit) {
        val before = captureNow()
        if (before == null) {
            try {
                val hint = lastDiag.ifBlank { "No active window (secure screen?)" }
                Toast.makeText(appContext, hint, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
            onDone(null)
            return
        }
        captureScreenshot(appContext) { path, w, h ->
            val after = captureNow()
            val stable = after != null && signature(after) == signature(before)
            if (!stable && retriesLeft > 0) {
                Log.i(TAG, "attemptCapture: screen changed during capture, retrying ($retriesLeft left)")
                Handler(Looper.getMainLooper()).postDelayed(
                    { attemptCapture(appContext, retriesLeft - 1, onDone) }, 500L
                )
                return@captureScreenshot
            }
            val tree = after ?: before // the tree closest in time to the screenshot
            val cap = if (path != null) {
                tree.copy(screenshotPath = path, shotW = w, shotH = h)
            } else {
                lastDiag = "Accessibility captured, screenshot unavailable."
                tree
            }
            if (!stable) lastDiag = "Screen was still changing — boxes may be slightly off."
            try {
                Toast.makeText(
                    appContext,
                    "Screen captured: ${cap.nodes.size} elements (from ${cap.rawCount} nodes)",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {}
            onDone(cap)
        }
    }

    // Cheap fingerprint of the layout: package + every element's bounds.
    private fun signature(c: A11yCapture): Int =
        (c.packageName + c.nodes.joinToString("|") { it.bounds }).hashCode()

    // TEMP-A11Y-TEST: screenshot of the current display via the enabled service (API 30+).
    // Saved to cache file; callback delivers (path, w, h) or (null, 0, 0) on failure/timeout.
    private fun captureScreenshot(
        appContext: Context,
        timeoutMs: Long = 4000L,
        onDone: (String?, Int, Int) -> Unit
    ) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            Log.d(TAG, "captureScreenshot: API < 30, unavailable")
            onDone(null, 0, 0)
            return
        }
        val svc = AxonAccessibilityService.instance
        if (svc == null) {
            onDone(null, 0, 0)
            return
        }
        val main = Handler(Looper.getMainLooper())
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(path: String?, w: Int, h: Int) {
            if (finished.compareAndSet(false, true)) main.post { onDone(path, w, h) }
        }
        try {
            svc.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                appContext.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        Thread {
                            var path: String? = null
                            var w = 0
                            var h = 0
                            try {
                                val hw = android.graphics.Bitmap.wrapHardwareBuffer(
                                    result.hardwareBuffer, result.colorSpace
                                )
                                val sw = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                try { hw?.recycle() } catch (_: Exception) {}
                                try { result.hardwareBuffer.close() } catch (_: Exception) {}
                                if (sw != null) {
                                    w = sw.width
                                    h = sw.height
                                    // UNIQUE name per capture: with a fixed name the Compose
                                    // remember(path) kept showing the OLD picture under NEW boxes.
                                    try {
                                        appContext.cacheDir
                                            .listFiles { x -> x.name.startsWith("a11y_preview") }
                                            ?.forEach { try { it.delete() } catch (_: Exception) {} }
                                    } catch (_: Exception) {}
                                    val f = java.io.File(
                                        appContext.cacheDir,
                                        "a11y_preview_${System.currentTimeMillis()}.jpg"
                                    )
                                    try {
                                        java.io.FileOutputStream(f).use { out ->
                                            sw.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                                        }
                                        path = f.absolutePath
                                    } catch (e: Exception) {
                                        Log.d(TAG, "captureScreenshot: save failed: ${e.message}")
                                    }
                                    try { sw.recycle() } catch (_: Exception) {}
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "captureScreenshot: convert failed: ${e.message}")
                            }
                            Log.i(TAG, "captureScreenshot: path=$path ${w}x$h")
                            finish(path, w, h)
                        }.start()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.d(TAG, "captureScreenshot: failed code=$errorCode")
                        finish(null, 0, 0)
                    }
                }
            )
        } catch (e: Exception) {
            Log.d(TAG, "captureScreenshot: exception: ${e.message}")
            finish(null, 0, 0)
        }
        main.postDelayed({ finish(null, 0, 0) }, timeoutMs)
    }

    private val BOUNDS_RE = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")

    // Keep public for the preview composable (same file): parse "[l,t][r,b]" or null if invalid/empty
    fun parseBounds(s: String): android.graphics.Rect? {
        return try {
            val m = BOUNDS_RE.find(s) ?: return null
            val l = m.groupValues[1].toInt()
            val t = m.groupValues[2].toInt()
            val r = m.groupValues[3].toInt()
            val b = m.groupValues[4].toInt()
            if (r > l && b > t) android.graphics.Rect(l, t, r, b) else null
        } catch (_: Exception) { null }
    }

    fun loadPreview(path: String): androidx.compose.ui.graphics.ImageBitmap? {
        return try {
            android.graphics.BitmapFactory.decodeFile(path)
                ?.asImageBitmap()
        } catch (_: Exception) { null }
    }

    fun captureNow(maxNodes: Int = 200): A11yCapture? {
        val svc = AxonAccessibilityService.instance ?: run {
            lastDiag = "Service not connected. Toggle Axon Assistant OFF/ON in Accessibility."
            Log.d(TAG, "captureNow: no service instance")
            return null
        }
        val ownPkg = try { svc.packageName } catch (_: Exception) { "" }
        // 1) Primary: active window root — but NEVER our own window (test targets other apps)
        var root: AccessibilityNodeInfo? = try { svc.activeRootForTest() } catch (_: Exception) { null }
        var viaFallback = false
        if (root != null) {
            val rootPkg = try { root.packageName?.toString() ?: "" } catch (_: Exception) { "" }
            if (rootPkg.isNotEmpty() && rootPkg == ownPkg) {
                Log.i(TAG, "captureNow: activeRoot is own window ($ownPkg) — skipping, scanning windows")
                root = null
            }
        }
        // 2) Scan windows: log every window, pick focused non-own window with a root
        var windowsCount = -1
        if (root == null) {
            try {
                val wins = svc.windows
                windowsCount = wins?.size ?: -1
                if (wins != null) {
                    var focusedCandidate: AccessibilityNodeInfo? = null
                    var firstCandidate: AccessibilityNodeInfo? = null
                    for (w in wins) {
                        var wRoot: AccessibilityNodeInfo? = null
                        var wPkg = ""
                        var wActive = false
                        var wFocused = false
                        var wId = -1
                        var wType = -1
                        try {
                            wId = w.id
                            wType = w.type
                            wActive = w.isActive
                            wFocused = w.isFocused
                            wRoot = w.root
                            wPkg = try { wRoot?.packageName?.toString() ?: "" } catch (_: Exception) { "" }
                        } catch (_: Exception) {}
                        Log.i(TAG, "win id=$wId type=$wType pkg=${wPkg.ifEmpty { "?" }} active=$wActive focused=$wFocused root=${wRoot != null}")
                        if (wRoot != null && wPkg.isNotEmpty() && wPkg != ownPkg) {
                            if (firstCandidate == null) firstCandidate = wRoot
                            if (wFocused && focusedCandidate == null) focusedCandidate = wRoot
                        }
                    }
                    root = focusedCandidate ?: firstCandidate
                    viaFallback = root != null
                }
            } catch (e: Exception) {
                Log.d(TAG, "captureNow: windows scan failed: ${e.message}")
            }
        }
        if (root == null) {
            lastDiag = if (windowsCount >= 0) {
                "No other-app window (windows=$windowsCount). Switch to another app during the 2s delay."
            } else {
                "No active window (secure screen?). Toggle Axon Assistant OFF/ON and retry."
            }
            Log.d(TAG, "captureNow: failed. $lastDiag")
            return null
        }
        return try {
            val pkg = try { root.packageName?.toString() ?: "unknown" } catch (_: Exception) { "unknown" }
            val out = ArrayList<A11yNodeItem>(64)
            val raw = buildElements(root, out, maxNodes)
            val rb = Rect()
            try { root.getBoundsInScreen(rb) } catch (_: Exception) {}
            val elements = compact(out, rb.width()) // drop noise + renumber #0..#N continuously
            lastDiag = if (viaFallback) "Captured via windows fallback." else ""
            Log.d(TAG, "captureNow: pkg=$pkg raw=$raw built=${out.size} elements=${elements.size} fallback=$viaFallback")
            A11yCapture(pkg, elements, rawCount = raw)
        } catch (e: Exception) {
            lastDiag = "Capture failed: ${e.message}"
            Log.d(TAG, "captureNow: exception. $lastDiag")
            null
        }
    }

    // ── Element builder ──────────────────────────────────
    // Turns the raw accessibility tree into a SHORT list of meaningful elements.
    //
    //  * invisible / off-screen subtree           -> dropped (isVisibleToUser)
    //  * bounds                                   -> own bounds ∩ all parents' visible rect
    //  * clickable / editable / checkable node    -> 1 element (button, input, toggle, list item…)
    //  * non-clickable node with text / desc      -> folded into nearest actionable parent's label,
    //                                                or a standalone TEXT / IMAGE element if it has none
    //  * pure layout containers, decorative views -> no element (but children are still visited)
    //  * wrapper inside wrapper with same bounds  -> merged into one element
    //  * full-screen clickable wrapper w/o label  -> treated as container (must not swallow everything)
    private class WalkCtx(
        val out: MutableList<A11yNodeItem>,
        val max: Int,
        val screenArea: Long
    ) { var raw = 0 }

    private fun buildElements(root: AccessibilityNodeInfo, out: MutableList<A11yNodeItem>, max: Int): Int {
        val screen = Rect()
        try { root.getBoundsInScreen(screen) } catch (_: Exception) { return 0 }
        val ctx = WalkCtx(out, max, screen.width().toLong() * screen.height())
        walk(root, screen, -1, ctx)
        return ctx.raw
    }

    private fun walk(node: AccessibilityNodeInfo, clip: Rect, ownerIdx: Int, ctx: WalkCtx) {
        ctx.raw++
        if (ctx.out.size >= ctx.max) return

        // 1) hidden / scrolled away / other ViewPager page => whole subtree is invisible
        val visible = try { node.isVisibleToUser } catch (_: Exception) { true }
        if (ctx.raw > 1 && !visible) return

        // 2) real on-screen rect = own bounds ∩ what the parents let through
        val r = Rect()
        try { node.getBoundsInScreen(r) } catch (_: Exception) { return }
        val rawBounds = "[${r.left},${r.top}][${r.right},${r.bottom}]"
        if (!r.intersect(clip)) return
        if (r.width() < MIN_PX || r.height() < MIN_PX) return
        val visibleBounds = "[${r.left},${r.top}][${r.right},${r.bottom}]"

        val cls = try { node.className?.toString() ?: "" } catch (_: Exception) { "" }
        val text = try { node.text?.toString() ?: "" } catch (_: Exception) { "" }
        val desc = try { node.contentDescription?.toString() ?: "" } catch (_: Exception) { "" }
        val viewId = try { node.viewIdResourceName ?: "-" } catch (_: Exception) { "-" }
        val clickable = try { node.isClickable || node.isLongClickable } catch (_: Exception) { false }
        val editable = try { node.isEditable } catch (_: Exception) { false }
        val checkable = try { node.isCheckable } catch (_: Exception) { false }
        val enabled = try { node.isEnabled } catch (_: Exception) { false }
        val scrollable = try { node.isScrollable } catch (_: Exception) { false }
        val label = (if (text.isNotBlank()) text else desc).trim()
        val area = r.width().toLong() * r.height()

        var actionable = clickable || editable || checkable
        // a full-screen clickable wrapper without its own label is a container, not a button
        if (actionable && !editable && label.isEmpty() && area > ctx.screenArea * 0.6) actionable = false

        val out = ctx.out
        var myOwner = ownerIdx

        if (actionable) {
            if (ownerIdx >= 0 && out[ownerIdx].bounds == visibleBounds) {
                // wrapper inside wrapper, same box => same element
                foldLabel(out, ownerIdx, label)
            } else {
                out.add(
                    A11yNodeItem(
                        className = cls.ifEmpty { "-" },
                        text = text.ifEmpty { "-" },
                        contentDesc = desc.ifEmpty { "-" },
                        viewId = viewId,
                        clickable = clickable,
                        editable = editable,
                        enabled = enabled,
                        scrollable = scrollable,
                        bounds = visibleBounds,
                        role = roleOf(cls, clickable, editable, checkable),
                        label = label,
                        parent = ownerIdx,
                        rawBounds = rawBounds
                    )
                )
                myOwner = out.size - 1
            }
        } else if (label.isNotEmpty()) {
            if (ownerIdx >= 0) {
                // text / icon description inside a button or card => belongs to it, no extra box
                foldLabel(out, ownerIdx, label)
            } else if (out.none { it.bounds == visibleBounds && it.label == label }) {
                val isImage = cls.substringAfterLast('.').contains("Image")
                out.add(
                    A11yNodeItem(
                        className = cls.ifEmpty { "-" },
                        text = text.ifEmpty { "-" },
                        contentDesc = desc.ifEmpty { "-" },
                        viewId = viewId,
                        clickable = false,
                        editable = false,
                        enabled = enabled,
                        scrollable = scrollable,
                        bounds = visibleBounds,
                        role = if (isImage) "IMAGE" else "TEXT",
                        label = label,
                        parent = -1,
                        rawBounds = rawBounds
                    )
                )
            }
        }
        // else: layout container / decorative view => no element

        val count = try { node.childCount } catch (_: Exception) { 0 }
        for (i in 0 until count) {
            if (out.size >= ctx.max) break
            var child: AccessibilityNodeInfo? = null
            try { child = node.getChild(i) } catch (_: Exception) {}
            if (child != null) {
                walk(child, r, myOwner, ctx) // children are clipped by THIS node's visible rect
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun foldLabel(out: MutableList<A11yNodeItem>, idx: Int, label: String) {
        if (label.isEmpty()) return
        val o = out[idx]
        if (o.label.contains(label)) return
        out[idx] = o.copy(label = if (o.label.isEmpty()) label else "${o.label} · $label")
    }

    private fun roleOf(cls: String, clickable: Boolean, editable: Boolean, checkable: Boolean): String {
        val c = cls.substringAfterLast('.')
        return when {
            editable || c.contains("EditText") -> "INPUT"
            checkable || c.contains("Switch") || c.contains("CheckBox") ||
                c.contains("RadioButton") || c.contains("Toggle") -> "TOGGLE"
            c.contains("Button") -> "BUTTON"
            c.contains("Image") -> "ICON"
            c.contains("Tab") -> "TAB"
            clickable -> "ITEM"
            else -> "VIEW"
        }
    }

    // ── Post-process: remove noise, renumber ─────────────
    // Removes UNLABELED generic elements (ITEM / ICON / IMAGE / VIEW) that are only decoration:
    //   * hairline strips / dividers (thinner than 5% of screen width)
    //   * anything sitting inside another LABELED element (avatar, thumbnail, icon inside a row)
    // Explicit widgets (INPUT / BUTTON / TOGGLE / TAB) and anything with a label are always kept.
    // Survivors are renumbered 0..N-1 and `parent` is remapped to the nearest surviving ancestor.
    private fun rectContains(o: Rect, r: Rect, tol: Int = 4): Boolean =
        o.left - tol <= r.left && o.top - tol <= r.top &&
            o.right + tol >= r.right && o.bottom + tol >= r.bottom

    private fun compact(items: List<A11yNodeItem>, screenW: Int): List<A11yNodeItem> {
        val rects = items.map { parseBounds(it.bounds) }
        val keep = BooleanArray(items.size) { true }
        val minSide = (screenW * 0.05f).toInt()
        for (i in items.indices) {
            val e = items[i]
            val r = rects[i]
            if (r == null) { keep[i] = false; continue }
            if (e.label.isNotEmpty()) continue
            if (e.role == "INPUT" || e.role == "BUTTON" || e.role == "TOGGLE" || e.role == "TAB") continue
            if (minOf(r.width(), r.height()) < minSide) { keep[i] = false; continue }
            val areaR = r.width().toLong() * r.height()
            val insideLabeled = items.indices.any { j ->
                if (j == i || items[j].label.isEmpty()) return@any false
                val o = rects[j] ?: return@any false
                rectContains(o, r) && o.width().toLong() * o.height() > areaR
            }
            if (insideLabeled) keep[i] = false
        }
        val newIdx = IntArray(items.size) { -1 }
        var k = 0
        for (i in items.indices) if (keep[i]) newIdx[i] = k++
        fun keptAncestor(p: Int): Int {
            var q = p
            while (q >= 0 && !keep[q]) q = items[q].parent
            return if (q >= 0) newIdx[q] else -1
        }
        return items.indices.filter { keep[it] }.map { items[it].copy(parent = keptAncestor(items[it].parent)) }
    }

    // ── Output for the vision LLM ────────────────────────
    fun loadBitmap(path: String): android.graphics.Bitmap? = try {
        android.graphics.BitmapFactory.decodeFile(path)
    } catch (_: Exception) { null }

    // Draws numbered boxes on a COPY of the ORIGINAL full-resolution screenshot.
    // Box thickness and tag size scale with the image width, so they stay readable
    // even after the API downsizes the picture. Tags never overlap each other.
    // Send THIS image + toLlmList() to the LLM (the untouched screenshot file is the clean version).
    fun renderMarked(
        base: android.graphics.Bitmap,
        nodes: List<A11yNodeItem>,
        shown: Set<Int>? = null
    ): android.graphics.Bitmap {
        val bmp = base.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val stroke = (w / 300f).coerceAtLeast(3f)      // ~3.6px on a 1080px-wide screen
        val ts = (w * 0.028f).coerceAtLeast(22f)       // ~30px text
        val pad = ts * 0.25f
        val tagH = ts + pad * 2f

        val boxPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
        }
        val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts
            isFakeBoldText = true
            color = android.graphics.Color.BLACK
        }
        val idxs = nodes.indices.filter { shown == null || it in shown }

        // 1) boxes: black casing + bright colour, readable on dark and light apps
        for (i in idxs) {
            val r = parseBounds(nodes[i].bounds) ?: continue
            val rf = RectF(
                r.left + stroke / 2f, r.top + stroke / 2f,
                r.right - stroke / 2f, r.bottom - stroke / 2f
            )
            boxPaint.strokeWidth = stroke + 2f
            boxPaint.color = android.graphics.Color.BLACK
            canvas.drawRect(rf, boxPaint)
            boxPaint.strokeWidth = stroke
            boxPaint.color = roleArgb(nodes[i].role)
            canvas.drawRect(rf, boxPaint)
        }

        // 2) tags: greedy placement, first free spot wins.
        // Smallest boxes first so nested children claim a spot before the parent takes every corner.
        val orderedIdxs = idxs.sortedBy { i ->
            val r = parseBounds(nodes[i].bounds)
            (r?.width()?.toLong() ?: 0L) * (r?.height()?.toLong() ?: 0L)
        }
        val placed = ArrayList<RectF>()
        for (i in orderedIdxs) {
            val r = parseBounds(nodes[i].bounds) ?: continue
            val tag = "#$i"
            val tw = textPaint.measureText(tag) + pad * 2f
            val l = r.left.toFloat()
            val t = r.top.toFloat()
            val rr = r.right.toFloat()
            val b = r.bottom.toFloat()
            val cx = (l + rr - tw) / 2f
            val cy = (t + b - tagH) / 2f
            val small = r.height() < tagH * 2.5f || r.width() < tw * 2.5f
            // corners + edge midpoints, inside and outside
            val inside = listOf(
                RectF(l, t, l + tw, t + tagH),
                RectF(rr - tw, t, rr, t + tagH),
                RectF(l, b - tagH, l + tw, b),
                RectF(rr - tw, b - tagH, rr, b),
                RectF(cx, t, cx + tw, t + tagH),
                RectF(cx, b - tagH, cx + tw, b),
                RectF(l, cy, l + tw, cy + tagH),
                RectF(rr - tw, cy, rr, cy + tagH)
            )
            val outside = listOf(
                RectF(l, t - tagH, l + tw, t),
                RectF(l, b, l + tw, b + tagH),
                RectF(rr - tw, t - tagH, rr, t),
                RectF(cx, t - tagH, cx + tw, t),
                RectF(cx, b, cx + tw, b + tagH)
            )
            // small boxes: keep the tag OFF the content; big boxes: tag inside the corner
            val cands = if (small) outside + inside else inside + outside
            var spot: RectF? = cands.firstOrNull { c ->
                c.left >= 0f && c.top >= 0f && c.right <= w && c.bottom <= h &&
                    placed.none { p -> RectF.intersects(p, c) }
            }
            if (spot == null) {
                // slide down along BOTH vertical edges inside the box
                var y = t
                while (spot == null && y + tagH <= b) {
                    val c1 = RectF(l, y, l + tw, y + tagH)
                    val c2 = RectF(rr - tw, y, rr, y + tagH)
                    spot = listOf(c1, c2).firstOrNull { c -> placed.none { p -> RectF.intersects(p, c) } }
                    y += tagH
                }
            }
            val s = spot ?: inside[0]
            placed.add(s)
            fillPaint.color = (roleArgb(nodes[i].role) and 0x00FFFFFF) or 0xE6000000.toInt()
            canvas.drawRoundRect(s, pad, pad, fillPaint)
            canvas.drawText(tag, s.left + pad, s.top + pad + ts * 0.85f, textPaint)
        }
        return bmp
    }

    // Text list that goes to the LLM together with the marked image. The LLM reads "#13" in the
    // picture, looks it up here, and answers "tap #13". Coordinates (screen px) are the box centre.
    fun toLlmList(cap: A11yCapture, shown: Set<Int>? = null): String {
        val sb = StringBuilder()
        sb.append("app=").append(cap.packageName)
            .append(" screen=").append(cap.shotW).append("x").append(cap.shotH).append("\n")
        for ((i, n) in cap.nodes.withIndex()) {
            if (shown != null && i !in shown) continue
            val r = parseBounds(n.bounds)
            val hint = n.viewId.substringAfter(":id/", "").replace('_', ' ')
            val name = when {
                n.label.isNotEmpty() -> "\"" + n.label.take(80) + "\""
                hint.isNotEmpty() -> "($hint)"
                else -> "(no label)"
            }
            sb.append("#").append(i).append(" ").append(n.role).append(" ").append(name)
            if (r != null) sb.append(" @(").append(r.centerX()).append(",").append(r.centerY()).append(")")
            if (n.parent >= 0) sb.append(" in #").append(n.parent)
            if (!n.enabled) sb.append(" [disabled]")
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    // TEMP-A11Y-TEST: save the EXACT bitmap shown in preview (passed in, not re-rendered),
    // to Pictures/Axon via MediaStore (API 29+) or app cache below that. Runs off the main thread.
    fun saveMarked(
        appContext: Context,
        marked: android.graphics.Bitmap,
        boxCount: Int,
        onDone: (String) -> Unit
    ) {
        Thread {
            val msg = saveBitmap(appContext, marked, boxCount)
            Handler(Looper.getMainLooper()).post { onDone(msg) }
        }.start()
    }

    private fun saveBitmap(appContext: Context, bmp: android.graphics.Bitmap, boxCount: Int): String {
        val name = "axon_marked_${System.currentTimeMillis()}.jpg"
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Axon")
                }
                val resolver = appContext.contentResolver
                val uri = resolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return "Save failed: MediaStore rejected insert"
                resolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                } ?: return "Save failed: cannot open output"
                Log.i(TAG, "saveMarked: saved $uri boxes=$boxCount")
                "Saved: Pictures/Axon/$name ($boxCount boxes)"
            } else {
                val f = java.io.File(appContext.cacheDir, name)
                java.io.FileOutputStream(f).use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                }
                "Saved to cache: ${f.absolutePath} ($boxCount boxes)"
            }
        } catch (e: Exception) {
            "Save failed: ${e.message}"
        }
    }

    // ── Tap-to-click: tap a listed element, switch to the app, auto-click after 3s ──
    // "-" (empty marker) is converted back to "" so it matches the live node; raw bounds are used
    // because the live node reports un-clipped bounds.
    fun targetFrom(item: A11yNodeItem, pkg: String) = A11yTarget(
        pkg,
        item.viewId,
        item.text.takeUnless { it == "-" } ?: "",
        item.contentDesc.takeUnless { it == "-" } ?: "",
        item.className.takeUnless { it == "-" } ?: "",
        item.rawBounds.ifEmpty { item.bounds }
    )

    @Volatile private var pendingClick: Runnable? = null
    private val clickHandler = Handler(Looper.getMainLooper())

    fun cancelPendingClick() {
        try {
            pendingClick?.let { clickHandler.removeCallbacks(it) }
        } catch (_: Exception) {}
        pendingClick = null
    }

    fun scheduleClick(
        appContext: Context,
        target: A11yTarget,
        delayMs: Long = 3000L,
        onDone: (String) -> Unit
    ) {
        cancelPendingClick()
        val svc = AxonAccessibilityService.instance
        if (svc == null) {
            val msg = "Enable Axon Assistant accessibility first"
            try { Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            onDone(msg)
            return
        }
        try {
            Toast.makeText(appContext, "Armed — switch to the app, click in 3s…", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
        val r = Runnable {
            pendingClick = null
            try { Toast.makeText(appContext, "Clicking…", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            val res = performClick(target)
            try {
                Toast.makeText(appContext, res, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
            onDone(res)
        }
        pendingClick = r
        clickHandler.postDelayed(r, delayMs)
    }

    private fun performClick(target: A11yTarget): String {
        val svc = AxonAccessibilityService.instance ?: return "Service not connected"
        val ownPkg = try { svc.packageName } catch (_: Exception) { "" }
        // Collect candidate roots (never our own window)
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            val active = svc.activeRootForTest()
            val pkg = try { active?.packageName?.toString() ?: "" } catch (_: Exception) { "" }
            if (active != null && pkg.isNotEmpty() && pkg != ownPkg) roots.add(active)
        } catch (_: Exception) {}
        try {
            val wins = svc.windows
            if (wins != null) {
                val ordered = wins.sortedWith(compareByDescending<android.view.accessibility.AccessibilityWindowInfo> {
                    try { if (it.isFocused) 1 else 0 } catch (_: Exception) { 0 }
                }.thenByDescending {
                    try {
                        val p = it.root?.packageName?.toString() ?: ""
                        if (p == target.pkg) 1 else 0
                    } catch (_: Exception) { 0 }
                })
                for (w in ordered) {
                    try {
                        val r = w.root ?: continue
                        val p = try { r.packageName?.toString() ?: "" } catch (_: Exception) { "" }
                        if (p.isNotEmpty() && p != ownPkg && !roots.contains(r)) roots.add(r)
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "performClick: windows scan failed: ${e.message}")
        }
        if (roots.isEmpty()) return "No other-app window for click (windows unavailable)"
        // Tiered matching (strongest first), clickable hits preferred within each tier
        val tiers = listOf(
            "id+bounds" to { n: AccessibilityNodeInfo -> matchId(n, target) && nodeBoundsStr(n) == target.bounds },
            "id" to { n: AccessibilityNodeInfo -> matchId(n, target) },
            "text+bounds" to { n: AccessibilityNodeInfo ->
                matchText(n, target) && target.bounds != "" && nodeBoundsStr(n) == target.bounds
            },
            "text" to { n: AccessibilityNodeInfo -> matchText(n, target) }
        )
        for ((tierName, pred) in tiers) {
            try {
                val hits = ArrayList<AccessibilityNodeInfo>()
                for (root in roots) {
                    try { dfsCollect(root, pred, hits, 10) } catch (_: Exception) {}
                    if (hits.size >= 10) break
                }
                if (hits.isNotEmpty()) {
                    val direct = hits.firstOrNull { isClick(it) }
                    Log.i(TAG, "performClick: tier=$tierName hits=${hits.size} directClick=${direct != null}")
                    return clickNode(direct ?: hits[0], tierName)
                }
            } catch (_: Exception) {}
        }
        Log.i(TAG, "performClick: no match pkg=${target.pkg} id=${target.viewId} text=${target.text.take(60)}")
        return "Element not found on current screen"
    }

    private fun matchId(node: AccessibilityNodeInfo, t: A11yTarget): Boolean {
        if (t.viewId == "-" || t.viewId.isBlank()) return false
        return try { node.viewIdResourceName == t.viewId } catch (_: Exception) { false }
    }

    private fun matchText(node: AccessibilityNodeInfo, t: A11yTarget): Boolean {
        return try {
            val cls = try { node.className?.toString() ?: "" } catch (_: Exception) { "" }
            val tx = try { node.text?.toString() ?: "" } catch (_: Exception) { "" }
            val desc = try { node.contentDescription?.toString() ?: "" } catch (_: Exception) { "" }
            cls == t.className && tx == t.text && desc == t.contentDesc &&
                (tx != "" || desc != "" || (t.className != "" && t.viewId == "-"))
        } catch (_: Exception) { false }
    }

    private fun nodeBoundsStr(node: AccessibilityNodeInfo): String {
        return try {
            val r = Rect()
            node.getBoundsInScreen(r)
            "[${r.left},${r.top}][${r.right},${r.bottom}]"
        } catch (_: Exception) { "" }
    }

    private fun isClick(node: AccessibilityNodeInfo): Boolean {
        return try { node.isClickable } catch (_: Exception) { false }
    }

    private fun dfsCollect(
        node: AccessibilityNodeInfo?,
        pred: (AccessibilityNodeInfo) -> Boolean,
        out: MutableList<AccessibilityNodeInfo>,
        max: Int
    ) {
        if (node == null || out.size >= max) return
        try { if (pred(node)) { out.add(node); if (out.size >= max) return } } catch (_: Exception) {}
        val count = try { node.childCount } catch (_: Exception) { 0 }
        for (i in 0 until count) {
            if (out.size >= max) break
            val c = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            dfsCollect(c, pred, out, max)
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo, via: String): String {
        return try {
            var target: AccessibilityNodeInfo? = node
            var hops = 0
            while (target != null && !(try { target.isClickable } catch (_: Exception) { false }) && hops < 4) {
                target = try { target.parent } catch (_: Exception) { null }
                hops++
            }
            if (target == null) return "Found element but nothing clickable near it"
            val ok = try { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
            val label = try { target.text?.toString() ?: (target.contentDescription?.toString() ?: "") } catch (_: Exception) { "" }
            Log.i(TAG, "performClick: clicked via=$via hops=$hops ok=$ok label=${label.take(60)}")
            if (ok) "Clicked${if (label.isNotBlank()) ": ${label.take(40)}" else ""}" else "Click action rejected"
        } catch (e: Exception) {
            "Click failed: ${e.message}"
        }
    }
}

// ── UI (self-contained HUD style, no dependency on private MainActivity helpers) ──
@Composable
fun A11yTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    var isWaiting by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<A11yCapture?>(null) }
    var errorMsg by remember { mutableStateOf("") }
    var diagText by remember { mutableStateOf("") }
    // TEMP-A11Y-TEST tap-to-click states
    var isClickWaiting by remember { mutableStateOf(false) }
    var clickTarget by remember { mutableStateOf<A11yTarget?>(null) }
    var clickResult by remember { mutableStateOf("") }
    // TEMP-A11Y-TEST filter states (display only, show-all by default)
    var onlyClickable by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // TEMP-A11Y-TEST save-annotated-image states
    var isSaving by remember { mutableStateOf(false) }
    var savedMsg by remember { mutableStateOf("") }
    val enabled = remember { A11yScreenReader.isEnabledInSystem(context) || A11yScreenReader.isServiceConnected() }
    LaunchedEffect(Unit) { diagText = A11yScreenReader.statusDump(context) }
    // TEMP-A11Y-TEST filter computation at composable scope (visible to both sections)
    val allNodes = capture?.nodes.orEmpty()
    val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.lowercase() }
    val filteredIdx = allNodes.indices.filter { i ->
        val n = allNodes[i]
        if (onlyClickable && !n.clickable) return@filter false
        if (tokens.isEmpty()) return@filter true
        val hay = "${n.className} ${n.role} ${n.label} ${n.text} ${n.contentDesc} ${n.viewId}".lowercase()
        tokens.all { hay.contains(it) }
    }

    App_abdelbasetTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgPrimary)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = 16.dp)) {
                // Upper section scrolls independently so the page never locks
                Column(
                    modifier = Modifier.weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(CardBg)
                            .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("SCREEN READER TEST", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, letterSpacing = 2.sp)
                        Text("TEMP · ACCESSIBILITY · READ-ONLY", fontSize = 8.sp, color = TextMuted, letterSpacing = 2.sp)
                    }
                }

                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Status
                    val statusText = if (A11yScreenReader.isServiceConnected()) "● SERVICE CONNECTED" else "○ SERVICE OFF"
                    val statusColor = if (A11yScreenReader.isServiceConnected()) NeonGreen else AccentAmber
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(CardBg)
                            .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(statusText, fontSize = 9.sp, color = statusColor, letterSpacing = 1.5.sp)
                    }

                    // TEMP-A11Y-TEST: on-device diagnostics card
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(CardBg)
                            .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                            .clickable { diagText = A11yScreenReader.statusDump(context) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Column {
                            Text("DIAG (TAP TO REFRESH)", fontSize = 8.sp, color = TextMuted, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                diagText.ifBlank { "…" },
                                fontSize = 10.sp, color = TextPrimary, lineHeight = 14.sp
                            )
                        }
                    }

                    Text(
                        "Press Read Screen, then switch to another app within 2 seconds. Tap any element below to auto-click it: switch to that app and it clicks after 3s.",
                        fontSize = 10.sp, color = TextMuted, lineHeight = 14.sp
                    )

                    // Buttons row
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Read Screen button
                        Box(
                            modifier = Modifier.weight(1f).height(46.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isWaiting) CardBg else NeonGreen.copy(0.12f))
                                .border(0.5.dp, if (isWaiting) CardBorder else NeonGreen.copy(0.6f), RoundedCornerShape(6.dp))
                                .clickable(enabled = !isWaiting) {
                                    isWaiting = true
                                    errorMsg = ""
                                    A11yScreenReader.scheduleCapture(appContext, 2000L) { result ->
                                        isWaiting = false
                                        if (result != null) {
                                            capture = result
                                            clickTarget = null
                                            clickResult = ""
                                            savedMsg = ""
                                            // e.g. "Screen was still changing…" — shown as a soft warning
                                            errorMsg = A11yScreenReader.lastDiag
                                        }
                                        else if (!A11yScreenReader.isServiceConnected()) errorMsg = "Service not connected. Enable it first."
                                        else errorMsg = A11yScreenReader.lastDiag.ifBlank { "No active window captured." }
                                    }
                                }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (isWaiting) "WAITING…" else "READ SCREEN",
                                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = if (isWaiting) TextMuted else NeonGreen, letterSpacing = 1.sp
                            )
                        }
                        // Enable button
                        if (!enabled) {
                            Box(
                                modifier = Modifier.weight(1f).height(46.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(NeonCyan.copy(0.1f))
                                    .border(0.5.dp, NeonCyan.copy(0.5f), RoundedCornerShape(6.dp))
                                    .clickable { A11yScreenReader.openAccessibilitySettings(context) }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("ENABLE A11Y", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NeonCyan, letterSpacing = 1.sp)
                            }
                        }
                    }

                    if (errorMsg.isNotBlank()) {
                        Text(errorMsg, fontSize = 10.sp, color = AccentAmber)
                    }

                    // TEMP-A11Y-TEST tap-to-click target + result
                    val ct = clickTarget
                    if (ct != null) {
                        Text(
                            "TARGET: ${(ct.text.takeIf { it.isNotBlank() } ?: ct.contentDesc.takeIf { it.isNotBlank() } ?: ct.viewId).take(60)}" +
                                (if (isClickWaiting) " · CLICK IN 3s…" else ""),
                            fontSize = 10.sp, color = NeonCyan
                        )
                    }
                    if (clickResult.isNotBlank()) {
                        Text(clickResult, fontSize = 10.sp, color = NeonGreen)
                    }

                    // Result header: package + count
                    val cap = capture
                    if (cap != null) {
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(CardBg)
                                .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Column {
                                Text("PKG: ${cap.packageName}", fontSize = 10.sp, color = NeonCyan)
                                Spacer(Modifier.height(2.dp))
                                Text("ELEMENTS: ${cap.nodes.size}  (raw nodes: ${cap.rawCount})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            }
                        }
                    } else {
                        Text("No capture yet.", fontSize = 10.sp, color = TextMuted)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // TEMP-A11Y-TEST SCREEN PREVIEW: this is EXACTLY what the vision LLM gets
                // (marked full-res screenshot + text list). Follows the search / clickable filter.
                val shot = capture
                if (shot == null) {
                    Text("No screen captured yet.", fontSize = 10.sp, color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp))
                } else if (shot.screenshotPath == null) {
                    Text("Accessibility captured, screenshot unavailable.",
                        fontSize = 10.sp, color = AccentAmber,
                        modifier = Modifier.padding(horizontal = 16.dp))
                } else {
                    val base = remember(shot.screenshotPath) {
                        A11yScreenReader.loadBitmap(shot.screenshotPath!!)
                    }
                    val shownSet = filteredIdx.toHashSet()
                    // The EXACT bitmap shown below — the save button stores this same object,
                    // so the file is pixel-identical to the preview (no second render pass).
                    val markedBmp = remember(shot, shownSet) {
                        base?.let { A11yScreenReader.renderMarked(it, shot.nodes, shownSet) }
                    }
                    val marked = remember(markedBmp) { markedBmp?.asImageBitmap() }
                    val llmText = remember(shot, shownSet) { A11yScreenReader.toLlmList(shot, shownSet) }
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("SCREEN PREVIEW", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = TextPrimary, letterSpacing = 2.sp)
                        Text(
                            "Screenshot: ${shot.shotW}x${shot.shotH}\n" +
                                "Package: ${shot.packageName}\n" +
                                "Raw nodes: ${shot.rawCount} → Elements: ${shot.nodes.size}\n" +
                                "Boxes drawn: ${shownSet.size}",
                            fontSize = 10.sp, color = TextMuted, lineHeight = 14.sp
                        )
                        if (marked != null && marked.width > 0 && marked.height > 0) {
                            Image(
                                bitmap = marked,
                                contentDescription = "Marked screenshot",
                                modifier = Modifier.fillMaxWidth()
                                    .aspectRatio(marked.width.toFloat() / marked.height.toFloat())
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                            )
                        } else {
                            Text("Screenshot file unreadable.", fontSize = 10.sp, color = AccentAmber)
                        }
                        // TEMP-A11Y-TEST: save the marked image (same boxes as preview)
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSaving) CardBg else NeonCyan.copy(0.1f))
                                .border(
                                    0.5.dp,
                                    if (isSaving) CardBorder else NeonCyan.copy(0.5f),
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable(enabled = !isSaving && markedBmp != null) {
                                    isSaving = true
                                    savedMsg = ""
                                    val bmp = markedBmp
                                    if (bmp == null) {
                                        isSaving = false
                                        savedMsg = "Nothing to save yet"
                                    } else {
                                        A11yScreenReader.saveMarked(appContext, bmp, shownSet.size) { msg ->
                                            isSaving = false
                                            savedMsg = msg
                                            try {
                                                Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (isSaving) "SAVING…" else "SAVE BOXES IMAGE (${shownSet.size})",
                                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = if (isSaving) TextMuted else NeonCyan,
                                letterSpacing = 1.sp
                            )
                        }
                        if (savedMsg.isNotBlank()) {
                            Text(savedMsg, fontSize = 10.sp, color = NeonGreen, lineHeight = 14.sp)
                        }
                        // LLM text list — tap to copy
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(CardBg)
                                .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                                .clickable {
                                    try {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager
                                        cm.setPrimaryClip(
                                            android.content.ClipData.newPlainText("a11y", llmText)
                                        )
                                        Toast.makeText(context, "LLM list copied", Toast.LENGTH_SHORT).show()
                                    } catch (_: Exception) {}
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Column {
                                Text("LLM LIST (TAP TO COPY)", fontSize = 8.sp, color = TextMuted, letterSpacing = 1.5.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(llmText, fontSize = 9.sp, color = TextPrimary, lineHeight = 12.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // TEMP-A11Y-TEST filter bar (display only — original indices preserved,
                // values computed above at composable scope)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("SEARCH label / role / id", fontSize = 9.sp, color = TextMuted) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedLabelColor = NeonCyan,
                            cursorColor = NeonCyan,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextPrimary)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (onlyClickable) NeonGreen.copy(0.12f) else CardBg)
                                .border(
                                    0.5.dp,
                                    if (onlyClickable) NeonGreen.copy(0.6f) else CardBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { onlyClickable = !onlyClickable }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (onlyClickable) "CLICKABLE ONLY: ON" else "CLICKABLE ONLY: OFF",
                                fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                color = if (onlyClickable) NeonGreen else TextMuted
                            )
                        }
                        Box(
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CardBg)
                                .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "SHOWING ${filteredIdx.size} / ${allNodes.size}",
                                fontSize = 9.sp, color = TextPrimary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                } // end upper scrollable section

                // Scrollable results list (bottom half, always has room)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(filteredIdx, key = { _, orig -> orig }) { _, orig ->
                        val n = allNodes[orig]
                        val index = orig
                        val isTarget = clickTarget?.let { t -> n.rawBounds == t.bounds } == true
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(CardBg)
                                .border(
                                    0.5.dp,
                                    if (isTarget) NeonCyan.copy(0.8f) else CardBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable(enabled = !isWaiting && !isClickWaiting) {
                                    val pkg = capture?.packageName ?: ""
                                    val t = A11yScreenReader.targetFrom(n, pkg)
                                    clickTarget = t
                                    clickResult = ""
                                    errorMsg = ""
                                    isClickWaiting = true
                                    A11yScreenReader.scheduleClick(appContext, t, 3000L) { res ->
                                        isClickWaiting = false
                                        clickResult = res
                                    }
                                }
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "#$index · ${n.role} · ${n.className.substringAfterLast('.')}" +
                                        (if (n.parent >= 0) " · child of #${n.parent}" else ""),
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = roleColor(n.role)
                                )
                                if (n.label.isNotBlank()) Text("label: ${n.label.take(200)}", fontSize = 10.sp, color = TextPrimary)
                                Text("id: ${n.viewId}", fontSize = 9.sp, color = TextMuted)
                                Text(
                                    "click=${n.clickable} edit=${n.editable} enabled=${n.enabled} scroll=${n.scrollable}",
                                    fontSize = 9.sp, color = TextMuted
                                )
                                Text("bounds: ${n.bounds}", fontSize = 9.sp, color = TextMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}
