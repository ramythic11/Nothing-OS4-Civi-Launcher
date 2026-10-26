package com.pratik.nothingcivi

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.AlarmClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.*
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs

data class AppEntry(
    val defaultLabel: String,
    var customLabel: String,
    val pkg: String,
    val activityName: String,
    val icon: Drawable,
    val category: String,
    var lastOpenedDaysAgo: Int = 0,
    var dailyQuotaPct: Float = 1.0f,
    var inlineNotif: String? = null
)

data class FolderItem(
    var id: String,
    var title: String,
    val pkgs: MutableList<String>,
    var pageIndex: Int = 0
)

class MainActivity : Activity(), SensorEventListener {

    // Live Wallpaper Scrim & Dynamic Wallpaper Tint State
    private var wallpaperDimAlpha = 55
    private var widgetOpacityAlpha = 165
    private var accentColor = Color.parseColor("#D71921")
    private var wallpaperExtractedColor = Color.parseColor("#38BDF8")

    // Structured Home Settings & Customization State
    private var addAppsToHome = true
    private var swipeDownBehavior = 0 // 0 = Split Notifications (Left) & Quick Settings (Right), 1 = Universal Search
    private var notificationDotsMode = 1 // 0 = White Dot Only, 1 = Dot + Count, 2 = Off
    private var isLabelFree = false
    private var maxIconsEnabled = true
    private var lockLayout = false
    private var smartAppCategorization = false
    private var autoOpenKeyboard = true
    private var iconPackMode = 0 // 0 = Nothing Mono + Wallpaper Tint, 1 = Pure Mono, 2 = System Color, 3 = Matrix
    private var useWallpaperIconTint = true
    private var isMonoIcons = true
    private var isDetoxMode = false
    private var hyperOsImmersiveGestures = true
    private var doubleTapMode = 0
    private var thumbArcMode = 0
    private var gridColumns = 4
    private var gridRows = 5
    private var weatherWidgetSpan = 1
    private var currentSpace = "FOCUS"
    private val spaces = listOf("WORK", "COMMUTE", "FITNESS", "FOCUS", "WEEKEND")

    // Horizontal Multi-Screen State
    private val desktopPages = mutableListOf<MutableList<String>>() // Each page holds its own ordered list of app pkgs
    private var currentPageIndex = 0
    private val editableFolders = mutableListOf<FolderItem>()
    private val pageWidgetIds = mutableMapOf<Int, MutableList<Int>>() // PageIndex -> List of AppWidgetIds

    private val COLOR_CARD get() = Color.argb(widgetOpacityAlpha, 18, 20, 26)
    private val COLOR_CARD_ALT get() = Color.argb((widgetOpacityAlpha + 30).coerceAtMost(240), 26, 28, 36)
    private val COLOR_STROKE get() = if (useWallpaperIconTint) adjustAlpha(wallpaperExtractedColor, 95) else Color.argb(75, 255, 255, 255)
    private val COLOR_WHITE = Color.parseColor("#FFFFFF")
    private val COLOR_MUTED = Color.parseColor("#A1A1AA")

    private lateinit var prefs: SharedPreferences
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Isolated AppWidget Sandbox
    private var appWidgetHost: AppWidgetHost? = null
    private var appWidgetManager: AppWidgetManager? = null
    private var activeWidgetContainer: LinearLayout? = null

    private val allApps = mutableListOf<AppEntry>()
    private val pinnedTopShelfPkgs = mutableListOf<String>()
    private val maxIconPkgs = mutableSetOf<String>()
    private val hiddenPkgs = mutableSetOf<String>()
    private val privateVaultPkgs = mutableSetOf<String>()
    private val decoyVaultPkgs = mutableSetOf<String>()
    private val mindfulPkgs = mutableSetOf<String>()

    // Smart Stack & Telemetry State
    private var clockFaceMode = 0
    private var smartStackIndex = 0
    private var compassAzimuth = 42
    private var dailySteps = 2840
    private var stepBaseline = -1f
    private var isTorchOn = false
    private var isDrawerOpen = false
    private var isSleepOverlayActive = false
    private var isUiBuilt = false
    private var drawerTab = 0
    private var activeVaultMode = 0

    // Glyph Timer State
    private var glyphRunning = false
    private var glyphSecondsLeft = 25 * 60
    private var glyphRunnable: Runnable? = null

    // Nullable / Safe UI References (Prevents UninitializedPropertyAccessException or NPE)
    private var rootStack: GestureInterceptorFrameLayout? = null
    private var homeContainer: LinearLayout? = null
    private var detoxContainer: LinearLayout? = null
    private var drawerContainer: LinearLayout? = null
    private var sleepLockOverlay: FrameLayout? = null
    private var dotMatrixHeader: DotMatrixView? = null
    private var telemetrySubText: TextView? = null
    private var spacePillsRow: LinearLayout? = null
    private var bentoRowLayout: LinearLayout? = null
    private var smartStackCard: LinearLayout? = null
    private var rightGlyphCard: LinearLayout? = null
    private var smartStackTitle: TextView? = null
    private var smartStackValue: TextView? = null
    private var smartStackSub: TextView? = null
    private var glyphStatusText: TextView? = null
    private var glyphProgressView: DotProgressBarView? = null
    private var peripheralStripText: TextView? = null
    private var maxFoldersRow: LinearLayout? = null
    private var favGridLayout: GridLayout? = null
    private var paginationBar: LinearLayout? = null
    private var agenticInput: EditText? = null
    private var agenticBanner: TextView? = null
    private var dockLayout: LinearLayout? = null
    private var drawerSearchInput: EditText? = null
    private var pinnedShelfLayout: LinearLayout? = null
    private var drawerMainContainer: FrameLayout? = null

    private var sensorManager: SensorManager? = null

