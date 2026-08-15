package neth.iecal.curbox.blockers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.guardian.GuardianAiDetector
import neth.iecal.curbox.guardian.GuardianConfig
import neth.iecal.curbox.guardian.GuardianKeywordMatcher
import neth.iecal.curbox.services.BaseBlockingService

/**
 * Guardian (NSFW content blocking) — accessibility-service hook.
 *
 * M2 scope: detection + block trigger only. The full settings UI (M3) and the
 * block overlay / unlock flow (M4) come later; until then this uses a simple
 * go-home + toast block and SharedPreferences-backed configuration.
 *
 * Safety (agents.md invariants): every feature call is inside try/catch and
 * failures go to CrashLogger.logNonFatalError — a Guardian failure must never
 * crash AppBlockerService. Coroutine workers rethrow CancellationException.
 */
class GuardianBlocker {

    companion object {
        const val TAG = "GuardianBlocker"
        const val INTENT_ACTION_REFRESH = "neth.iecal.curbox.refresh.guardian"
        private const val PREFS = "guardian_prefs"
        private const val SCAN_THROTTLE_MS = 800L
        private const val MIN_IMAGE_REGION_PX = 80

        /**
         * Tells the running service (if any) to reload its Guardian config.
         * Settings screens call this after persisting changes.
         */
        fun refresh(context: Context) {
            try {
                val intent = Intent(INTENT_ACTION_REFRESH).setPackage(context.packageName)
                context.sendBroadcast(intent)
            } catch (_: Throwable) {}
        }
    }

    private lateinit var service: BaseBlockingService
    private lateinit var prefs: SharedPreferences
    private lateinit var crashLogger: CrashLogger
    private lateinit var detector: GuardianAiDetector

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var refreshReceiver: android.content.BroadcastReceiver? = null

    private var lastScanAt = 0L
    @Volatile private var scanInProgress = false

    /** True when the user has turned Guardian on. */
    @Volatile var isEnabled = false
        private set

    /** Refreshable snapshot of the active NSFW keyword rules. */
    @Volatile var keywordRules: List<Pair<String, Boolean>> = emptyList()
        private set

    fun setupBlocker(service: BaseBlockingService) {
        this.service = service
        this.prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        this.crashLogger = CrashLogger(service)
        this.detector = GuardianAiDetector.get(service)

        reloadConfig()
        scope.launch {
            try {
                detector.ensureLoaded()
                Log.i(TAG, "Models: legacy=${detector.isLegacyAvailable()} " +
                    "nsfw=${detector.isNsfwGateAvailable()} gender=${detector.isGenderModelAvailable()}")
            } catch (t: Throwable) {
                crashLogger.logNonFatalError(Exception(t))
            }
        }
    }