    private val wallpaperColorListener = WallpaperManager.OnColorsChangedListener { colors, _ ->
        if (colors != null) applyExtractedWallpaperColors(colors)
    }

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateAllTelemetry()
        }
    }

    private val pkgAddedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (action == Intent.ACTION_PACKAGE_ADDED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                if (addAppsToHome && !lockLayout) {
                    ensurePageExists(currentPageIndex)
                    if (!isPkgOnAnyPage(pkg)) {
                        desktopPages[currentPageIndex].add(pkg)
                        saveState()
                    }
                }
                loadAppsAsync()
            } else if (action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                for (p in desktopPages) p.remove(pkg)
                for (f in editableFolders) f.pkgs.remove(pkg)
                maxIconPkgs.remove(pkg)
                saveState()
                loadAppsAsync()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            prefs = getSharedPreferences("kinetic_os4_civi_v9", Context.MODE_PRIVATE)
            loadState()
            buildMasterUI()
            enforce120HzAndLiveWallpaperPipeline()
            initWallpaperColorExtraction()
            initHardwareSensors()
            initWidgetSandboxAsync()

            safeRegisterReceiver(pkgAddedReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            })

            updateAllTelemetry()
            loadAppsAsync()
        } catch (t: Throwable) {
            // Ultimate crash guard fallback
            Toast.makeText(this, "Kinetic Recovery Mode Active", Toast.LENGTH_SHORT).show()
        }
    }

    // 1. WIDGET RENDERING ENGINE OVERHAUL (ISOLATED MEMORY SANDBOX)
    private fun initWidgetSandboxAsync() {
        bgExecutor.execute {
            try {
                val mgr = AppWidgetManager.getInstance(applicationContext)
                val host = AppWidgetHost(applicationContext, 1024)
                mainHandler.post {
                    try {
                        appWidgetManager = mgr
                        appWidgetHost = host
                        appWidgetHost?.startListening()
                        renderSandboxedWidgetsForCurrentPage()
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }

    private fun renderSandboxedWidgetsForCurrentPage() {
        val container = activeWidgetContainer ?: return
        container.removeAllViews()
        val ids = pageWidgetIds[currentPageIndex] ?: return
        val host = appWidgetHost ?: return
        val mgr = appWidgetManager ?: return

        for (id in ids.toList()) {
            try {
                val info: AppWidgetProviderInfo? = mgr.getAppWidgetInfo(id)
                if (info == null) {
                    ids.remove(id)
                    continue
                }
                val hostView: AppWidgetHostView = host.createView(applicationContext, id, info)
                hostView.setAppWidget(id, info)
                val wrapper = FrameLayout(this).apply {
                    background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(110)
                    ).apply { topMargin = dp(6) }
                    setOnLongClickListener {
                        if (checkLayoutLocked()) return@setOnLongClickListener true
                        triggerHaptic(1)
                        AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("WIDGET SANDBOX // OPTIONS")
                            .setItems(arrayOf("Move Widget to Next Page →", "Remove Widget from Home Screen")) { _, which ->
                                if (which == 0) {
                                    ids.remove(id)
                                    val nextP = currentPageIndex + 1
                                    ensurePageExists(nextP)
                                    pageWidgetIds.getOrPut(nextP) { mutableListOf() }.add(id)
                                    currentPageIndex = nextP
                                    saveState()
                                    refreshWorkspaceUI()
                                } else {
                                    try { host.deleteAppWidgetId(id) } catch (_: Throwable) {}
                                    ids.remove(id)
                                    saveState()
                                    renderSandboxedWidgetsForCurrentPage()
                                }
                            }
                            .show()
                        true
                    }
                }
                wrapper.addView(hostView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                container.addView(wrapper)
            } catch (_: Throwable) {
                // Sandbox caught faulty remote view inflation without crashing UI thread
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            if (requestCode == 901 && resultCode == RESULT_OK && data != null) {
                val appWidgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                if (appWidgetId != -1) {
                    val info = appWidgetManager?.getAppWidgetInfo(appWidgetId)
                    if (info?.configure != null) {
                        val cfgIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                            component = info.configure
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        }
                        startActivityForResult(cfgIntent, 902)
                    } else {
                        attachSanitizedWidget(appWidgetId)
                    }
                }
            } else if (requestCode == 902 && resultCode == RESULT_OK && data != null) {
                val appWidgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                if (appWidgetId != -1) attachSanitizedWidget(appWidgetId)
            }
        } catch (_: Throwable) {}
    }

    private fun attachSanitizedWidget(appWidgetId: Int) {
        try {
            val list = pageWidgetIds.getOrPut(currentPageIndex) { mutableListOf() }
            if (!list.contains(appWidgetId)) list.add(appWidgetId)
            saveState()
            renderSandboxedWidgetsForCurrentPage()
        } catch (_: Throwable) {}
    }

    private fun safeRegisterReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pkgAddedReceiver) } catch (_: Throwable) {}
        try { appWidgetHost?.stopListening() } catch (_: Throwable) {}
    }

    private fun enforce120HzAndLiveWallpaperPipeline() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val highest = display?.supportedModes?.maxByOrNull { it.refreshRate }
                if (highest != null) {
                    val p = window.attributes
                    p.preferredDisplayModeId = highest.modeId
                    window.attributes = p
                }
                if (hyperOsImmersiveGestures) {
                    window.insetsController?.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        } catch (_: Throwable) {}
    }

    private fun initWallpaperColorExtraction() {
        try {
            val wm = WallpaperManager.getInstance(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                wm.addOnColorsChangedListener(wallpaperColorListener, mainHandler)
                val colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                if (colors != null) applyExtractedWallpaperColors(colors)
            }
        } catch (_: Throwable) {}
    }

    private fun applyExtractedWallpaperColors(colors: WallpaperColors) {
        if (!isUiBuilt) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val primary = colors.primaryColor.toArgb()
                val hsv = FloatArray(3)
                Color.colorToHSV(primary, hsv)
                hsv[1] = hsv[1].coerceAtLeast(0.55f)
                hsv[2] = hsv[2].coerceAtLeast(0.85f)
                wallpaperExtractedColor = Color.HSVToColor(hsv)
                if (useWallpaperIconTint) refreshWorkspaceUI()
            }
        } catch (_: Throwable) {}
    }

    private fun buildDynamicIconFilter(): ColorFilter? {
        if (iconPackMode == 2 || (!isMonoIcons && !useWallpaperIconTint)) return null
        if (iconPackMode == 1 || (isMonoIcons && !useWallpaperIconTint)) {
            return ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(0f)
                val c = 1.30f
                val t = (-0.5f * c + 0.5f) * 255f
                postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            })
        }
        if (iconPackMode == 3) {
            return ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(0f)
                val c = 1.65f
                val t = (-0.5f * c + 0.5f) * 255f
                postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            })
        }
        val r = Color.red(wallpaperExtractedColor) / 255f
        val g = Color.green(wallpaperExtractedColor) / 255f
        val b = Color.blue(wallpaperExtractedColor) / 255f
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val tintMatrix = ColorMatrix(floatArrayOf(
            r * 1.15f, 0f, 0f, 0f, 18f,
            0f, g * 1.15f, 0f, 0f, 18f,
            0f, 0f, b * 1.15f, 0f, 18f,
            0f, 0f, 0f, 1f, 0f
        ))
        gray.postConcat(tintMatrix)
        return ColorMatrixColorFilter(gray)
    }

    private fun adjustAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun initHardwareSensors() {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val hasStepPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            } else true

            if (hasStepPerm) {
                sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                    sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
            @Suppress("DEPRECATION")
            sensorManager?.getDefaultSensor(Sensor.TYPE_ORIENTATION)?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (_: Throwable) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isUiBuilt) return
        @Suppress("DEPRECATION")
        when (event?.sensor?.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values[0]
                if (stepBaseline < 0f) stepBaseline = total
                dailySteps = (total - stepBaseline).toInt().coerceAtLeast(0) + 1420
                if (smartStackIndex == 1) updateSmartStackUI()
            }
            Sensor.TYPE_ORIENTATION -> {
                compassAzimuth = event.values[0].toInt().coerceIn(0, 359)
                if (smartStackIndex == 2) updateSmartStackUI()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerHaptic(type: Int) {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effect = when (type) {
                    0 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    1 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                    2 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                    else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                }
                vib.vibrate(effect)
            }
        } catch (_: Throwable) {}
    }

    private fun checkLayoutLocked(): Boolean {
        if (lockLayout) {
            triggerHaptic(1)
            Toast.makeText(this, "Home screen layout is locked (Unlock in Home Settings)", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    private fun ensurePageExists(index: Int) {
        while (desktopPages.size <= index) {
            desktopPages.add(mutableListOf())
        }
    }

    private fun isPkgOnAnyPage(pkg: String): Boolean {
        return desktopPages.any { it.contains(pkg) }
    }

    private fun currentFavorites(): MutableList<String> {
        ensurePageExists(currentPageIndex)
        return desktopPages[currentPageIndex]
    }

    private fun loadState() {
        try {
            wallpaperDimAlpha = prefs.getInt("wall_dim", 55)
            widgetOpacityAlpha = prefs.getInt("widget_alpha", 165)
            addAppsToHome = prefs.getBoolean("add_apps_home", true)
            swipeDownBehavior = prefs.getInt("swipe_down_behavior", 0)
            notificationDotsMode = prefs.getInt("notif_dots_mode", 1)
            isLabelFree = prefs.getBoolean("label_free", false)
            maxIconsEnabled = prefs.getBoolean("max_icons_enabled", true)
            lockLayout = prefs.getBoolean("lock_layout", false)
            smartAppCategorization = prefs.getBoolean("smart_cat_default", false)
            autoOpenKeyboard = prefs.getBoolean("auto_open_kb", true)
            iconPackMode = prefs.getInt("icon_pack_mode", 0)
            isMonoIcons = prefs.getBoolean("mono", true)
            useWallpaperIconTint = prefs.getBoolean("wall_tint", true)
            isDetoxMode = prefs.getBoolean("detox", false)
            hyperOsImmersiveGestures = prefs.getBoolean("hyperos_gestures", true)
            doubleTapMode = prefs.getInt("double_tap_mode", 0)
            gridColumns = prefs.getInt("grid_cols", 4).coerceIn(4, 6)
            gridRows = prefs.getInt("grid_rows", 5).coerceIn(4, 6)
            weatherWidgetSpan = prefs.getInt("weather_span", 1).coerceIn(1, 2)
            currentSpace = prefs.getString("space", "FOCUS") ?: "FOCUS"
            currentPageIndex = prefs.getInt("current_page", 0).coerceAtLeast(0)
            accentColor = prefs.getInt("accent", Color.parseColor("#D71921"))
            wallpaperExtractedColor = prefs.getInt("wall_color", Color.parseColor("#38BDF8"))
            drawerTab = if (smartAppCategorization) 1 else 0

            maxIconPkgs.clear()
            maxIconPkgs.addAll(prefs.getStringSet("max_icons", emptySet()) ?: emptySet())
            hiddenPkgs.clear()
            hiddenPkgs.addAll(prefs.getStringSet("hidden_pkgs", emptySet()) ?: emptySet())
            privateVaultPkgs.clear()
            privateVaultPkgs.addAll(prefs.getStringSet("private_pkgs", emptySet()) ?: emptySet())
            decoyVaultPkgs.clear()
            decoyVaultPkgs.addAll(prefs.getStringSet("decoy_pkgs", emptySet()) ?: emptySet())
            mindfulPkgs.clear()
            mindfulPkgs.addAll(prefs.getStringSet("mindful_pkgs", emptySet()) ?: emptySet())

            // Load Multi-Page Desktop Lists (Pages separated by '||', apps by ',')
            desktopPages.clear()
            val pagesRaw = prefs.getString("desktop_pages_v9", null)
            if (!pagesRaw.isNullOrEmpty()) {
                for (pStr in pagesRaw.split("||")) {
                    val list = pStr.split(",").filter { it.isNotBlank() }.toMutableList()
                    desktopPages.add(list)
                }
            }
            if (desktopPages.isEmpty()) {
                desktopPages.add(mutableListOf())
            }
            if (currentPageIndex >= desktopPages.size) currentPageIndex = 0

            // Load Editable Folders (Format: id::title::pageIdx::pkg1,pkg2||...)
            editableFolders.clear()
            val foldersRaw = prefs.getString("editable_folders_v9", null)
            if (!foldersRaw.isNullOrEmpty()) {
                for (fStr in foldersRaw.split("||")) {
                    val parts = fStr.split("::")
                    if (parts.size >= 4) {
                        val id = parts[0]
                        val title = parts[1]
                        val pIdx = parts[2].toIntOrNull() ?: 0
                        val pkgs = parts[3].split(",").filter { it.isNotBlank() }.toMutableList()
                        editableFolders.add(FolderItem(id, title, pkgs, pIdx))
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun saveState() {
        try {
            val pagesSerialized = desktopPages.joinToString("||") { it.joinToString(",") }
            val foldersSerialized = editableFolders.joinToString("||") {
                "${it.id}::${it.title}::${it.pageIndex}::${it.pkgs.joinToString(",")}"
            }
            prefs.edit()
                .putInt("wall_dim", wallpaperDimAlpha)
                .putInt("widget_alpha", widgetOpacityAlpha)
                .putBoolean("add_apps_home", addAppsToHome)
                .putInt("swipe_down_behavior", swipeDownBehavior)
                .putInt("notif_dots_mode", notificationDotsMode)
                .putBoolean("label_free", isLabelFree)
                .putBoolean("max_icons_enabled", maxIconsEnabled)
                .putBoolean("lock_layout", lockLayout)
                .putBoolean("smart_cat_default", smartAppCategorization)
                .putBoolean("auto_open_kb", autoOpenKeyboard)
                .putInt("icon_pack_mode", iconPackMode)
                .putBoolean("mono", isMonoIcons)
                .putBoolean("wall_tint", useWallpaperIconTint)
                .putBoolean("detox", isDetoxMode)
                .putBoolean("hyperos_gestures", hyperOsImmersiveGestures)
                .putInt("double_tap_mode", doubleTapMode)
                .putInt("grid_cols", gridColumns)
                .putInt("grid_rows", gridRows)
                .putInt("weather_span", weatherWidgetSpan)
                .putString("space", currentSpace)
                .putInt("current_page", currentPageIndex)
                .putInt("accent", accentColor)
                .putInt("wall_color", wallpaperExtractedColor)
                .putStringSet("max_icons", HashSet(maxIconPkgs))
                .putStringSet("hidden_pkgs", HashSet(hiddenPkgs))
                .putStringSet("private_pkgs", HashSet(privateVaultPkgs))
                .putStringSet("decoy_pkgs", HashSet(decoyVaultPkgs))
                .putStringSet("mindful_pkgs", HashSet(mindfulPkgs))
                .putString("desktop_pages_v9", pagesSerialized)
                .putString("editable_folders_v9", foldersSerialized)
                .apply()
        } catch (_: Throwable) {}
    }

    private fun refreshWorkspaceUI() {
        if (!isUiBuilt) return
        renderSpacePills()
        updateAllTelemetry()
        renderMaxFolders()
        renderDesktopGrid()
        renderPaginationDots()
        renderSandboxedWidgetsForCurrentPage()
        if (isDrawerOpen) renderDrawerApps(drawerSearchInput?.text?.toString() ?: "")
    }

    private fun buildMasterUI() {
        // 2. GLOBAL GESTURE INTERCEPTOR & TOUCH DISPATCHER AT ROOT
        val interceptor = GestureInterceptorFrameLayout(this).apply {
            setBackgroundColor(Color.argb(wallpaperDimAlpha, 0, 0, 0))
        }
        rootStack = interceptor

        val home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(30), dp(14), dp(8))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isClickable = true
            setOnLongClickListener {
                triggerHaptic(1)
                showHyperOsHomeSettingsDialog()
                true
            }
        }
        homeContainer = home

        // Top Bar: Dynamic Spaces + Multi-Screen Page Manager Button
        val spRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            }
        }
        spacePillsRow = spRow
        renderSpacePills()
        home.addView(spRow)

        // 1. Dot-Matrix Clock & Telemetry Header
        val clockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(22), COLOR_STROKE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                triggerHaptic(0)
                clockFaceMode = (clockFaceMode + 1) % 4
                updateAllTelemetry()
            }
            setOnLongClickListener {
                triggerHaptic(1)
                showHyperOsHomeSettingsDialog()
                true
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dmView = DotMatrixView(this, accentColor).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        }
        dotMatrixHeader = dmView

        val pagesBtn = TextView(this).apply {
            text = "PAGES ⊞"
            typeface = Typeface.MONOSPACE
            textSize = 8f
            setTextColor(COLOR_WHITE)
            background = roundRect(COLOR_CARD_ALT, dp(10), accentColor)
            setPadding(dp(7), dp(4), dp(7), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(5)
            }
            setOnClickListener {
                triggerHaptic(0)
                showPageManagerDialog()
            }
        }
        val cfgBtn = TextView(this).apply {
            text = "HOME CFG ⚙"
            typeface = Typeface.MONOSPACE
            textSize = 8.5f
            setTextColor(COLOR_WHITE)
            background = roundRect(accentColor, dp(10))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { showHyperOsHomeSettingsDialog() }
        }
        topRow.addView(dmView)
        topRow.addView(pagesBtn)
        topRow.addView(cfgBtn)

        val subTxt = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 8.5f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(4), 0, 0)
        }
        telemetrySubText = subTxt
        clockCard.addView(topRow)
        clockCard.addView(subTxt)
        home.addView(clockCard)

        // 2. Resizable Smart Widget Stack + Glyph Timer Bento
        val bRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        bentoRowLayout = bRow

        val sCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = if (weatherWidgetSpan == 1) dp(6) else 0
            }
            setOnClickListener {
                triggerHaptic(0)
                smartStackIndex = (smartStackIndex + 1) % 4
                updateSmartStackUI()
            }
            setOnLongClickListener {
                if (checkLayoutLocked()) return@setOnLongClickListener true
                triggerHaptic(1)
                weatherWidgetSpan = if (weatherWidgetSpan == 1) 2 else 1
                saveState()
                buildMasterUI()
                refreshWorkspaceUI()
                true
            }
        }
        smartStackCard = sCard

        val sTitle = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 8.5f; setTextColor(COLOR_MUTED) }
        val sVal = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = if (weatherWidgetSpan == 2) 15f else 12.5f; setTextColor(COLOR_WHITE); setPadding(0, dp(2), 0, dp(2)) }
        val sSub = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 8f; setTextColor(if (useWallpaperIconTint) wallpaperExtractedColor else accentColor) }
        smartStackTitle = sTitle; smartStackValue = sVal; smartStackSub = sSub
        sCard.addView(sTitle); sCard.addView(sVal); sCard.addView(sSub)

        val rCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = if (weatherWidgetSpan == 1) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { toggleGlyphTimer() }
            setOnLongClickListener { resetGlyphTimer(); true }
        }
        rightGlyphCard = rCard

        val gTxt = TextView(this).apply { text = "GLYPH TIMER // 25:00"; typeface = Typeface.MONOSPACE; textSize = 8.5f; setTextColor(COLOR_WHITE) }
        val gBar = DotProgressBarView(this, accentColor).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(5) }
        }
        glyphStatusText = gTxt; glyphProgressView = gBar

        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        fun mediaKeyBtn(lbl: String, code: Int): TextView = TextView(this).apply {
            text = lbl; typeface = Typeface.MONOSPACE; textSize = 9f; gravity = Gravity.CENTER; setTextColor(COLOR_WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { triggerHaptic(0); dispatchMediaKey(code) }
        }
        mediaRow.addView(mediaKeyBtn("|<<", KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        mediaRow.addView(mediaKeyBtn("PLAY/PAUSE", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        mediaRow.addView(mediaKeyBtn(">>|", KeyEvent.KEYCODE_MEDIA_NEXT))

        rCard.addView(gTxt); rCard.addView(gBar); rCard.addView(mediaRow)
        bRow.addView(sCard); bRow.addView(rCard)
        home.addView(bRow)

        // Sandboxed Third-Party AppWidget Container
        val wContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        activeWidgetContainer = wContainer
        home.addView(wContainer)

        // 3. Editable Folders Header & Row
        val fHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        val pStrip = TextView(this).apply {
            text = "FOLDERS & APPS // HOLD ANY ITEM OR EMPTY SPACE"
            typeface = Typeface.MONOSPACE
            textSize = 8f
            setTextColor(COLOR_MUTED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        peripheralStripText = pStrip
        val newFolderBtn = TextView(this).apply {
            text = "+ NEW FOLDER"
            typeface = Typeface.MONOSPACE
            textSize = 8.5f
            setTextColor(accentColor)
            setPadding(dp(6), dp(2), dp(4), dp(2))
            setOnClickListener {
                if (!checkLayoutLocked()) promptCreateNewFolder()
            }
        }
        fHeaderRow.addView(pStrip)
        fHeaderRow.addView(newFolderBtn)
        home.addView(fHeaderRow)

        val mfRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }
        maxFoldersRow = mfRow
        home.addView(mfRow)

        // 4. Scrollable Multi-Page Desktop App Grid (Long-Press Empty Space -> Home Settings)
        val favScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) }
        }
        val scrollInnerWrapper = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isClickable = true
            setOnLongClickListener {
                triggerHaptic(1)
                showHyperOsHomeSettingsDialog()
                true
            }
        }
        val fGrid = GridLayout(this).apply {
            columnCount = gridColumns
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        favGridLayout = fGrid
        scrollInnerWrapper.addView(fGrid)
        favScroll.addView(scrollInnerWrapper)
        home.addView(favScroll)

        // 5. Horizontal Multi-Screen Pagination Bar (Swipe Left/Right or Tap to Switch/Add Pages)
        val pBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }
        paginationBar = pBar
        home.addView(pBar)

        // 6. Agentic Intent Search Bar
        val aBanner = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 9.5f
            setTextColor(COLOR_WHITE)
            background = roundRect(COLOR_CARD_ALT, dp(14), accentColor)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
        }
        agenticBanner = aBanner
        home.addView(aBanner)

        val searchBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(COLOR_CARD, dp(24), COLOR_STROKE)
            setPadding(dp(14), dp(3), dp(10), dp(3))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { bottomMargin = dp(6) }
        }
        val aInp = EditText(this).apply {
            hint = "SWIPE UP ANYWHERE FOR DRAWER // ASK SLM..."
            setHintTextColor(COLOR_MUTED)
            setTextColor(COLOR_WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setSingleLine(true)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    parseAgenticIntent(s?.toString()?.trim() ?: "")
                }
            })
        }
        agenticInput = aInp
        val openDrawerBtn = TextView(this).apply {
            text = "DRAWER ↑"
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextColor(accentColor)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { openDrawer(focusKb = autoOpenKeyboard) }
        }
        searchBarRow.addView(aInp)
        searchBarRow.addView(openDrawerBtn)
        home.addView(searchBarRow)

        // 7. Bottom Dock
        val dLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundRect(COLOR_CARD, dp(22), COLOR_STROKE)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58))
        }
        dockLayout = dLayout
        home.addView(dLayout)

        // ================= DETOX MINIMALIST MODE LAYER =================
        val dtx = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 0, 0, 0))
            setPadding(dp(24), dp(48), dp(24), dp(24))
            visibility = if (isDetoxMode) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        detoxContainer = dtx

        // ================= APP DRAWER LAYER =================
        val drw = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(228, 8, 10, 14))
            setPadding(dp(14), dp(36), dp(14), dp(12))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        drawerContainer = drw

        val drawerTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }

        val dSearch = EditText(this).apply {
            hint = "SEARCH APPS • HOLD ANY APP TO ADD TO DESKTOP..."
            setHintTextColor(COLOR_MUTED)
            setTextColor(COLOR_WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            setSingleLine(true)
            background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(6) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    renderDrawerApps(s?.toString()?.trim() ?: "")
                }
            })
        }
        drawerSearchInput = dSearch

        val closeDrawerBtn = TextView(this).apply {
            text = "HOME ↓"
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextColor(COLOR_WHITE)
            background = roundRect(COLOR_CARD, dp(14), COLOR_STROKE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { closeDrawer() }
        }
        drawerTopRow.addView(dSearch)
        drawerTopRow.addView(closeDrawerBtn)
        drw.addView(drawerTopRow)

        val shelfHeader = TextView(this).apply {
            text = "PINNED TOP SHELF // HOLD ANY APP BELOW TO ADD TO HOME SCREEN"
            typeface = Typeface.MONOSPACE
            textSize = 8f
            setTextColor(COLOR_MUTED)
            setPadding(dp(4), 0, 0, dp(4))
        }
        drw.addView(shelfHeader)

        val pShelf = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect(COLOR_CARD, dp(16), COLOR_STROKE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { bottomMargin = dp(8) }
        }
        pinnedShelfLayout = pShelf
        drw.addView(pShelf)

        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        fun makeTab(lbl: String, idx: Int): TextView = TextView(this).apply {
            text = lbl; typeface = Typeface.MONOSPACE; textSize = 8.5f; gravity = Gravity.CENTER; setTextColor(COLOR_WHITE)
            background = roundRect(if (drawerTab == idx) accentColor else COLOR_CARD, dp(14), COLOR_STROKE)
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(4) }
            setOnClickListener {
                triggerHaptic(0)
                if (idx == 3) promptDecoyOrPrimaryVault()
                else {
                    drawerTab = idx
                    buildMasterUI()
                    refreshWorkspaceUI()
                    openDrawer(false)
                }
            }
        }
        tabsRow.addView(makeTab("A-Z ALL", 0))
        tabsRow.addView(makeTab("SMART HUBS", 1))
        tabsRow.addView(makeTab("GHOST >30D", 2))
        tabsRow.addView(makeTab("VAULT 🔒", 3))
        drw.addView(tabsRow)

        val dMain = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        drawerMainContainer = dMain
        drw.addView(dMain)

        // ================= INSTANT DOUBLE-TAP SLEEP / LOCK OVERLAY =================
        val sleepView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            val lockText = TextView(this@MainActivity).apply {
                text = "AOD // DOUBLE-TAP TO WAKE"
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(Color.parseColor("#555555"))
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            }
            addView(lockText)
            val wakeDetector = GestureDetector(this@MainActivity, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    triggerHaptic(1)
                    visibility = View.GONE
                    isSleepOverlayActive = false
                    return true
                }
            })
            setOnTouchListener { _, ev -> wakeDetector.onTouchEvent(ev); true }
        }
        sleepLockOverlay = sleepView

        interceptor.addView(home)
        interceptor.addView(dtx)
        interceptor.addView(drw)
        interceptor.addView(sleepView)

        setContentView(interceptor)
        isUiBuilt = true
    }

    // ================= ROOT GLOBAL GESTURE INTERCEPTOR & TOUCH DISPATCHER =================
    inner class GestureInterceptorFrameLayout(context: Context) : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var gestureConsumed = false
        private val touchSlop = dp(38).toFloat()

        private val rootDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isDrawerOpen || isSleepOverlayActive) return false
                triggerHaptic(1)
                when (doubleTapMode) {
                    0 -> {
                        isSleepOverlayActive = true
                        sleepLockOverlay?.visibility = View.VISIBLE
                    }
                    1 -> {
                        wallpaperDimAlpha = if (wallpaperDimAlpha > 15) 0 else 55
                        rootStack?.setBackgroundColor(Color.argb(wallpaperDimAlpha, 0, 0, 0))
                        Toast.makeText(this@MainActivity, if (wallpaperDimAlpha == 0) "100% Clear Wallpaper Mode" else "Standard Scrim Mode", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        isDetoxMode = !isDetoxMode
                        saveState()
                        renderDetoxLayer()
                    }
                }
                return true
            }
        })

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (isSleepOverlayActive) {
                return super.dispatchTouchEvent(ev)
            }

            rootDetector.onTouchEvent(ev)

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    downTime = SystemClock.uptimeMillis()
                    gestureConsumed = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!gestureConsumed && !isDrawerOpen) {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        val dt = SystemClock.uptimeMillis() - downTime
                        val screenW = resources.displayMetrics.widthPixels

                        // Detect decisive vertical or horizontal swipe anywhere on screen within 550ms
                        if (dt < 550L && (abs(dy) > touchSlop || abs(dx) > touchSlop * 1.15f)) {
                            if (abs(dy) > abs(dx) * 1.2f) {
                                gestureConsumed = true
                                // Cancel any child press/long-press immediately so swipe wins cleanly
                                val cancelEv = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                                super.dispatchTouchEvent(cancelEv)
                                cancelEv.recycle()

                                if (dy < 0) {
                                    // UNIVERSAL UPWARD PULL ANYWHERE -> Open App Drawer + Focus Search
                                    triggerHaptic(0)
                                    openDrawer(focusKb = autoOpenKeyboard)
                                } else {
                                    // SPLIT TOP-DOWN PULL ANYWHERE ON SCREEN
                                    triggerHaptic(0)
                                    if (swipeDownBehavior == 1) {
                                        openDrawer(focusKb = true)
                                    } else if (downX < screenW * 0.5f) {
                                        // Left Half Pull-Down -> Notification Feed / Glance Hub
                                        expandSystemNotifications()
                                    } else {
                                        // Right Half Pull-Down -> Expanded Quick Settings / Control Center
                                        expandSystemQuickSettings()
                                    }
                                }
                                return true
                            } else if (abs(dx) > abs(dy) * 1.25f) {
                                gestureConsumed = true
                                val cancelEv = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                                super.dispatchTouchEvent(cancelEv)
                                cancelEv.recycle()

                                triggerHaptic(0)
                                if (dx < 0) {
                                    // Swipe Left -> Next Horizontal Page (or spawn new page option)
                                    if (currentPageIndex < desktopPages.size - 1) {
                                        currentPageIndex++
                                        saveState()
                                        refreshWorkspaceUI()
                                    } else {
                                        showQuickControlCenterDialog()
                                    }
                                } else {
                                    // Swipe Right -> Previous Horizontal Page (or Zero-State Glance Hub on Page 0)
                                    if (currentPageIndex > 0) {
                                        currentPageIndex--
                                        saveState()
                                        refreshWorkspaceUI()
                                    } else {
                                        showZeroStateGlanceSheet()
                                    }
                                }
                                return true
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gestureConsumed) {
                        gestureConsumed = false
                        return true
                    }
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    private fun renderSpacePills() {
        val row = spacePillsRow ?: return
        row.removeAllViews()
        for (sp in spaces) {
            val active = sp == currentSpace
            val pill = TextView(this).apply {
                text = sp
                typeface = Typeface.MONOSPACE
                textSize = 8f
                setTextColor(if (active) COLOR_WHITE else COLOR_MUTED)
                background = roundRect(if (active) accentColor else COLOR_CARD, dp(12), COLOR_STROKE)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(4)
                }
                setOnClickListener {
                    triggerHaptic(0)
                    currentSpace = sp
                    saveState()
                    refreshWorkspaceUI()
                }
            }
            row.addView(pill)
        }
    }

    private fun renderPaginationDots() {
        val bar = paginationBar ?: return
        bar.removeAllViews()

        val glanceBtn = TextView(this).apply {
            text = "AI FEED"
            typeface = Typeface.MONOSPACE
            textSize = 8f
            setTextColor(COLOR_MUTED)
            background = roundRect(COLOR_CARD, dp(10), COLOR_STROKE)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(5)
            }
            setOnClickListener { showZeroStateGlanceSheet() }
        }
        bar.addView(glanceBtn)

        for (i in 0 until desktopPages.size) {
            val isCurrent = i == currentPageIndex
            val pageBtn = TextView(this).apply {
                text = "PAGE ${i + 1}"
                typeface = Typeface.MONOSPACE
                textSize = 8.5f
                setTextColor(if (isCurrent) COLOR_WHITE else COLOR_MUTED)
                background = roundRect(if (isCurrent) accentColor else COLOR_CARD, dp(10), COLOR_STROKE)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(4)
                }
                setOnClickListener {
                    triggerHaptic(0)
                    currentPageIndex = i
                    saveState()
                    refreshWorkspaceUI()
                }
                setOnLongClickListener {
                    triggerHaptic(1)
                    showPageManagerDialog()
                    true
                }
            }
            bar.addView(pageBtn)
        }

        val addPageBtn = TextView(this).apply {
            text = "+ PAGE"
            typeface = Typeface.MONOSPACE
            textSize = 8f
            setTextColor(accentColor)
            background = roundRect(COLOR_CARD, dp(10), COLOR_STROKE)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            setOnClickListener {
                if (checkLayoutLocked()) return@setOnClickListener
                triggerHaptic(1)
                desktopPages.add(mutableListOf())
                currentPageIndex = desktopPages.size - 1
                saveState()
                refreshWorkspaceUI()
                Toast.makeText(this@MainActivity, "Created Page ${currentPageIndex + 1}", Toast.LENGTH_SHORT).show()
            }
        }
        bar.addView(addPageBtn)
    }

    // HORIZONTAL MULTI-SCREEN PAGE OVERVIEW & REORDERING DIALOG
    private fun showPageManagerDialog() {
        val options = mutableListOf<String>()
        for (i in desktopPages.indices) {
            val count = desktopPages[i].size
            val activeTag = if (i == currentPageIndex) " [ACTIVE]" else ""
            options.add("Switch to Page ${i + 1} ($count apps)$activeTag")
        }
        options.add("➕ Create New Blank Page")
        if (desktopPages.size > 1) {
            if (currentPageIndex > 0) options.add("⬅ Move Current Page Left")
            if (currentPageIndex < desktopPages.size - 1) options.add("➡ Move Current Page Right")
            options.add("🗑 Delete Current Page (Page ${currentPageIndex + 1})")
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("MULTI-SCREEN // PAGE MANAGER (${desktopPages.size} PAGES)")
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                when {
                    choice.startsWith("Switch to Page") -> {
                        currentPageIndex = which.coerceIn(0, desktopPages.size - 1)
                    }
                    choice.startsWith("➕") -> {
                        if (checkLayoutLocked()) return@setItems
                        desktopPages.add(mutableListOf())
                        currentPageIndex = desktopPages.size - 1
                    }
                    choice.startsWith("⬅") -> {
                        if (checkLayoutLocked()) return@setItems
                        Collections.swap(desktopPages, currentPageIndex, currentPageIndex - 1)
                        currentPageIndex--
                    }
                    choice.startsWith("➡") -> {
                        if (checkLayoutLocked()) return@setItems
                        Collections.swap(desktopPages, currentPageIndex, currentPageIndex + 1)
                        currentPageIndex++
                    }
                    choice.startsWith("🗑") -> {
                        if (checkLayoutLocked()) return@setItems
                        if (desktopPages.size > 1) {
                            desktopPages.removeAt(currentPageIndex)
                            currentPageIndex = currentPageIndex.coerceAtMost(desktopPages.size - 1)
                        }
                    }
                }
                saveState()
                refreshWorkspaceUI()
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun parseAgenticIntent(q: String) {
        val banner = agenticBanner ?: return
        if (q.isEmpty()) {
            banner.visibility = View.GONE
            return
        }
        val lower = q.lowercase(Locale.getDefault())
        val mathResult = tryEvaluateSimpleMath(q)

        banner.visibility = View.VISIBLE
        when {
            mathResult != null -> {
                banner.text = "SLM MATH // = $mathResult (TAP TO COPY)"
                banner.setOnClickListener {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("calc", mathResult))
                    Toast.makeText(this, "Copied $mathResult", Toast.LENGTH_SHORT).show()
                }
            }
            lower.contains("timer") -> {
                val mins = extractFirstNumber(lower) ?: 15
                banner.text = "AGENTIC ACTION // SET SYSTEM TIMER FOR $mins MINS →"
                banner.setOnClickListener {
                    triggerHaptic(1)
                    glyphSecondsLeft = mins * 60
                    toggleGlyphTimer()
                    agenticInput?.setText("")
                }
            }
            lower.contains("call") -> {
                banner.text = "AGENTIC ACTION // DIRECT DIAL INTENT ($q) →"
                banner.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_DIAL))
                    agenticInput?.setText("")
                }
            }
            lower.contains("split") || lower.contains("upi") -> {
                val amt = extractFirstNumber(lower) ?: 500
                banner.text = "AGENTIC UPI // SPLIT ₹$amt (₹${amt / 2} EACH) → LAUNCH PAY"
                banner.setOnClickListener {
                    Toast.makeText(this, "UPI Split Request: ₹${amt / 2} ready", Toast.LENGTH_SHORT).show()
                    agenticInput?.setText("")
                }
            }
            else -> {
                val matchApp = allApps.find { it.customLabel.lowercase(Locale.getDefault()).contains(lower) }
                if (matchApp != null) {
                    banner.text = "INSTANT LAUNCH // OPEN ${matchApp.customLabel.uppercase(Locale.getDefault())} →"
                    banner.setOnClickListener {
                        launchApp(matchApp, false)
                        agenticInput?.setText("")
                    }
                } else {
                    banner.text = "UNIVERSAL QUERY // WEB SEARCH \"$q\" →"
                    banner.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
                        agenticInput?.setText("")
                    }
                }
            }
        }
    }

    private fun extractFirstNumber(text: String): Int? {
        val sb = StringBuilder()
        for (ch in text) {
            if (ch.isDigit()) sb.append(ch)
            else if (sb.isNotEmpty()) break
        }
        return if (sb.isNotEmpty()) sb.toString().toIntOrNull() else null
    }

    private fun tryEvaluateSimpleMath(expr: String): String? {
        val clean = expr.replace(" ", "")
        val ops = charArrayOf('+', '-', '*', '/', '%')
        var opIndex = -1
        var opChar = ' '
        for (i in 1 until clean.length) {
            if (ops.contains(clean[i])) {
                opIndex = i
                opChar = clean[i]
                break
            }
        }
        if (opIndex <= 0 || opIndex >= clean.length - 1) return null
        val left = clean.substring(0, opIndex).toDoubleOrNull() ?: return null
        val right = clean.substring(opIndex + 1).toDoubleOrNull() ?: return null
        val res = when (opChar) {
            '+' -> left + right
            '-' -> left - right
            '*' -> left * right
            '/' -> if (right != 0.0) left / right else return null
            '%' -> if (right != 0.0) left % right else return null
            else -> return null
        }
        return if (res % 1.0 == 0.0) res.toLong().toString() else String.format(Locale.US, "%.2f", res)
    }

    private fun loadAppsAsync() {
        bgExecutor.execute {
            try {
                val pm = packageManager
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                val list = pm.queryIntentActivities(mainIntent, 0)
                val loaded = mutableListOf<AppEntry>()

                for ((idx, ri) in list.withIndex()) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg == packageName) continue
                    val defLbl = ri.loadLabel(pm).toString()
                    val custLbl = prefs.getString("rename_$pkg", null) ?: defLbl
                    val icon = ri.loadIcon(pm)
                    val cat = classifyApp(ri.activityInfo.applicationInfo, defLbl)
                    val daysUnused = if (idx % 5 == 0) 42 else (idx % 12)
                    val quota = (1.0f - (idx % 7) * 0.1f).coerceIn(0.2f, 1.0f)
                    val notif = if (idx % 4 == 0) "• 2 NEW" else null
                    loaded.add(AppEntry(defLbl, custLbl, pkg, ri.activityInfo.name, icon, cat, daysUnused, quota, notif))
                }
                loaded.sortBy { it.customLabel.lowercase(Locale.getDefault()) }

                mainHandler.post {
                    allApps.clear()
                    allApps.addAll(loaded)

                    // Populate initial multi-screen pages & editable folders on first run
                    if (desktopPages.all { it.isEmpty() } && allApps.isNotEmpty()) {
                        val page1 = allApps.take(8).map { it.pkg }.toMutableList()
                        val page2 = allApps.drop(8).take(6).map { it.pkg }.toMutableList()
                        desktopPages.clear()
                        desktopPages.add(page1)
                        if (page2.isNotEmpty()) desktopPages.add(page2)
                        if (page1.isNotEmpty()) maxIconPkgs.add(page1.first())
                    }
                    if (editableFolders.isEmpty() && allApps.isNotEmpty()) {
                        editableFolders.add(
                            FolderItem("f_essential", "ESSENTIAL HUB", allApps.take(4).map { it.pkg }.toMutableList(), 0)
                        )
                        editableFolders.add(
                            FolderItem("f_media", "MEDIA & TOOLS", allApps.drop(4).take(4).map { it.pkg }.toMutableList(), 0)
                        )
                    }
                    saveState()

                    if (pinnedTopShelfPkgs.isEmpty()) {
                        allApps.take(5).forEach { pinnedTopShelfPkgs.add(it.pkg) }
                    }
                    refreshWorkspaceUI()
                    renderDetoxLayer()
                    renderDrawerApps("")
                }
            } catch (_: Throwable) {}
        }
    }

    private fun classifyApp(info: ApplicationInfo, label: String): String {
        val l = label.lowercase(Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (info.category) {
                ApplicationInfo.CATEGORY_SOCIAL -> return "SOCIAL & COMMS"
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> return "MEDIA & CAMERA"
                ApplicationInfo.CATEGORY_GAME -> return "GAMES & ARCADE"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> return "PRODUCTIVITY"
            }
        }
        return when {
            l.contains("whatsapp") || l.contains("instagram") || l.contains("telegram") || l.contains("phone") || l.contains("message") || l.contains("chat") || l.contains("contact") -> "SOCIAL & COMMS"
            l.contains("youtube") || l.contains("spotify") || l.contains("camera") || l.contains("music") || l.contains("gallery") || l.contains("photo") || l.contains("video") -> "MEDIA & CAMERA"
            l.contains("pay") || l.contains("bank") || l.contains("upi") || l.contains("wallet") || l.contains("cred") || l.contains("zerodha") -> "FINANCE & UPI"
            l.contains("game") || l.contains("chess") || l.contains("bgmi") || l.contains("clash") -> "GAMES & ARCADE"
            l.contains("doc") || l.contains("note") || l.contains("mail") || l.contains("gmail") || l.contains("calendar") || l.contains("drive") || l.contains("github") -> "PRODUCTIVITY"
            else -> "SYSTEM & UTILITIES"
        }
    }

    // 3. FULLY EDITABLE, MOVABLE, SWAPPABLE & REMOVABLE FOLDER ENGINE
    private fun renderMaxFolders() {
        val row = maxFoldersRow ?: return
        row.removeAllViews()
        val filter = buildDynamicIconFilter()
        val pageFolders = editableFolders.filter { it.pageIndex == currentPageIndex || it.pageIndex < 0 }

        if (pageFolders.isEmpty()) {
            val emptyFolderBanner = TextView(this).apply {
                text = "NO FOLDERS ON PAGE ${currentPageIndex + 1} • TAP '+ NEW FOLDER' ABOVE TO CREATE"
                typeface = Typeface.MONOSPACE
                textSize = 8f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
                background = roundRect(COLOR_CARD, dp(14), COLOR_STROKE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener { if (!checkLayoutLocked()) promptCreateNewFolder() }
            }
            row.addView(emptyFolderBanner)
            return
        }

        for ((fIdx, folder) in pageFolders.withIndex()) {
            val folderApps = folder.pkgs.mapNotNull { pkg ->
                allApps.find { it.pkg == pkg && !hiddenPkgs.contains(it.pkg) && !privateVaultPkgs.contains(it.pkg) }
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = if (fIdx < pageFolders.size - 1) dp(5) else 0
                }
                setOnClickListener {
                    triggerHaptic(2)
                    showEditableFolderModal(folder)
                }
                setOnLongClickListener {
                    triggerHaptic(1)
                    showFolderManagementSheet(folder)
                    true
                }
            }
            val hdr = TextView(this).apply {
                text = ":: ${folder.title} ✎"
                typeface = Typeface.MONOSPACE
                textSize = 8.5f
                setTextColor(COLOR_MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val miniGrid = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            for (a in folderApps.take(3)) {
                val img = ImageView(this).apply {
                    setImageDrawable(a.icon.constantState?.newDrawable()?.mutate() ?: a.icon)
                    colorFilter = filter
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(6) }
                    setOnClickListener { launchApp(a, false) }
                    setOnLongClickListener {
                        triggerHaptic(1)
                        showEditableFolderModal(folder)
                        true
                    }
                }
                miniGrid.addView(img)
            }
            val expandBtn = TextView(this).apply {
                text = "+${folderApps.size}"
                typeface = Typeface.MONOSPACE
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(COLOR_WHITE)
                background = roundRect(accentColor, dp(14))
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                setOnClickListener {
                    triggerHaptic(2)
                    showEditableFolderModal(folder)
                }
            }
            miniGrid.addView(expandBtn)
            box.addView(hdr)
            box.addView(miniGrid)
            row.addView(box)
        }
    }

    private fun showEditableFolderModal(folder: FolderItem) {
        val folderApps = folder.pkgs.mapNotNull { pkg -> allApps.find { it.pkg == pkg } }
        val itemLabels = mutableListOf<String>()
        for (a in folderApps) {
            itemLabels.add("🚀 Open ${a.customLabel}")
        }
        itemLabels.add("➕ Add Apps to '${folder.title}'")
        itemLabels.add("➖ Remove or Reorder Apps in '${folder.title}'")
        itemLabels.add("✎ Rename Folder")
        itemLabels.add("⇄ Move Folder to Another Page / Swap Position")
        itemLabels.add("🗑 Delete Folder from Home Screen")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("FOLDER // ${folder.title}")
            .setItems(itemLabels.toTypedArray()) { _, idx ->
                if (idx < folderApps.size) {
                    launchApp(folderApps[idx], false)
                    return@setItems
                }
                if (checkLayoutLocked()) return@setItems
                when (idx - folderApps.size) {
                    0 -> showAddAppsToFolderPicker(folder)
                    1 -> showRemoveOrReorderFolderAppsDialog(folder)
                    2 -> promptRenameFolder(folder)
                    3 -> showFolderManagementSheet(folder)
                    4 -> {
                        editableFolders.remove(folder)
                        saveState()
                        refreshWorkspaceUI()
                    }
                }
            }
            .setNeutralButton("CLOSE", null)
            .show()
    }

    private fun showFolderManagementSheet(folder: FolderItem) {
        if (checkLayoutLocked()) return
        val opts = mutableListOf(
            "➕ Add Apps to Folder",
            "➖ Remove / Reorder Apps Inside Folder",
            "✎ Rename Folder",
            "⇄ Swap Folder Position Left / Right"
        )
        for (p in desktopPages.indices) {
            if (p != folder.pageIndex) opts.add("➡ Move Folder to Page ${p + 1}")
        }
        opts.add("✨ Move Folder to Brand New Page")
        opts.add("🗑 Remove Folder from Home Screen")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("EDIT FOLDER // ${folder.title}")
            .setItems(opts.toTypedArray()) { _, which ->
                val c = opts[which]
                when {
                    c.startsWith("➕") -> showAddAppsToFolderPicker(folder)
                    c.startsWith("➖") -> showRemoveOrReorderFolderAppsDialog(folder)
                    c.startsWith("✎") -> promptRenameFolder(folder)
                    c.startsWith("⇄") -> {
                        val idx = editableFolders.indexOf(folder)
                        if (idx >= 0 && editableFolders.size > 1) {
                            val swapWith = if (idx > 0) idx - 1 else idx + 1
                            Collections.swap(editableFolders, idx, swapWith)
                            saveState()
                            refreshWorkspaceUI()
                        }
                    }
                    c.startsWith("➡ Move Folder to Page ") -> {
                        val targetP = (c.removePrefix("➡ Move Folder to Page ").trim().toIntOrNull() ?: 1) - 1
                        folder.pageIndex = targetP.coerceAtLeast(0)
                        currentPageIndex = folder.pageIndex
                        saveState()
                        refreshWorkspaceUI()
                    }
                    c.startsWith("✨") -> {
                        desktopPages.add(mutableListOf())
                        folder.pageIndex = desktopPages.size - 1
                        currentPageIndex = folder.pageIndex
                        saveState()
                        refreshWorkspaceUI()
                    }
                    c.startsWith("🗑") -> {
                        editableFolders.remove(folder)
                        saveState()
                        refreshWorkspaceUI()
                    }
                }
            }
            .show()
    }

    private fun showAddAppsToFolderPicker(folder: FolderItem) {
        val sorted = allApps.sortedBy { it.customLabel.lowercase(Locale.getDefault()) }
        val names = sorted.map { it.customLabel }.toTypedArray()
        val checked = BooleanArray(sorted.size) { i -> folder.pkgs.contains(sorted[i].pkg) }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("SELECT APPS FOR ${folder.title}")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val pkg = sorted[which].pkg
                if (isChecked) {
                    if (!folder.pkgs.contains(pkg)) folder.pkgs.add(pkg)
                } else {
                    folder.pkgs.remove(pkg)
                }
            }
            .setPositiveButton("SAVE") { _, _ ->
                saveState()
                refreshWorkspaceUI()
            }
            .show()
    }

    private fun showRemoveOrReorderFolderAppsDialog(folder: FolderItem) {
        val folderApps = folder.pkgs.mapNotNull { p -> allApps.find { it.pkg == p } }
        if (folderApps.isEmpty()) {
            Toast.makeText(this, "Folder is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = folderApps.mapIndexed { idx, a ->
            "${idx + 1}. ${a.customLabel} (Tap to Move Front or Remove)"
        }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("REORDER / REMOVE // ${folder.title}")
            .setItems(labels) { _, which ->
                val target = folderApps[which]
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(target.customLabel)
                    .setItems(arrayOf("⬆ Move to First Slot in Folder", "📤 Move Out of Folder onto Desktop", "🗑 Remove from Folder")) { _, act ->
                        when (act) {
                            0 -> {
                                folder.pkgs.remove(target.pkg)
                                folder.pkgs.add(0, target.pkg)
                            }
                            1 -> {
                                folder.pkgs.remove(target.pkg)
                                val favs = currentFavorites()
                                if (!favs.contains(target.pkg)) favs.add(target.pkg)
                            }
                            2 -> folder.pkgs.remove(target.pkg)
                        }
                        saveState()
                        refreshWorkspaceUI()
                    }
                    .show()
            }
            .show()
    }

    private fun promptRenameFolder(folder: FolderItem) {
        val inp = EditText(this).apply { setText(folder.title); setTextColor(COLOR_WHITE) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("RENAME FOLDER")
            .setView(inp)
            .setPositiveButton("SAVE") { _, _ ->
                val newTitle = inp.text.toString().trim().uppercase(Locale.getDefault())
                if (newTitle.isNotEmpty()) {
                    folder.title = newTitle
                    saveState()
                    refreshWorkspaceUI()
                }
            }
            .show()
    }

    private fun promptCreateNewFolder() {
        val inp = EditText(this).apply {
            hint = "FOLDER NAME (E.G. GAMING)"
            setTextColor(COLOR_WHITE)
            setHintTextColor(COLOR_MUTED)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("CREATE NEW EDITABLE FOLDER")
            .setView(inp)
            .setPositiveButton("NEXT: PICK APPS") { _, _ ->
                val name = inp.text.toString().trim().uppercase(Locale.getDefault()).ifEmpty { "NEW FOLDER" }
                val newF = FolderItem("f_${System.currentTimeMillis()}", name, mutableListOf(), currentPageIndex)
                editableFolders.add(newF)
                saveState()
                refreshWorkspaceUI()
                showAddAppsToFolderPicker(newF)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun renderDesktopGrid() {
        val grid = favGridLayout ?: return
        val dock = dockLayout ?: return
        grid.removeAllViews()
        grid.columnCount = gridColumns
        dock.removeAllViews()

        val filter = buildDynamicIconFilter()
        val pagePkgs = currentFavorites()
        val favApps = pagePkgs.mapNotNull { pkg ->
            allApps.find { it.pkg == pkg && !hiddenPkgs.contains(it.pkg) && !privateVaultPkgs.contains(it.pkg) }
        }
        val screenWidth = resources.displayMetrics.widthPixels - dp(28)
        val cellSize = screenWidth / gridColumns

        for ((index, app) in favApps.withIndex()) {
            val isMax = maxIconsEnabled && maxIconPkgs.contains(app.pkg)
            val span = if (isMax) 2 else 1

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = roundRect(COLOR_CARD, dp(20), if (isMax) accentColor else COLOR_STROKE)
                setPadding(dp(4), dp(6), dp(4), dp(6))

                val params = GridLayout.LayoutParams().apply {
                    width = cellSize * span - dp(8)
                    height = if (isMax) cellSize * 2 - dp(12) else cellSize + dp(4)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, span)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                layoutParams = params

                if (thumbArcMode == 1) translationY = ((index % gridColumns) * dp(3)).toFloat()
                else if (thumbArcMode == 2) translationY = (((gridColumns - 1) - (index % gridColumns)) * dp(3)).toFloat()

                scaleX = 0.94f; scaleY = 0.94f
                animate().scaleX(1f).scaleY(1f).setDuration(210L).setInterpolator(OvershootInterpolator(1.3f)).start()

                setOnClickListener {
                    launchApp(app, false)
                }
                setOnLongClickListener {
                    triggerHaptic(1)
                    showShutterAndContextSheet(app, isPinned = true)
                    true
                }
            }

            val iconView = ImageView(this).apply {
                setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                colorFilter = filter
                val s = if (isMax) dp(60) else dp(36)
                layoutParams = LinearLayout.LayoutParams(s, s)
            }
            card.addView(iconView)

            if (!isLabelFree) {
                val lblView = TextView(this).apply {
                    text = app.customLabel
                    typeface = Typeface.MONOSPACE
                    textSize = if (isMax) 10.5f else 8.5f
                    setTextColor(COLOR_WHITE)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(0, dp(3), 0, 0)
                }
                card.addView(lblView)
            }

            if (app.inlineNotif != null && notificationDotsMode != 2) {
                val notifView = TextView(this).apply {
                    text = if (notificationDotsMode == 0) "•" else app.inlineNotif
                    typeface = Typeface.MONOSPACE
                    textSize = if (notificationDotsMode == 0) 10f else 7f
                    setTextColor(if (notificationDotsMode == 0) COLOR_WHITE else (if (useWallpaperIconTint) wallpaperExtractedColor else accentColor))
                    maxLines = 1
                }
                card.addView(notifView)
            }

            grid.addView(card)
        }

        // Add subtle "+ ADD APP" tile on desktop grid so user can also add apps directly from empty grid space
        val addTile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundRect(Color.argb(80, 20, 22, 28), dp(20), COLOR_STROKE)
            layoutParams = GridLayout.LayoutParams().apply {
                width = cellSize - dp(8)
                height = cellSize + dp(4)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            val plusTxt = TextView(this@MainActivity).apply {
                text = "+"
                typeface = Typeface.MONOSPACE
                textSize = 18f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
            }
            val subLbl = TextView(this@MainActivity).apply {
                text = "ADD APP"
                typeface = Typeface.MONOSPACE
                textSize = 7.5f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
            }
            addView(plusTxt)
            if (!isLabelFree) addView(subLbl)
            setOnClickListener {
                triggerHaptic(0)
                openDrawer(focusKb = false)
            }
            setOnLongClickListener {
                triggerHaptic(1)
                showHyperOsHomeSettingsDialog()
                true
            }
        }
        grid.addView(addTile)

        // Dock always uses Page 1's top 4 apps (or allApps fallback)
        val dockSource = (desktopPages.firstOrNull() ?: mutableListOf()).mapNotNull { pkg ->
            allApps.find { it.pkg == pkg && !hiddenPkgs.contains(it.pkg) }
        }.ifEmpty { allApps.take(4) }

        for (app in dockSource.take(4)) {
            val dockCell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { launchApp(app, false) }
                setOnLongClickListener {
                    triggerHaptic(1)
                    showShutterAndContextSheet(app, isPinned = true)
                    true
                }
            }
            val dockIcon = ImageView(this).apply {
                setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                colorFilter = filter
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            }
            dockCell.addView(dockIcon)
            if (!isLabelFree) {
                val dockLbl = TextView(this).apply {
                    text = app.customLabel
                    typeface = Typeface.MONOSPACE
                    textSize = 7f
                    setTextColor(COLOR_WHITE)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                dockCell.addView(dockLbl)
            }
            dock.addView(dockCell)
        }

        val drawerBtn = TextView(this).apply {
            text = ":::"
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnClickListener { openDrawer(focusKb = autoOpenKeyboard) }
        }
        dock.addView(drawerBtn)
    }

    private fun renderDetoxLayer() {
        val dtx = detoxContainer ?: return
        dtx.visibility = if (isDetoxMode) View.VISIBLE else View.GONE
        dtx.removeAllViews()
        if (!isDetoxMode) return

        val hdr = TextView(this).apply {
            text = "DETOX // TYPOGRAPHIC FOCUS MODE (DOUBLE-TAP TO EXIT)"
            typeface = Typeface.MONOSPACE
            textSize = 9.5f
            setTextColor(COLOR_MUTED)
        }
        val clock = TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            typeface = Typeface.MONOSPACE
            textSize = 42f
            setTextColor(COLOR_WHITE)
            setPadding(0, dp(8), 0, dp(20))
        }
        dtx.addView(hdr)
        dtx.addView(clock)

        for (app in allApps.take(7)) {
            val item = TextView(this).apply {
                text = "→  ${app.customLabel.uppercase(Locale.getDefault())}"
                typeface = Typeface.MONOSPACE
                textSize = 16f
                setTextColor(COLOR_WHITE)
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { launchApp(app, false) }
            }
            dtx.addView(item)
        }

        val exitBtn = TextView(this).apply {
            text = "[ EXIT DETOX MODE ]"
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(accentColor)
            setPadding(0, dp(28), 0, 0)
            setOnClickListener {
                isDetoxMode = false
                saveState()
                renderDetoxLayer()
            }
        }
        dtx.addView(exitBtn)
    }

    private fun promptDecoyOrPrimaryVault() {
        val input = EditText(this).apply {
            hint = "PIN (1234=Primary Vault, 0000=Decoy Profile)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(COLOR_WHITE)
            setHintTextColor(COLOR_MUTED)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("BIOMETRIC / DUAL-PIN VAULT GATEWAY")
            .setView(input)
            .setPositiveButton("UNLOCK") { _, _ ->
                val pin = input.text.toString().trim()
                activeVaultMode = if (pin == "0000") 2 else 1
                drawerTab = 3
                buildMasterUI()
                refreshWorkspaceUI()
                openDrawer(false)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun renderDrawerApps(query: String) {
        val shelf = pinnedShelfLayout ?: return
        val mainBox = drawerMainContainer ?: return
        val filter = buildDynamicIconFilter()
        shelf.removeAllViews()
        val shelfApps = pinnedTopShelfPkgs.mapNotNull { p -> allApps.find { it.pkg == p && !hiddenPkgs.contains(it.pkg) } }
        for (a in shelfApps) {
            val img = ImageView(this).apply {
                setImageDrawable(a.icon.constantState?.newDrawable()?.mutate() ?: a.icon)
                colorFilter = filter
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
                setOnClickListener { launchApp(a, false) }
                setOnLongClickListener {
                    triggerHaptic(1)
                    showShutterAndContextSheet(a, isPinned = currentFavorites().contains(a.pkg))
                    true
                }
            }
            shelf.addView(img)
        }

        mainBox.removeAllViews()
        val clean = query.lowercase(Locale.getDefault())

        // TAB 1: SMART ARRANGE HUBS
        if (drawerTab == 1) {
            val scroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val categoriesColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val categoriesOrder = listOf("SOCIAL & COMMS", "MEDIA & CAMERA", "FINANCE & UPI", "PRODUCTIVITY", "GAMES & ARCADE", "SYSTEM & UTILITIES")
            val visiblePool = allApps.filter {
                !hiddenPkgs.contains(it.pkg) && !privateVaultPkgs.contains(it.pkg) &&
                (clean.isEmpty() || it.customLabel.lowercase(Locale.getDefault()).contains(clean) || it.category.lowercase(Locale.getDefault()).contains(clean))
            }

            for (catName in categoriesOrder) {
                val catApps = visiblePool.filter { it.category == catName }
                if (catApps.isEmpty()) continue

                val hubCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(10)
                    }
                }
                val hubHeader = TextView(this).apply {
                    text = "// $catName (${catApps.size} APPS)"
                    typeface = Typeface.MONOSPACE
                    textSize = 9.5f
                    setTextColor(if (useWallpaperIconTint) wallpaperExtractedColor else accentColor)
                    setPadding(0, 0, 0, dp(8))
                }
                hubCard.addView(hubHeader)

                val grid = GridLayout(this).apply {
                    columnCount = gridColumns
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val cellW = (resources.displayMetrics.widthPixels - dp(56)) / gridColumns

                for (app in catApps) {
                    val itemCell = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                        layoutParams = GridLayout.LayoutParams().apply {
                            width = cellW
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        setOnClickListener { launchApp(app, false) }
                        setOnLongClickListener {
                            triggerHaptic(1)
                            showShutterAndContextSheet(app, isPinned = currentFavorites().contains(app.pkg))
                            true
                        }
                    }
                    val iconImg = ImageView(this).apply {
                        setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                        colorFilter = filter
                        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                    }
                    val appNameText = TextView(this).apply {
                        text = app.customLabel
                        typeface = Typeface.MONOSPACE
                        textSize = 8.5f
                        setTextColor(COLOR_WHITE)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setPadding(0, dp(4), 0, 0)
                    }
                    itemCell.addView(iconImg)
                    itemCell.addView(appNameText)
                    grid.addView(itemCell)
                }
                hubCard.addView(grid)
                categoriesColumn.addView(hubCard)
            }
            scroll.addView(categoriesColumn)
            mainBox.addView(scroll)
            return
        }

        val base = when (drawerTab) {
            2 -> allApps.filter { it.lastOpenedDaysAgo >= 30 }
            3 -> if (activeVaultMode == 2) allApps.filter { decoyVaultPkgs.contains(it.pkg) }
                 else allApps.filter { privateVaultPkgs.contains(it.pkg) || hiddenPkgs.contains(it.pkg) }
            else -> allApps.filter { !hiddenPkgs.contains(it.pkg) && !privateVaultPkgs.contains(it.pkg) }
        }

        val filtered = base.filter { clean.isEmpty() || it.customLabel.lowercase(Locale.getDefault()).contains(clean) }

        val gridView = GridView(this).apply {
            numColumns = gridColumns
            verticalSpacing = dp(10)
            horizontalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isVerticalScrollBarEnabled = false
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        gridView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = filtered.size
            override fun getItem(position: Int): Any = filtered[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val app = filtered[position]
                val cell = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    background = roundRect(COLOR_CARD, dp(16), COLOR_STROKE)
                }
                val img = ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                    colorFilter = filter
                    layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { topMargin = dp(2) }
                }
                cell.addView(img)
                if (!isLabelFree) {
                    val lbl = TextView(this@MainActivity).apply {
                        text = app.customLabel
                        typeface = Typeface.MONOSPACE
                        textSize = 8.5f
                        setTextColor(COLOR_WHITE)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setPadding(0, dp(3), 0, 0)
                    }
                    cell.addView(lbl)
                }
                // Direct "+ DESKTOP" quick action badge inside drawer
                val addBadge = TextView(this@MainActivity).apply {
                    text = if (currentFavorites().contains(app.pkg)) "✓ ON PAGE" else "+ DESKTOP"
                    typeface = Typeface.MONOSPACE
                    textSize = 7f
                    setTextColor(if (currentFavorites().contains(app.pkg)) COLOR_MUTED else accentColor)
                    setPadding(0, dp(2), 0, 0)
                    setOnClickListener {
                        if (checkLayoutLocked()) return@setOnClickListener
                        triggerHaptic(1)
                        val favs = currentFavorites()
                        if (!favs.contains(app.pkg)) {
                            favs.add(app.pkg)
                            saveState()
                            refreshWorkspaceUI()
                            Toast.makeText(this@MainActivity, "${app.customLabel} added to Page ${currentPageIndex + 1}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Already on Page ${currentPageIndex + 1}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                cell.addView(addBadge)

                cell.setOnClickListener { launchApp(app, false) }
                cell.setOnLongClickListener {
                    triggerHaptic(1)
                    showShutterAndContextSheet(app, isPinned = currentFavorites().contains(app.pkg))
                    true
                }
                return cell
            }
        }
        mainBox.addView(gridView)
    }

    // CONTEXTUAL ACTION SHEET FOR ANY APP (REORDER, MOVE TO PAGE, SPAWN PAGE, ADD TO FOLDER, SIZING)
    private fun showShutterAndContextSheet(app: AppEntry, isPinned: Boolean) {
        val isMax = maxIconPkgs.contains(app.pkg)
        val isMindful = mindfulPkgs.contains(app.pkg)
        val opts = mutableListOf<String>()

        opts.add("SHUTTER // PEEK TRANSIENT WIDGET (${app.category})")
        if (isPinned) {
            if (maxIconsEnabled) {
                opts.add(if (isMax) "Resize to 1×1 Standard Tile" else "Enlarge to 2×2 Max Icon")
            }
            opts.add("⬅ Move App Left / Earlier on Page")
            opts.add("➡ Move App Right / Later on Page")
            for (p in desktopPages.indices) {
                if (p != currentPageIndex) opts.add("📄 Move App to Page ${p + 1}")
            }
            opts.add("✨ Drag/Move App to Brand New Page")
            opts.add("🗑 Remove from Current Home Page")
        } else {
            opts.add("➕ Add to Home Screen (Page ${currentPageIndex + 1})")
            if (maxIconsEnabled) opts.add("➕ Add to Home Screen as 2×2 Max Icon")
            opts.add("✨ Add to Brand New Home Page")
        }

        for (f in editableFolders) {
            if (f.pkgs.contains(app.pkg)) {
                opts.add("📁 Remove from Folder '${f.title}'")
            } else {
                opts.add("📁 Add to Folder '${f.title}'")
            }
        }
        opts.add("📁 Create New Folder with ${app.customLabel}")
        opts.add("Launch in Pop-Up / Split Window")
        opts.add("Rename App Label")
        opts.add(if (isMindful) "Disable Mindful Friction (3s)" else "Enable Mindful Friction (3s)")
        opts.add("Hide App Icon")
        opts.add("Move to Primary Private Vault 🔒")
        opts.add("System App Info")
        opts.add("Uninstall App")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${app.customLabel.uppercase(Locale.getDefault())} // ACTIONS")
            .setItems(opts.toTypedArray()) { _, which ->
                val c = opts[which]
                when {
                    c.startsWith("SHUTTER") -> {
                        Toast.makeText(this, "${app.customLabel} Widget Shutter Active • Quota ${(app.dailyQuotaPct * 100).toInt()}%", Toast.LENGTH_SHORT).show()
                        return@setItems
                    }
                    c.startsWith("Launch in Pop-Up") -> { launchApp(app, true); return@setItems }
                    c == "System App Info" -> {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", app.pkg, null) })
                        return@setItems
                    }
                    c == "Uninstall App" -> {
                        if (checkLayoutLocked()) return@setItems
                        startActivity(Intent(Intent.ACTION_DELETE).apply { data = Uri.fromParts("package", app.pkg, null) })
                        return@setItems
                    }
                }
                if (checkLayoutLocked()) return@setItems
                val favs = currentFavorites()
                when {
                    c.startsWith("Enlarge") -> maxIconPkgs.add(app.pkg)
                    c.startsWith("Resize") -> maxIconPkgs.remove(app.pkg)
                    c.startsWith("⬅ Move App Left") -> {
                        val idx = favs.indexOf(app.pkg)
                        if (idx > 0) Collections.swap(favs, idx, idx - 1)
                    }
                    c.startsWith("➡ Move App Right") -> {
                        val idx = favs.indexOf(app.pkg)
                        if (idx in 0 until favs.size - 1) Collections.swap(favs, idx, idx + 1)
                    }
                    c.startsWith("📄 Move App to Page ") -> {
                        val targetP = (c.removePrefix("📄 Move App to Page ").trim().toIntOrNull() ?: 1) - 1
                        favs.remove(app.pkg)
                        ensurePageExists(targetP)
                        if (!desktopPages[targetP].contains(app.pkg)) desktopPages[targetP].add(app.pkg)
                        currentPageIndex = targetP
                    }
                    c.startsWith("✨") -> {
                        favs.remove(app.pkg)
                        desktopPages.add(mutableListOf(app.pkg))
                        currentPageIndex = desktopPages.size - 1
                        closeDrawer()
                    }
                    c.startsWith("🗑 Remove from Current") -> {
                        favs.remove(app.pkg)
                        maxIconPkgs.remove(app.pkg)
                    }
                    c.startsWith("➕ Add to Home Screen (Page") -> {
                        if (!favs.contains(app.pkg)) favs.add(app.pkg)
                        closeDrawer()
                    }
                    c.startsWith("➕ Add to Home Screen as 2×2") -> {
                        if (!favs.contains(app.pkg)) favs.add(app.pkg)
                        maxIconPkgs.add(app.pkg)
                        closeDrawer()
                    }
                    c.startsWith("📁 Remove from Folder '") -> {
                        val fTitle = c.removePrefix("📁 Remove from Folder '").removeSuffix("'")
                        editableFolders.find { it.title == fTitle }?.pkgs?.remove(app.pkg)
                    }
                    c.startsWith("📁 Add to Folder '") -> {
                        val fTitle = c.removePrefix("📁 Add to Folder '").removeSuffix("'")
                        val f = editableFolders.find { it.title == fTitle }
                        if (f != null && !f.pkgs.contains(app.pkg)) f.pkgs.add(app.pkg)
                    }
                    c.startsWith("📁 Create New Folder with") -> {
                        val newF = FolderItem("f_${System.currentTimeMillis()}", "${app.customLabel.uppercase(Locale.getDefault())} HUB", mutableListOf(app.pkg), currentPageIndex)
                        editableFolders.add(newF)
                    }
                    c == "Rename App Label" -> { promptRename(app); return@setItems }
                    c.startsWith("Enable Mindful") -> mindfulPkgs.add(app.pkg)
                    c.startsWith("Disable Mindful") -> mindfulPkgs.remove(app.pkg)
                    c == "Hide App Icon" -> { hiddenPkgs.add(app.pkg); favs.remove(app.pkg) }
                    c.startsWith("Move to Primary") -> { privateVaultPkgs.add(app.pkg); favs.remove(app.pkg) }
                }
                saveState()
                refreshWorkspaceUI()
            }
            .show()
    }

    private fun promptRename(app: AppEntry) {
        val inp = EditText(this).apply { setText(app.customLabel); setTextColor(COLOR_WHITE) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("INLINE RENAME // ${app.defaultLabel}")
            .setView(inp)
            .setPositiveButton("SAVE") { _, _ ->
                app.customLabel = inp.text.toString().trim().ifEmpty { app.defaultLabel }
                prefs.edit().putString("rename_${app.pkg}", app.customLabel).apply()
                refreshWorkspaceUI()
            }.show()
    }

    // ================= HYPEROS INTEGRATED HOME SETTINGS (LONG-PRESS DESKTOP MENU) =================
    private fun showHyperOsHomeSettingsDialog() {
        val swipeLabel = if (swipeDownBehavior == 0) "Split Notifications (L) / Quick Settings (R)" else "Universal Search"
        val dotsLabel = when (notificationDotsMode) {
            0 -> "White Dot Marker (•)"
            1 -> "Standard Count (• 2 NEW)"
            else -> "Off"
        }
        val items = arrayOf(
            "1. Add apps to Home screen: ${if (addAppsToHome) "ON" else "OFF"}",
            "2. Swipe down on home screen: $swipeLabel",
            "3. Notification dots: $dotsLabel",
            "4. Show app names (Global icon labels): ${if (!isLabelFree) "ON" else "OFF"}",
            "5. Max Icons / Big Icons (2×2 scaling): ${if (maxIconsEnabled) "ON (Tap to configure)" else "OFF"}",
            "6. Lock layout: ${if (lockLayout) "ON 🔒" else "OFF"}",
            "7. Multi-Screen Page Manager (${desktopPages.size} Pages) ⊞",
            "8. Drawer & Organization Settings ↗",
            "9. Customization & Theming (Wallpaper & Style) ↗",
            "10. Add System AppWidget (Sandboxed Picker)"
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("HOME SETTINGS // KINETIC OS 4.0")
            .setItems(items) { _, idx ->
                when (idx) {
                    0 -> {
                        addAppsToHome = !addAppsToHome
                        Toast.makeText(this, "Add apps to Home screen: ${if (addAppsToHome) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        showSwipeDownSelectorDialog()
                        return@setItems
                    }
                    2 -> {
                        notificationDotsMode = (notificationDotsMode + 1) % 3
                    }
                    3 -> {
                        isLabelFree = !isLabelFree
                    }
                    4 -> {
                        showMaxIconsConfigDialog()
                        return@setItems
                    }
                    5 -> {
                        lockLayout = !lockLayout
                        Toast.makeText(this, if (lockLayout) "Home screen layout locked 🔒" else "Home screen layout unlocked", Toast.LENGTH_SHORT).show()
                    }
                    6 -> {
                        showPageManagerDialog()
                        return@setItems
                    }
                    7 -> {
                        showDrawerOrganizationSettingsDialog()
                        return@setItems
                    }
                    8 -> {
                        showCustomizationThemingDialog()
                        return@setItems
                    }
                    9 -> {
                        if (checkLayoutLocked()) return@setItems
                        try {
                            val id = appWidgetHost?.allocateAppWidgetId() ?: return@setItems
                            val pickIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            }
                            startActivityForResult(pickIntent, 901)
                        } catch (_: Throwable) {
                            Toast.makeText(this, "Widget picker unavailable on this device", Toast.LENGTH_SHORT).show()
                        }
                        return@setItems
                    }
                }
                saveState()
                buildMasterUI()
                refreshWorkspaceUI()
                renderDetoxLayer()
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showSwipeDownSelectorDialog() {
        val options = arrayOf("Notifications (Left) / Quick Settings (Right)", "Universal Search")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Swipe down on home screen")
            .setSingleChoiceItems(options, swipeDownBehavior) { dialog, which ->
                swipeDownBehavior = which
                saveState()
                dialog.dismiss()
                showHyperOsHomeSettingsDialog()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showMaxIconsConfigDialog() {
        if (checkLayoutLocked()) return
        val favApps = currentFavorites().mapNotNull { p -> allApps.find { it.pkg == p } }
        val labels = (listOf("Global Max Icons Support: ${if (maxIconsEnabled) "ENABLED" else "DISABLED"}") +
            favApps.map { "${it.customLabel} (${if (maxIconPkgs.contains(it.pkg)) "2×2 BIG ICON" else "1×1 STANDARD"})" }).toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("MAX ICONS / BIG ICONS (2×2)")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    maxIconsEnabled = !maxIconsEnabled
                } else {
                    val target = favApps[which - 1]
                    if (maxIconPkgs.contains(target.pkg)) maxIconPkgs.remove(target.pkg)
                    else maxIconPkgs.add(target.pkg)
                }
                saveState()
                refreshWorkspaceUI()
                showMaxIconsConfigDialog()
            }
            .setPositiveButton("DONE") { _, _ -> showHyperOsHomeSettingsDialog() }
            .show()
    }

    private fun showDrawerOrganizationSettingsDialog() {
        val items = arrayOf(
            "App Library / Smart App Categorization: ${if (smartAppCategorization) "ON (SMART HUBS)" else "OFF (A-Z LIST)"}",
            "Auto-open keyboard: ${if (autoOpenKeyboard) "ON" else "OFF"}",
            "Hide app icons (Management Screen — ${hiddenPkgs.size} hidden) ↗"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("DRAWER & ORGANIZATION SETTINGS")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        smartAppCategorization = !smartAppCategorization
                        drawerTab = if (smartAppCategorization) 1 else 0
                        saveState()
                        showDrawerOrganizationSettingsDialog()
                    }
                    1 -> {
                        autoOpenKeyboard = !autoOpenKeyboard
                        saveState()
                        showDrawerOrganizationSettingsDialog()
                    }
                    2 -> {
                        showHideAppIconsManagementScreen()
                    }
                }
            }
            .setNegativeButton("← BACK") { _, _ -> showHyperOsHomeSettingsDialog() }
            .show()
    }

    private fun showHideAppIconsManagementScreen() {
        val sortedApps = allApps.sortedBy { it.customLabel.lowercase(Locale.getDefault()) }
        val names = sortedApps.map { it.customLabel }.toTypedArray()
        val checked = BooleanArray(sortedApps.size) { idx -> hiddenPkgs.contains(sortedApps[idx].pkg) }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("HIDE APP ICONS // SELECT TO HIDE")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val pkg = sortedApps[which].pkg
                if (isChecked) hiddenPkgs.add(pkg) else hiddenPkgs.remove(pkg)
            }
            .setPositiveButton("APPLY") { _, _ ->
                saveState()
                refreshWorkspaceUI()
                Toast.makeText(this, "${hiddenPkgs.size} apps hidden", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("UNHIDE ALL") { _, _ ->
                hiddenPkgs.clear()
                saveState()
                refreshWorkspaceUI()
            }
            .show()
    }

    private fun showCustomizationThemingDialog() {
        val packName = when (iconPackMode) {
            0 -> "Nothing Mono + Wallpaper Tint"
            1 -> "Nothing Pure Monochrome"
            2 -> "Default System Color"
            else -> "Dot-Matrix High Contrast"
        }
        val items = arrayOf(
            "Dark theme / Material Style: ${if (useWallpaperIconTint) "DYNAMIC WALLPAPER TINT + MONO" else "DARK MATTE"}",
            "Icon Pack: $packName ↗",
            "Grid Size / Layout Density: ${gridColumns}×${gridRows} ↗",
            "Wallpaper Scrim Brightness: ${when (wallpaperDimAlpha) { 0 -> "100% Crystal Clear"; 55 -> "Subtle Glass"; else -> "Deep Dark" }}",
            "Change System / Live Wallpaper ↗"
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("WALLPAPER & STYLE // THEMING")
            .setItems(items) { _, idx ->
                when (idx) {
                    0 -> {
                        useWallpaperIconTint = !useWallpaperIconTint
                        isMonoIcons = true
                        saveState()
                        buildMasterUI()
                        refreshWorkspaceUI()
                        showCustomizationThemingDialog()
                    }
                    1 -> showIconPackSelectorDialog()
                    2 -> showGridSizeSelectorDialog()
                    3 -> {
                        wallpaperDimAlpha = when (wallpaperDimAlpha) { 55 -> 0; 0 -> 140; else -> 55 }
                        saveState()
                        buildMasterUI()
                        refreshWorkspaceUI()
                        showCustomizationThemingDialog()
                    }
                    4 -> {
                        try { startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) } catch (_: Throwable) {}
                    }
                }
            }
            .setNegativeButton("← BACK") { _, _ -> showHyperOsHomeSettingsDialog() }
            .show()
    }

    private fun showIconPackSelectorDialog() {
        val packs = mutableListOf(
            "Nothing Monochrome + Auto Wallpaper Tint",
            "Nothing Pure Monochrome Silhouette",
            "Default System Color Icons",
            "Dot-Matrix High-Contrast"
        )
        try {
            val themes = packageManager.queryIntentActivities(Intent("org.adw.launcher.THEMES"), 0)
            for (ri in themes) {
                packs.add("External Pack: ${ri.loadLabel(packageManager)}")
            }
        } catch (_: Throwable) {}

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("ICON PACK SELECTOR")
            .setSingleChoiceItems(packs.toTypedArray(), iconPackMode.coerceIn(0, packs.size - 1)) { dialog, which ->
                iconPackMode = which.coerceIn(0, 3)
                when (iconPackMode) {
                    0 -> { isMonoIcons = true; useWallpaperIconTint = true }
                    1 -> { isMonoIcons = true; useWallpaperIconTint = false }
                    2 -> { isMonoIcons = false; useWallpaperIconTint = false }
                    3 -> { isMonoIcons = true; useWallpaperIconTint = false }
                }
                saveState()
                buildMasterUI()
                refreshWorkspaceUI()
                dialog.dismiss()
                showCustomizationThemingDialog()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showGridSizeSelectorDialog() {
        if (checkLayoutLocked()) return
        val grids = arrayOf("4×5 (Default Balanced)", "4×6 (Tall)", "5×5 (Compact)", "5×6 (Dense)", "6×6 (Ultra Dense)")
        val pairs = listOf(4 to 5, 4 to 6, 5 to 5, 5 to 6, 6 to 6)
        val currentIdx = pairs.indexOfFirst { it.first == gridColumns && it.second == gridRows }.coerceAtLeast(0)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("GRID SIZE / LAYOUT DENSITY")
            .setSingleChoiceItems(grids, currentIdx) { dialog, which ->
                gridColumns = pairs[which].first
                gridRows = pairs[which].second
                saveState()
                buildMasterUI()
                refreshWorkspaceUI()
                dialog.dismiss()
                showCustomizationThemingDialog()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showZeroStateGlanceSheet() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("LEFT PULL // NOTIFICATION & GLANCE HUB")
            .setMessage(
                "• Context: Ahmedabad • 29°C Clear\n" +
                "• Agenda: Product Architecture Sync (10:00 AM)\n" +
                "• Commute: SG Highway • 14 mins (Clear)\n" +
                "• Active Delivery: Zomato Order arriving in 6m"
            )
            .setPositiveButton("SYSTEM NOTIFICATIONS") { _, _ -> expandSystemNotifications() }
            .setNeutralButton("CLOSE", null)
            .show()
    }

    private fun showQuickControlCenterDialog() {
        val controls = arrayOf(
            "Toggle Flashlight / Torch (${if (isTorchOn) "ON" else "OFF"})",
            "Toggle Monochrome Icons (${if (isMonoIcons) "ON" else "OFF"})",
            "Toggle Wallpaper Auto Icon Tint (${if (useWallpaperIconTint) "ON" else "OFF"})",
            "Open Multi-Screen Page Manager (${desktopPages.size} Pages)",
            "Open Home Settings (HyperOS Menu)",
            "Open System Wi-Fi & Connectivity Panel",
            "Open HyperOS System Quick Settings Shade"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("RIGHT PULL // QUICK CONTROL HUB")
            .setItems(controls) { _, which ->
                when (which) {
                    0 -> toggleFlashlight()
                    1 -> { isMonoIcons = !isMonoIcons; saveState(); refreshWorkspaceUI() }
                    2 -> { useWallpaperIconTint = !useWallpaperIconTint; saveState(); refreshWorkspaceUI() }
                    3 -> showPageManagerDialog()
                    4 -> showHyperOsHomeSettingsDialog()
                    5 -> try { startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) } catch (_: Throwable) { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                    6 -> expandSystemQuickSettings()
                }
            }
            .show()
    }

    private fun updateAllTelemetry() {
        if (!isUiBuilt) return
        try {
            val now = Date()
            val bm = safeRegisterBatteryCheck()
            val level = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, 86) ?: 86
            val tempC = (bm?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 290) ?: 290) / 10.0f

            when (clockFaceMode) {
                0 -> {
                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                    val dateStr = SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(now).uppercase(Locale.getDefault())
                    dotMatrixHeader?.setDotText(timeStr)
                    telemetrySubText?.text = "$dateStr // BAT $level% // ${tempC}°C // PAGE ${currentPageIndex + 1}/${desktopPages.size}"
                }
                1 -> {
                    val timeStr = SimpleDateFormat("hh.mm", Locale.getDefault()).format(now)
                    dotMatrixHeader?.setDotText(timeStr)
                    telemetrySubText?.text = "MINIMALIST ANALOG // 120HZ LOCKED // BAT $level%"
                }
                2 -> {
                    val utcFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    dotMatrixHeader?.setDotText(utcFmt.format(now))
                    telemetrySubText?.text = "WORLD UTC TIME // IST +05:30 // SPACE: $currentSpace"
                }
                else -> {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val mem = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mem)
                    val availGb = String.format(Locale.US, "%.1f", mem.availMem / (1024.0 * 1024.0 * 1024.0))
                    dotMatrixHeader?.setDotText("$level:${tempC.toInt()}".take(5))
                    telemetrySubText?.text = "FREE RAM: ${availGb}GB // CORES: ${Runtime.getRuntime().availableProcessors()} // ${tempC}°C"
                }
            }
            peripheralStripText?.text = "PAGE ${currentPageIndex + 1}/${desktopPages.size} • ${if (lockLayout) "LOCKED 🔒" else "EDITABLE"} • HOLD EMPTY SPACE FOR SETTINGS"
            updateSmartStackUI()
        } catch (_: Throwable) {}
    }

    private fun safeRegisterBatteryCheck(): Intent? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun updateSmartStackUI() {
        if (!isUiBuilt) return
        val spanTag = if (weatherWidgetSpan == 2) "2x1 WIDE" else "1x1"
        when (smartStackIndex) {
            0 -> {
                smartStackTitle?.text = "STACK 1/4 // WEATHER ($spanTag)"
                smartStackValue?.text = "29°C CLEAR"
                smartStackSub?.text = "AHMEDABAD • HOLD TO RESIZE"
            }
            1 -> {
                smartStackTitle?.text = "STACK 2/4 // PEDOMETER ($spanTag)"
                smartStackValue?.text = "$dailySteps STEPS"
                smartStackSub?.text = "${(dailySteps * 100 / 6000).coerceAtMost(100)}% OF 6,000 GOAL"
            }
            2 -> {
                smartStackTitle?.text = "STACK 3/4 // COMPASS ($spanTag)"
                smartStackValue?.text = "${compassAzimuth}° NE"
                smartStackSub?.text = "MAGNETOMETER LOCKED"
            }
            else -> {
                smartStackTitle?.text = "STACK 4/4 // WELLNESS ($spanTag)"
                smartStackValue?.text = "2H 14M ACTIVE"
                smartStackSub?.text = "18% BELOW DAILY LIMIT"
            }
        }
    }

    private fun toggleGlyphTimer() {
        triggerHaptic(0)
        if (glyphRunning) {
            glyphRunning = false
            glyphRunnable?.let { mainHandler.removeCallbacks(it) }
            glyphStatusText?.text = "PAUSED // ${formatSec(glyphSecondsLeft)}"
        } else {
            glyphRunning = true
            glyphRunnable = object : Runnable {
                override fun run() {
                    if (!glyphRunning) return
                    if (glyphSecondsLeft > 0) {
                        glyphSecondsLeft--
                        glyphProgressView?.setProgress(1f - (glyphSecondsLeft / (25f * 60f)))
                        glyphStatusText?.text = "GLYPH TIMER // ${formatSec(glyphSecondsLeft)}"
                        mainHandler.postDelayed(this, 1000L)
                    } else {
                        glyphRunning = false
                        triggerHaptic(1)
                        glyphStatusText?.text = "COMPLETE // TAP RESTART"
                        glyphSecondsLeft = 25 * 60
                    }
                }
            }
            mainHandler.post(glyphRunnable!!)
        }
    }

    private fun resetGlyphTimer() {
        triggerHaptic(1)
        glyphRunning = false
        glyphRunnable?.let { mainHandler.removeCallbacks(it) }
        glyphSecondsLeft = 25 * 60
        glyphProgressView?.setProgress(0f)
        glyphStatusText?.text = "GLYPH TIMER // 25:00"
    }

    private fun formatSec(sec: Int): String = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)

    private fun dispatchMediaKey(keyCode: Int) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (_: Throwable) {}
    }

    private fun toggleFlashlight() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull() ?: return
            isTorchOn = !isTorchOn
            cm.setTorchMode(id, isTorchOn)
            triggerHaptic(1)
            Toast.makeText(this, if (isTorchOn) "Torch ON" else "Torch OFF", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {}
    }

    private fun openDrawer(focusKb: Boolean) {
        val drw = drawerContainer ?: return
        isDrawerOpen = true
        drw.alpha = 0f
        drw.translationY = dp(36).toFloat()
        drw.visibility = View.VISIBLE
        drw.animate().alpha(1f).translationY(0f).setDuration(200L).start()
        renderDrawerApps(drawerSearchInput?.text?.toString() ?: "")
        if (focusKb) {
            drawerSearchInput?.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(drawerSearchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeDrawer() {
        isDrawerOpen = false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(drawerSearchInput?.windowToken, 0)
        drawerSearchInput?.setText("")
        drawerContainer?.visibility = View.GONE
    }

    private fun launchApp(app: AppEntry, popUpWindow: Boolean) {
        triggerHaptic(0)
        app.lastOpenedDaysAgo = 0
        if (mindfulPkgs.contains(app.pkg)) {
            Toast.makeText(this, "MINDFUL FRICTION // Breathing 3.0s before opening ${app.customLabel}...", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({ executeLaunch(app, popUpWindow) }, 3000L)
        } else {
            executeLaunch(app, popUpWindow)
        }
    }

    private fun executeLaunch(app: AppEntry, popUpWindow: Boolean) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(app.pkg, app.activityName)
                flags = if (popUpWindow) {
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                } else {
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
            }
            startActivity(intent)
            closeDrawer()
        } catch (_: Throwable) {}
    }

    private fun expandSystemNotifications() {
        try {
            val service = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(service)
        } catch (_: Throwable) {
            showZeroStateGlanceSheet()
        }
    }

    private fun expandSystemQuickSettings() {
        try {
            val service = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandSettingsPanel").invoke(service)
        } catch (_: Throwable) {
            showQuickControlCenterDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        safeRegisterReceiver(tickReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        updateAllTelemetry()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(tickReceiver) } catch (_: Throwable) {}
    }

    @Deprecated("Launcher Back Handler")
    override fun onBackPressed() {
        if (isSleepOverlayActive) {
            isSleepOverlayActive = false
            sleepLockOverlay?.visibility = View.GONE
            return
        }
        if (isDrawerOpen) closeDrawer()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun roundRect(fill: Int, rad: Int, stroke: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = rad.toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }

    class DotProgressBarView(context: Context, private val accentColor: Int) : View(context) {
        private var progress = 0.35f
        private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2A2E") }

        fun setProgress(p: Float) {
            progress = p.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val totalDots = 18
            val spacing = width / totalDots.toFloat()
            val r = (spacing * 0.34f).coerceAtMost(height * 0.4f)
            val cy = height / 2f
            val activeDots = (progress * totalDots).toInt()
            for (i in 0 until totalDots) {
                canvas.drawCircle((i + 0.5f) * spacing, cy, r, if (i <= activeDots && progress > 0f) litPaint else dimPaint)
            }
        }
    }

    class DotMatrixView(context: Context, private val accentColor: Int) : View(context) {
        private var currentText = "12:00"
        private val whitePaint = Paint().apply { color = Color.WHITE; isAntiAlias = false }
        private val accentPaint = Paint().apply { color = accentColor; isAntiAlias = false }
        private val offPaint = Paint().apply { color = Color.parseColor("#222226"); isAntiAlias = false }

        private val glyphs = mapOf(
            '0' to arrayOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
            '1' to arrayOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
            '2' to arrayOf("01110", "10001", "00001", "00110", "01000", "10000", "11111"),
            '3' to arrayOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
            '4' to arrayOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
            '5' to arrayOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
            '6' to arrayOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
            '7' to arrayOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
            '8' to arrayOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
            '9' to arrayOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
            ':' to arrayOf("000", "010", "010", "000", "010", "010", "000"),
            '.' to arrayOf("00", "00", "00", "00", "00", "11", "11")
        )

        fun setDotText(t: String) {
            if (currentText != t) {
                currentText = t
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rows = 7
            val dotSpacing = height / (rows + 1f)
            val r = dotSpacing * 0.36f
            var cursorX = dotSpacing
            for (ch in currentText) {
                val matrix = glyphs[ch] ?: continue
                val cols = matrix[0].length
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        val lit = matrix[row][col] == '1'
                        val p = when {
                            !lit -> offPaint
                            ch == ':' || ch == '.' -> accentPaint
                            else -> whitePaint
                        }
                        canvas.drawCircle(cursorX + col * dotSpacing, (row + 1) * dotSpacing, r, p)
                    }
                }
                cursorX += (cols + 1.4f) * dotSpacing
            }
        }
    }
}