    fun setupReceivers() {
        try {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    reloadConfig()
                }
            }
            refreshReceiver = receiver
            val filter = IntentFilter(INTENT_ACTION_REFRESH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                service.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            crashLogger.logNonFatalError(Exception(t))
        }
    }

    fun removeReceivers() {
        try {
            refreshReceiver?.let { service.unregisterReceiver(it) }
        } catch (_: Throwable) {}
        refreshReceiver = null
    }

    fun onDestroy() {
        removeReceivers()
        scope.cancel()
    }

    private fun reloadConfig() {
        try {
            isEnabled = prefs.getBoolean("enabled", false)
            detector.config = GuardianConfig(
                aiEnabled = isEnabled,
                userGender = prefs.getString("gender", "NONE") ?: "NONE",
                aiThreshold = prefs.getFloat("ai_threshold", 0.72f),
                nsfwGateThreshold = prefs.getFloat("nsfw_gate", 0.68f),
                genderThreshold = prefs.getFloat("gender_threshold", 0.78f),
                gridVoteCount = prefs.getInt("grid_votes", 2),
            )
            keywordRules = prefs.getStringSet("keywords", emptySet())
                ?.map { raw ->
                    val trimmed = raw.trim()
                    if (trimmed.isEmpty()) null else {
                        val isRegex = trimmed.startsWith("regex:")
                        if (isRegex) trimmed.removePrefix("regex:") to true else trimmed to false
                    }
                }
                ?.filterNotNull()
                ?: emptyList()
            if (prefs.contains("keywords")) {
                // re-read after import/refresh — nothing else needed here
            }
        } catch (t: Throwable) {
            crashLogger.logNonFatalError(Exception(t))
        }
    }

    /**
     * Entry point from AppBlockerService.onAccessibilityEvent. Content/window
     * changes on non-whitelisted packages trigger a throttled text scan and
     * (on API 30+) an AI screenshot scan.
     */
    fun onAccessibilityEvent(event: AccessibilityEvent, pkg: String) {
        if (!isEnabled) return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    scanText(event)
                    maybeTriggerAiScan(pkg)
                }
            }
        } catch (t: Throwable) {
            crashLogger.logNonFatalError(Exception(t))
        }
    }

    /** Quick keyword scan over the visible text of the event's window. */
    private fun scanText(event: AccessibilityEvent) {
        if (keywordRules.isEmpty()) return
        val text = collectText(event) ?: return
        if (text.isBlank() || text.length < 2) return
        when (val r = GuardianKeywordMatcher.evaluate(text, keywordRules)) {
            is GuardianKeywordMatcher.Result.Block -> {
                Log.i(TAG, "Keyword block: ${r.matched}")
                block("keyword:${r.matched}")
            }
            else -> Unit
        }
    }

    private fun collectText(event: AccessibilityEvent): String? {
        val sb = StringBuilder()
        event.text?.forEach { item ->
            item?.toString()?.takeIf { it.isNotBlank() }?.let { sb.append(it).append(' ') }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /** Throttled AI screenshot scan (API 30+ only; fail-open below that). */
    private fun maybeTriggerAiScan(pkg: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (scanInProgress) return
        val now = System.currentTimeMillis()
        if (now - lastScanAt < SCAN_THROTTLE_MS) return
        lastScanAt = now
        scanInProgress = true

        try {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        scope.launch {
                            var bmp: Bitmap? = null
                            try {
                                val hw = result.hardwareBuffer
                                bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                                try { hw.close() } catch (_: Throwable) {}
                                if (bmp != null && pkg == currentPackage()) {
                                    evaluate(bmp, pkg)
                                }
                            } catch (t: Throwable) {
                                if (t is kotlinx.coroutines.CancellationException) throw t
                                crashLogger.logNonFatalError(Exception(t))
                            } finally {
                                try { bmp?.recycle() } catch (_: Throwable) {}
                                scanInProgress = false
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        scanInProgress = false
                    }
                },
            )
        } catch (t: Throwable) {
            scanInProgress = false
            crashLogger.logNonFatalError(Exception(t))
        }
    }

    private suspend fun evaluate(bitmap: Bitmap, pkg: String) {
        val gender = detector.config.userGender
        if (gender != "NONE" &&
            detector.isGenderModelAvailable() && detector.isNsfwGateAvailable()
        ) {
            // Full-screen scans are noisy → requireStrongNsfw (NSFW gate 0.80).
            if (detector.isOppositeGenderNsfw(bitmap, gender, requireStrongNsfw = true)) {
                block("ai-gender")
                return
            }
        }
        if (detector.isLegacyAvailable() && detector.isUnsafe(bitmap)) {
            block("ai-legacy")
        }
    }

    private fun currentPackage(): String? =
        service.rootInActiveWindow?.packageName?.toString()

    /** Minimal block: go home + notify. Full overlay/unlock UI lands in M4. */
    private fun block(reason: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                Toast.makeText(
                    service,
                    "Guardian: NSFW content blocked ($reason)",
                    Toast.LENGTH_SHORT,
                ).show()
                Log.i(TAG, "Blocked package=${currentPackage()} reason=$reason")
            } catch (t: Throwable) {
                crashLogger.logNonFatalError(Exception(t))
            }
        }
    }

    // ── Config helpers (used by the M3 settings UI later) ───────────────────

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("enabled", enabled)
        }
        reloadConfig()
    }

    fun setGender(context: Context, gender: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("gender", gender)
        }
        reloadConfig()
    }

    fun setThresholds(context: Context, ai: Float, nsfw: Float, gender: Float, votes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putFloat("ai_threshold", ai)
            putFloat("nsfw_gate", nsfw)
            putFloat("gender_threshold", gender)
            putInt("grid_votes", votes)
        }
        reloadConfig()
    }

    fun setKeywords(context: Context, keywords: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putStringSet("keywords", keywords)
        }
        reloadConfig()
    }
}
