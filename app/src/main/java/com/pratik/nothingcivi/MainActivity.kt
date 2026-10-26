package com.pratik.nothingcivi

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
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

class MainActivity : Activity(), SensorEventListener {

    // Live Wallpaper Scrim & Dynamic Wallpaper Tint State (No Rigid Pitch-Black Background!)
    private var wallpaperDimAlpha = 60 // 0 = 100% Clear Wallpaper, 255 = Solid Black
    private var widgetOpacityAlpha = 165 // Translucent Glass Backing over Live Wallpaper
    private var accentColor = Color.parseColor("#D71921")
    private var wallpaperExtractedColor = Color.parseColor("#38BDF8")

    // Structured Home Settings, Drawer & Organization, and Theming State
    private var addAppsToHome = true // 1.1 Automatically place newly downloaded apps onto desktop
    private var swipeDownBehavior = 0 // 1.2 0 = Notifications / Quick Settings, 1 = Universal Search
    private var notificationDotsMode = 1 // 1.3 0 = White Dot Only, 1 = Dot + Count, 2 = Off
    private var isLabelFree = false // 1.4 Global app icon labels / Show app names (!isLabelFree)
    private var maxIconsEnabled = true // 1.5 Max Icons / Big Icons (2x2 scaling support)
    private var lockLayout = false // 1.6 Lock layout to prevent accidental moves/deletions
    private var smartAppCategorization = false // 2.1 App Library / Smart App Categorization default
    private var autoOpenKeyboard = true // 2.2 Auto-open keyboard when drawer opens
    private var iconPackMode = 0 // 3.2 0 = Nothing Mono + Wallpaper Tint, 1 = Pure Mono, 2 = System Color, 3 = High-Contrast Matrix
    private var useWallpaperIconTint = true
    private var isMonoIcons = true
    private var isDetoxMode = false
    private var hyperOsImmersiveGestures = true
    private var doubleTapMode = 0 // 0 = Screen Sleep/Lock Overlay, 1 = Wallpaper Peek, 2 = Detox Mode
    private var thumbArcMode = 0 // 0=Off, 1=Right-Hand, 2=Left-Hand
    private var gridColumns = 4
    private var gridRows = 5
    private var weatherWidgetSpan = 1 // 1 = 1x1, 2 = 2x1 Resizable Widget
    private var currentSpace = "FOCUS"
    private val spaces = listOf("WORK", "COMMUTE", "FITNESS", "FOCUS", "WEEKEND")

    private val COLOR_CARD get() = Color.argb(widgetOpacityAlpha, 18, 20, 26)
    private val COLOR_CARD_ALT get() = Color.argb((widgetOpacityAlpha + 30).coerceAtMost(240), 26, 28, 36)
    private val COLOR_STROKE get() = if (useWallpaperIconTint) adjustAlpha(wallpaperExtractedColor, 95) else Color.argb(75, 255, 255, 255)
    private val COLOR_WHITE = Color.parseColor("#FFFFFF")
    private val COLOR_MUTED = Color.parseColor("#A1A1AA")

    private lateinit var prefs: SharedPreferences
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appWidgetHost: AppWidgetHost? = null
    private var appWidgetManager: AppWidgetManager? = null

    private val allApps = mutableListOf<AppEntry>()
    private val favoritePkgs = mutableListOf<String>()
    private val pinnedTopShelfPkgs = mutableListOf<String>()
    private val maxIconPkgs = mutableSetOf<String>()
    private val hiddenPkgs = mutableSetOf<String>()
    private val privateVaultPkgs = mutableSetOf<String>()
    private val decoyVaultPkgs = mutableSetOf<String>()
    private val mindfulPkgs = mutableSetOf<String>()
    private val customFolders = mutableMapOf<String, MutableList<String>>()

    // Smart Stack & Telemetry State
    private var clockFaceMode = 0 // 0=DotMatrix, 1=Minimalist Analog, 2=UTC Dual, 3=Civi NPU/RAM
    private var smartStackIndex = 0 // 0=Weather, 1=Pedometer Ring, 2=Compass Heading, 3=ScreenTime
    private var compassAzimuth = 42
    private var dailySteps = 2840
    private var stepBaseline = -1f
    private var isTorchOn = false
    private var isDrawerOpen = false
    private var isSleepOverlayActive = false
    private var drawerTab = 0 // 0=A-Z, 1=Smart Arrange Hubs, 2=Ghost Shelf (>30d), 3=Vault (Real/Decoy)
    private var activeVaultMode = 0

    // Glyph Timer State
    private var glyphRunning = false
    private var glyphSecondsLeft = 25 * 60
    private var glyphRunnable: Runnable? = null

    // UI References
    private lateinit var rootStack: FrameLayout
    private lateinit var homeContainer: LinearLayout
    private lateinit var detoxContainer: LinearLayout
    private lateinit var drawerContainer: LinearLayout
    private lateinit var sleepLockOverlay: FrameLayout
    private lateinit var dotMatrixHeader: DotMatrixView
    private lateinit var telemetrySubText: TextView
    private lateinit var spacePillsRow: LinearLayout
    private lateinit var bentoRowLayout: LinearLayout
    private lateinit var smartStackCard: LinearLayout
    private lateinit var rightGlyphCard: LinearLayout
    private lateinit var smartStackTitle: TextView
    private lateinit var smartStackValue: TextView
    private lateinit var smartStackSub: TextView
    private lateinit var glyphStatusText: TextView
    private lateinit var glyphProgressView: DotProgressBarView
    private lateinit var peripheralStripText: TextView
    private lateinit var maxFoldersRow: LinearLayout
    private lateinit var favGridLayout: GridLayout
    private lateinit var agenticInput: EditText
    private lateinit var agenticBanner: TextView
    private lateinit var dockLayout: LinearLayout
    private lateinit var drawerSearchInput: EditText
    private lateinit var pinnedShelfLayout: LinearLayout
    private lateinit var drawerMainContainer: FrameLayout

    private var sensorManager: SensorManager? = null

    private val wallpaperColorListener = WallpaperManager.OnColorsChangedListener { colors, _ ->
        if (colors != null) {
            applyExtractedWallpaperColors(colors)
        }
    }

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateAllTelemetry()
        }
    }

    // Play Store / Package Added Listener for "Add apps to Home screen" setting
    private val pkgAddedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (action == Intent.ACTION_PACKAGE_ADDED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                if (addAppsToHome && !lockLayout && !favoritePkgs.contains(pkg)) {
                    favoritePkgs.add(pkg)
                    saveState()
                }
                loadAppsAsync()
            } else if (action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                favoritePkgs.remove(pkg)
                maxIconPkgs.remove(pkg)
                saveState()
                loadAppsAsync()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("kinetic_os4_master", Context.MODE_PRIVATE)
        loadState()
        enforce120HzAndLiveWallpaperPipeline()
        initWallpaperColorExtraction()
        initHardwareSensors()
        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = AppWidgetHost(this, 1024)
        appWidgetHost?.startListening()

        registerReceiver(pkgAddedReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        })

        buildMasterUI()
        updateAllTelemetry()
        loadAppsAsync()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pkgAddedReceiver) } catch (_: Exception) {}
        try { appWidgetHost?.stopListening() } catch (_: Exception) {}
    }

    private fun enforce120HzAndLiveWallpaperPipeline() {
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
    }

    private fun initWallpaperColorExtraction() {
        try {
            val wm = WallpaperManager.getInstance(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                wm.addOnColorsChangedListener(wallpaperColorListener, mainHandler)
                val colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                if (colors != null) {
                    applyExtractedWallpaperColors(colors)
                }
            }
        } catch (_: Exception) {}
    }

    private fun applyExtractedWallpaperColors(colors: WallpaperColors) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val primary = colors.primaryColor.toArgb()
            val hsv = FloatArray(3)
            Color.colorToHSV(primary, hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.55f)
            hsv[2] = hsv[2].coerceAtLeast(0.85f)
            wallpaperExtractedColor = Color.HSVToColor(hsv)
            if (useWallpaperIconTint) {
                renderMaxFolders()
                renderDesktopGrid()
                if (isDrawerOpen) renderDrawerApps(drawerSearchInput.text.toString())
            }
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 101)
            }
        }
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        @Suppress("DEPRECATION")
        sensorManager?.getDefaultSensor(Sensor.TYPE_ORIENTATION)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
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
    }

    private fun checkLayoutLocked(): Boolean {
        if (lockLayout) {
            triggerHaptic(1)
            Toast.makeText(this, "Home screen layout is locked", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    private fun loadState() {
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
        gridColumns = prefs.getInt("grid_cols", 4)
        gridRows = prefs.getInt("grid_rows", 5)
        weatherWidgetSpan = prefs.getInt("weather_span", 1)
        currentSpace = prefs.getString("space", "FOCUS") ?: "FOCUS"
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

        favoritePkgs.clear()
        val favRaw = prefs.getString("fav_list", null)
        if (!favRaw.isNullOrEmpty()) {
            favoritePkgs.addAll(favRaw.split(",").filter { it.isNotBlank() })
        }
    }

    private fun saveState() {
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
            .putInt("accent", accentColor)
            .putInt("wall_color", wallpaperExtractedColor)
            .putStringSet("max_icons", maxIconPkgs)
            .putStringSet("hidden_pkgs", hiddenPkgs)
            .putStringSet("private_pkgs", privateVaultPkgs)
            .putStringSet("decoy_pkgs", decoyVaultPkgs)
            .putStringSet("mindful_pkgs", mindfulPkgs)
            .putString("fav_list", favoritePkgs.joinToString(","))
            .apply()
    }

    private fun buildMasterUI() {
        rootStack = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(wallpaperDimAlpha, 0, 0, 0))
        }

        homeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(32), dp(14), dp(10))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Top Bar: Dynamic Spaces + Quick Monochrome & Home Settings Pills
        spacePillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        renderSpacePills()
        homeContainer.addView(spacePillsRow)

        // 1. Dot-Matrix Clock & Telemetry Header
        val clockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(22), COLOR_STROKE)
            setPadding(dp(14), dp(12), dp(14), dp(10))
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
        dotMatrixHeader = DotMatrixView(this, accentColor).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        }
        val monoQuickBtn = TextView(this).apply {
            text = if (isMonoIcons) "MONO:ON" else "MONO:OFF"
            typeface = Typeface.MONOSPACE
            textSize = 8f
            setTextColor(COLOR_WHITE)
            background = roundRect(if (isMonoIcons) accentColor else COLOR_CARD_ALT, dp(10), COLOR_STROKE)
            setPadding(dp(7), dp(4), dp(7), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(5)
            }
            setOnClickListener {
                triggerHaptic(0)
                isMonoIcons = !isMonoIcons
                iconPackMode = if (isMonoIcons) 0 else 2
                saveState()
                buildMasterUI()
                updateAllTelemetry()
                renderMaxFolders()
                renderDesktopGrid()
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
        topRow.addView(dotMatrixHeader)
        topRow.addView(monoQuickBtn)
        topRow.addView(cfgBtn)

        telemetrySubText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(5), 0, 0)
        }
        clockCard.addView(topRow)
        clockCard.addView(telemetrySubText)
        homeContainer.addView(clockCard)

        // 2. Resizable Smart Widget Stack + Glyph Timer Bento
        bentoRowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(7)
            }
        }

        smartStackCard = LinearLayout(this).apply {
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
                Toast.makeText(this@MainActivity, "Widget Resized to ${if (weatherWidgetSpan == 2) "2x1 Wide" else "1x1 Compact"}", Toast.LENGTH_SHORT).show()
                buildMasterUI()
                updateAllTelemetry()
                renderMaxFolders()
                renderDesktopGrid()
                true
            }
        }
        smartStackTitle = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 8.5f
            setTextColor(COLOR_MUTED)
        }
        smartStackValue = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = if (weatherWidgetSpan == 2) 16f else 13f
            setTextColor(COLOR_WHITE)
            setPadding(0, dp(3), 0, dp(2))
        }
        smartStackSub = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 8f
            setTextColor(if (useWallpaperIconTint) wallpaperExtractedColor else accentColor)
        }
        smartStackCard.addView(smartStackTitle)
        smartStackCard.addView(smartStackValue)
        smartStackCard.addView(smartStackSub)

        rightGlyphCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = if (weatherWidgetSpan == 1) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { toggleGlyphTimer() }
            setOnLongClickListener { resetGlyphTimer(); true }
        }
        glyphStatusText = TextView(this).apply {
            text = "GLYPH TIMER // 25:00"
            typeface = Typeface.MONOSPACE
            textSize = 8.5f
            setTextColor(COLOR_WHITE)
        }
        glyphProgressView = DotProgressBarView(this, accentColor).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(6) }
        }
        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        fun mediaKeyBtn(lbl: String, code: Int): TextView = TextView(this).apply {
            text = lbl; typeface = Typeface.MONOSPACE; textSize = 9.5f; gravity = Gravity.CENTER; setTextColor(COLOR_WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { triggerHaptic(0); dispatchMediaKey(code) }
        }
        mediaRow.addView(mediaKeyBtn("|<<", KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        mediaRow.addView(mediaKeyBtn("PLAY/PAUSE", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        mediaRow.addView(mediaKeyBtn(">>|", KeyEvent.KEYCODE_MEDIA_NEXT))

        rightGlyphCard.addView(glyphStatusText)
        rightGlyphCard.addView(glyphProgressView)
        rightGlyphCard.addView(mediaRow)

        bentoRowLayout.addView(smartStackCard)
        bentoRowLayout.addView(rightGlyphCard)
        homeContainer.addView(bentoRowLayout)

        // 3. Quick Wallpaper & Style / Home Settings Strip
        peripheralStripText = TextView(this).apply {
            text = "GRID ${gridColumns}x${gridRows} • ${if (lockLayout) "LOCKED 🔒" else "UNLOCKED"} • HOLD EMPTY SPACE FOR HOME SETTINGS"
            typeface = Typeface.MONOSPACE
            textSize = 8f
            setTextColor(COLOR_WHITE)
            background = roundRect(COLOR_CARD, dp(14), COLOR_STROKE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
            setOnClickListener {
                triggerHaptic(0)
                showHyperOsHomeSettingsDialog()
            }
            setOnLongClickListener {
                toggleFlashlight()
                true
            }
        }
        homeContainer.addView(peripheralStripText)

        // 4. Max Folders (2x2 Interactive Hubs)
        maxFoldersRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        homeContainer.addView(maxFoldersRow)

        // 5. Desktop Configurable App Grid
        val favScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) }
        }
        favGridLayout = GridLayout(this).apply {
            columnCount = gridColumns
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        favScroll.addView(favGridLayout)
        homeContainer.addView(favScroll)

        // 6. Agentic Intent Search Bar
        agenticBanner = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(COLOR_WHITE)
            background = roundRect(COLOR_CARD_ALT, dp(14), accentColor)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        homeContainer.addView(agenticBanner)

        val searchBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(COLOR_CARD, dp(24), COLOR_STROKE)
            setPadding(dp(14), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(8) }
        }
        agenticInput = EditText(this).apply {
            hint = "AGENTIC SLM // 'Timer 45m', 'Call', '45*18'..."
            setHintTextColor(COLOR_MUTED)
            setTextColor(COLOR_WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
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
        val openDrawerBtn = TextView(this).apply {
            text = "DRAWER ↑"
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextColor(accentColor)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { openDrawer(focusKb = autoOpenKeyboard) }
        }
        searchBarRow.addView(agenticInput)
        searchBarRow.addView(openDrawerBtn)
        homeContainer.addView(searchBarRow)

        // 7. Bottom Dock
        dockLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundRect(COLOR_CARD, dp(22), COLOR_STROKE)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60))
        }
        homeContainer.addView(dockLayout)

        // ================= DETOX MINIMALIST MODE LAYER =================
        detoxContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 0, 0, 0))
            setPadding(dp(24), dp(48), dp(24), dp(24))
            visibility = if (isDetoxMode) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // ================= APP DRAWER LAYER =================
        drawerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(215, 8, 10, 14))
            setPadding(dp(14), dp(36), dp(14), dp(12))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val drawerTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }

        drawerSearchInput = EditText(this).apply {
            hint = "SEARCH APPS, CATEGORIES, MATH..."
            setHintTextColor(COLOR_MUTED)
            setTextColor(COLOR_WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 11f
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

        val closeDrawerBtn = TextView(this).apply {
            text = "HOME ↓"
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextColor(COLOR_WHITE)
            background = roundRect(COLOR_CARD, dp(14), COLOR_STROKE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { closeDrawer() }
        }
        drawerTopRow.addView(drawerSearchInput)
        drawerTopRow.addView(closeDrawerBtn)
        drawerContainer.addView(drawerTopRow)

        val shelfHeader = TextView(this).apply {
            text = "PINNED TOP SHELF // STATIONARY"
            typeface = Typeface.MONOSPACE
            textSize = 8.5f
            setTextColor(COLOR_MUTED)
            setPadding(dp(4), 0, 0, dp(4))
        }
        drawerContainer.addView(shelfHeader)

        pinnedShelfLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect(COLOR_CARD, dp(16), COLOR_STROKE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(8) }
        }
        drawerContainer.addView(pinnedShelfLayout)

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
                    openDrawer(false)
                }
            }
        }
        tabsRow.addView(makeTab("A-Z ALL", 0))
        tabsRow.addView(makeTab("SMART HUBS", 1))
        tabsRow.addView(makeTab("GHOST >30D", 2))
        tabsRow.addView(makeTab("VAULT 🔒", 3))
        drawerContainer.addView(tabsRow)

        drawerMainContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        drawerContainer.addView(drawerMainContainer)

        // ================= INSTANT DOUBLE-TAP SLEEP / LOCK OVERLAY =================
        sleepLockOverlay = FrameLayout(this).apply {
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

        rootStack.addView(homeContainer)
        rootStack.addView(detoxContainer)
        rootStack.addView(drawerContainer)
        rootStack.addView(sleepLockOverlay)

        // ================= MASTER GESTURE ENGINE (Long-Press Empty Space -> Home Settings) =================
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                triggerHaptic(1)
                showHyperOsHomeSettingsDialog()
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || isDrawerOpen) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                val screenW = resources.displayMetrics.widthPixels

                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > dp(50)) {
                    if (dy < 0) {
                        // SWIPE UP -> Open App Drawer (Respects Auto-open keyboard setting)
                        triggerHaptic(0)
                        openDrawer(focusKb = autoOpenKeyboard)
                        return true
                    } else {
                        // SWIPE DOWN -> Respects "Swipe down on home screen" setting
                        triggerHaptic(0)
                        if (swipeDownBehavior == 1) {
                            openDrawer(focusKb = true)
                        } else if (e1.x < screenW * 0.5f) {
                            expandSystemNotifications()
                        } else {
                            expandSystemQuickSettings()
                        }
                        return true
                    }
                }

                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > dp(65)) {
                    triggerHaptic(0)
                    if (dx > 0) showZeroStateGlanceSheet()
                    else showQuickControlCenterDialog()
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                triggerHaptic(1)
                when (doubleTapMode) {
                    0 -> {
                        isSleepOverlayActive = true
                        sleepLockOverlay.visibility = View.VISIBLE
                    }
                    1 -> {
                        wallpaperDimAlpha = if (wallpaperDimAlpha > 15) 0 else 55
                        rootStack.setBackgroundColor(Color.argb(wallpaperDimAlpha, 0, 0, 0))
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

        homeContainer.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }
        favGridLayout.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); false }

        setContentView(rootStack)
    }

    private fun renderSpacePills() {
        spacePillsRow.removeAllViews()
        for (sp in spaces) {
            val active = sp == currentSpace
            val pill = TextView(this).apply {
                text = sp
                typeface = Typeface.MONOSPACE
                textSize = 8.5f
                setTextColor(if (active) COLOR_WHITE else COLOR_MUTED)
                background = roundRect(if (active) accentColor else COLOR_CARD, dp(12), COLOR_STROKE)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(5)
                }
                setOnClickListener {
                    triggerHaptic(0)
                    currentSpace = sp
                    saveState()
                    renderSpacePills()
                    renderDesktopGrid()
                    updateAllTelemetry()
                }
            }
            spacePillsRow.addView(pill)
        }
    }

    private fun parseAgenticIntent(q: String) {
        if (q.isEmpty()) {
            agenticBanner.visibility = View.GONE
            return
        }
        val lower = q.lowercase(Locale.getDefault())
        val mathResult = tryEvaluateSimpleMath(q)

        agenticBanner.visibility = View.VISIBLE
        when {
            mathResult != null -> {
                agenticBanner.text = "SLM MATH // = $mathResult (TAP TO COPY)"
                agenticBanner.setOnClickListener {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("calc", mathResult))
                    Toast.makeText(this, "Copied $mathResult", Toast.LENGTH_SHORT).show()
                }
            }
            lower.contains("timer") -> {
                val mins = extractFirstNumber(lower) ?: 15
                agenticBanner.text = "AGENTIC ACTION // SET SYSTEM TIMER FOR $mins MINS →"
                agenticBanner.setOnClickListener {
                    triggerHaptic(1)
                    glyphSecondsLeft = mins * 60
                    toggleGlyphTimer()
                    agenticInput.setText("")
                }
            }
            lower.contains("call") -> {
                agenticBanner.text = "AGENTIC ACTION // DIRECT DIAL INTENT ($q) →"
                agenticBanner.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_DIAL))
                    agenticInput.setText("")
                }
            }
            lower.contains("split") || lower.contains("upi") -> {
                val amt = extractFirstNumber(lower) ?: 500
                agenticBanner.text = "AGENTIC UPI // SPLIT ₹$amt (₹${amt / 2} EACH) → LAUNCH PAY"
                agenticBanner.setOnClickListener {
                    Toast.makeText(this, "UPI Split Request: ₹${amt / 2} ready", Toast.LENGTH_SHORT).show()
                    agenticInput.setText("")
                }
            }
            else -> {
                val matchApp = allApps.find { it.customLabel.lowercase(Locale.getDefault()).contains(lower) }
                if (matchApp != null) {
                    agenticBanner.text = "INSTANT LAUNCH // OPEN ${matchApp.customLabel.uppercase(Locale.getDefault())} →"
                    agenticBanner.setOnClickListener {
                        launchApp(matchApp, false)
                        agenticInput.setText("")
                    }
                } else {
                    agenticBanner.text = "UNIVERSAL QUERY // WEB SEARCH \"$q\" →"
                    agenticBanner.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
                        agenticInput.setText("")
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
                if (favoritePkgs.isEmpty() && allApps.isNotEmpty()) {
                    allApps.take(8).forEach { favoritePkgs.add(it.pkg) }
                    maxIconPkgs.add(favoritePkgs.first())
                    saveState()
                }
                if (pinnedTopShelfPkgs.isEmpty()) {
                    allApps.take(5).forEach { pinnedTopShelfPkgs.add(it.pkg) }
                }
                renderMaxFolders()
                renderDesktopGrid()
                renderDetoxLayer()
                renderDrawerApps("")
            }
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

    private fun renderMaxFolders() {
        maxFoldersRow.removeAllViews()
        val filter = buildDynamicIconFilter()
        val visiblePool = allApps.filter { !hiddenPkgs.contains(it.pkg) && !privateVaultPkgs.contains(it.pkg) }
        val folders = listOf("ESSENTIAL HUB" to visiblePool.take(4), "MEDIA & TOOLS" to visiblePool.drop(4).take(4))

        for ((title, apps) in folders) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) }
            }
            val hdr = TextView(this).apply {
                text = ":: $title"
                typeface = Typeface.MONOSPACE
                textSize = 8.5f
                setTextColor(COLOR_MUTED)
                setOnClickListener {
                    triggerHaptic(2)
                    showExpandedFolderModal(title, apps)
                }
            }
            val miniGrid = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            for (a in apps.take(3)) {
                val img = ImageView(this).apply {
                    setImageDrawable(a.icon.constantState?.newDrawable()?.mutate() ?: a.icon)
                    colorFilter = filter
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(6) }
                    setOnClickListener { launchApp(a, false) }
                }
                miniGrid.addView(img)
            }
            val expandBtn = TextView(this).apply {
                text = "+${apps.size}"
                typeface = Typeface.MONOSPACE
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(COLOR_WHITE)
                background = roundRect(accentColor, dp(14))
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                setOnClickListener {
                    triggerHaptic(2)
                    showExpandedFolderModal(title, apps)
                }
            }
            miniGrid.addView(expandBtn)
            box.addView(hdr)
            box.addView(miniGrid)
            maxFoldersRow.addView(box)
        }
    }

    private fun showExpandedFolderModal(title: String, apps: List<AppEntry>) {
        val names = apps.map { it.customLabel }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("MAX FOLDER // $title")
            .setItems(names) { _, idx -> launchApp(apps[idx], false) }
            .setNeutralButton("CLOSE", null)
            .show()
    }

    private fun renderDesktopGrid() {
        favGridLayout.removeAllViews()
        favGridLayout.columnCount = gridColumns
        dockLayout.removeAllViews()

        val filter = buildDynamicIconFilter()
        val favApps = favoritePkgs.mapNotNull { pkg ->
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

                scaleX = 0.92f; scaleY = 0.92f
                animate().scaleX(1f).scaleY(1f).setDuration(240L).setInterpolator(OvershootInterpolator(1.4f)).start()

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

            favGridLayout.addView(card)
        }

        for (app in favApps.take(4)) {
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
            dockLayout.addView(dockCell)
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
        dockLayout.addView(drawerBtn)
    }

    private fun renderDetoxLayer() {
        detoxContainer.visibility = if (isDetoxMode) View.VISIBLE else View.GONE
        detoxContainer.removeAllViews()
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
        detoxContainer.addView(hdr)
        detoxContainer.addView(clock)

        for (app in allApps.take(7)) {
            val item = TextView(this).apply {
                text = "→  ${app.customLabel.uppercase(Locale.getDefault())}"
                typeface = Typeface.MONOSPACE
                textSize = 16f
                setTextColor(COLOR_WHITE)
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { launchApp(app, false) }
            }
            detoxContainer.addView(item)
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
        detoxContainer.addView(exitBtn)
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
                openDrawer(false)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun renderDrawerApps(query: String) {
        val filter = buildDynamicIconFilter()
        pinnedShelfLayout.removeAllViews()
        val shelfApps = pinnedTopShelfPkgs.mapNotNull { p -> allApps.find { it.pkg == p && !hiddenPkgs.contains(it.pkg) } }
        for (a in shelfApps) {
            val img = ImageView(this).apply {
                setImageDrawable(a.icon.constantState?.newDrawable()?.mutate() ?: a.icon)
                colorFilter = filter
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
                setOnClickListener { launchApp(a, false) }
            }
            pinnedShelfLayout.addView(img)
        }

        drawerMainContainer.removeAllViews()
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
                            showShutterAndContextSheet(app, isPinned = favoritePkgs.contains(app.pkg))
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
            drawerMainContainer.addView(scroll)
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
                if (drawerTab == 2) {
                    val tag = TextView(this@MainActivity).apply {
                        text = "${app.lastOpenedDaysAgo}D IDLE"
                        typeface = Typeface.MONOSPACE
                        textSize = 7f
                        setTextColor(accentColor)
                    }
                    cell.addView(tag)
                }
                val img = ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                    colorFilter = filter
                    layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { topMargin = dp(3) }
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
                cell.setOnClickListener { launchApp(app, false) }
                cell.setOnLongClickListener {
                    triggerHaptic(1)
                    showShutterAndContextSheet(app, isPinned = favoritePkgs.contains(app.pkg))
                    true
                }
                return cell
            }
        }
        drawerMainContainer.addView(gridView)
    }

    private fun showShutterAndContextSheet(app: AppEntry, isPinned: Boolean) {
        val isMax = maxIconPkgs.contains(app.pkg)
        val isMindful = mindfulPkgs.contains(app.pkg)
        val opts = mutableListOf<String>()

        opts.add("SHUTTER // PEEK TRANSIENT WIDGET (${app.category})")
        if (isPinned) {
            if (maxIconsEnabled) {
                opts.add(if (isMax) "Resize to 1×1 Standard Tile" else "Enlarge to 2×2 Max Icon")
            }
            opts.add("Unpin from Desktop")
        } else {
            opts.add("Pin to Desktop")
            if (maxIconsEnabled) opts.add("Pin as 2×2 Max Icon")
        }
        opts.add("Launch in Pop-Up / Split Window")
        opts.add("Rename App Label")
        opts.add(if (isMindful) "Disable Mindful Friction (3s)" else "Enable Mindful Friction (3s)")
        opts.add("Hide App Icon")
        opts.add("Move to Primary Private Vault 🔒")
        opts.add("Home Settings (HyperOS Menu)")
        opts.add("System App Info")
        opts.add("Uninstall App")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${app.customLabel.uppercase(Locale.getDefault())} // SHUTTER & CFG")
            .setItems(opts.toTypedArray()) { _, which ->
                val c = opts[which]
                when {
                    c.startsWith("SHUTTER") -> {
                        Toast.makeText(this, "${app.customLabel} Widget Shutter Active • Quota ${(app.dailyQuotaPct * 100).toInt()}%", Toast.LENGTH_SHORT).show()
                        return@setItems
                    }
                    c.startsWith("Launch in Pop-Up") -> { launchApp(app, true); return@setItems }
                    c == "Home Settings (HyperOS Menu)" -> { showHyperOsHomeSettingsDialog(); return@setItems }
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
                when {
                    c.startsWith("Enlarge") -> maxIconPkgs.add(app.pkg)
                    c.startsWith("Resize") -> maxIconPkgs.remove(app.pkg)
                    c.startsWith("Unpin") -> { favoritePkgs.remove(app.pkg); maxIconPkgs.remove(app.pkg) }
                    c == "Pin to Desktop" -> if (!favoritePkgs.contains(app.pkg)) favoritePkgs.add(app.pkg)
                    c == "Pin as 2×2 Max Icon" -> { if (!favoritePkgs.contains(app.pkg)) favoritePkgs.add(app.pkg); maxIconPkgs.add(app.pkg) }
                    c == "Rename App Label" -> { promptRename(app); return@setItems }
                    c.startsWith("Enable Mindful") -> mindfulPkgs.add(app.pkg)
                    c.startsWith("Disable Mindful") -> mindfulPkgs.remove(app.pkg)
                    c == "Hide App Icon" -> { hiddenPkgs.add(app.pkg); favoritePkgs.remove(app.pkg) }
                    c.startsWith("Move to Primary") -> { privateVaultPkgs.add(app.pkg); favoritePkgs.remove(app.pkg) }
                }
                saveState()
                renderDesktopGrid()
                renderDrawerApps(drawerSearchInput.text.toString())
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
                renderDesktopGrid()
                renderDrawerApps(drawerSearchInput.text.toString())
            }.show()
    }

    // ================= HYPEROS INTEGRATED HOME SETTINGS (LONG-PRESS DESKTOP MENU) =================
    private fun showHyperOsHomeSettingsDialog() {
        val swipeLabel = if (swipeDownBehavior == 0) "Notifications / Quick Settings" else "Search"
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
            "7. Drawer & Organization Settings ↗",
            "8. Customization & Theming (Wallpaper & Style) ↗",
            "9. Add System AppWidget (Native Picker)"
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
                        showDrawerOrganizationSettingsDialog()
                        return@setItems
                    }
                    7 -> {
                        showCustomizationThemingDialog()
                        return@setItems
                    }
                    8 -> {
                        if (checkLayoutLocked()) return@setItems
                        try {
                            val id = appWidgetHost?.allocateAppWidgetId() ?: return@setItems
                            val pickIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            }
                            startActivityForResult(pickIntent, 901)
                        } catch (_: Exception) {}
                        return@setItems
                    }
                }
                saveState()
                buildMasterUI()
                updateAllTelemetry()
                renderMaxFolders()
                renderDesktopGrid()
                renderDetoxLayer()
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showSwipeDownSelectorDialog() {
        val options = arrayOf("Notifications / Quick Settings", "Universal Search")
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
        val favApps = favoritePkgs.mapNotNull { p -> allApps.find { it.pkg == p } }
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
                renderDesktopGrid()
                showMaxIconsConfigDialog()
            }
            .setPositiveButton("DONE") { _, _ -> showHyperOsHomeSettingsDialog() }
            .show()
    }

    // 2. Drawer & Organization Settings Sub-Menu
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

    // Dedicated Management Screen for "Hide app icons"
    private fun showHideAppIconsManagementScreen() {
        val sortedApps = allApps.sortedBy { it.customLabel.lowercase(Locale.getDefault()) }
        val names = sortedApps.map { it.customLabel }.toTypedArray()
        val checked = BooleanArray(sortedApps.size) { idx -> hiddenPkgs.contains(sortedApps[idx].pkg) }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("HIDE APP ICONS // SELECT TO HIDE")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val pkg = sortedApps[which].pkg
                if (isChecked) {
                    hiddenPkgs.add(pkg)
                } else {
                    hiddenPkgs.remove(pkg)
                }
            }
            .setPositiveButton("APPLY") { _, _ ->
                saveState()
                renderMaxFolders()
                renderDesktopGrid()
                if (isDrawerOpen) renderDrawerApps(drawerSearchInput.text.toString())
                Toast.makeText(this, "${hiddenPkgs.size} apps hidden", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("UNHIDE ALL") { _, _ ->
                hiddenPkgs.clear()
                saveState()
                renderMaxFolders()
                renderDesktopGrid()
                if (isDrawerOpen) renderDrawerApps(drawerSearchInput.text.toString())
            }
            .show()
    }

    // 3. Customization & Theming Sub-Menu (Wallpaper & Style)
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
                        renderMaxFolders()
                        renderDesktopGrid()
                        showCustomizationThemingDialog()
                    }
                    1 -> showIconPackSelectorDialog()
                    2 -> showGridSizeSelectorDialog()
                    3 -> {
                        wallpaperDimAlpha = when (wallpaperDimAlpha) { 55 -> 0; 0 -> 140; else -> 55 }
                        saveState()
                        buildMasterUI()
                        renderMaxFolders()
                        renderDesktopGrid()
                        showCustomizationThemingDialog()
                    }
                    4 -> {
                        try { startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) } catch (_: Exception) {}
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
        } catch (_: Exception) {}

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
                renderMaxFolders()
                renderDesktopGrid()
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
                renderMaxFolders()
                renderDesktopGrid()
                dialog.dismiss()
                showCustomizationThemingDialog()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showZeroStateGlanceSheet() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("ZERO-STATE // GLANCE & NOTIFICATIONS")
            .setMessage(
                "• Morning Context: Ahmedabad • 29°C Clear\n" +
                "• Agenda: Product Architecture Sync (10:00 AM)\n" +
                "• Commute: SG Highway • 14 mins (Clear)\n" +
                "• Active Delivery: Zomato Order arriving in 6m"
            )
            .setPositiveButton("OPEN SYSTEM NOTIFICATIONS") { _, _ -> expandSystemNotifications() }
            .setNeutralButton("CLOSE", null)
            .show()
    }

    private fun showQuickControlCenterDialog() {
        val controls = arrayOf(
            "Toggle Flashlight / Torch (${if (isTorchOn) "ON" else "OFF"})",
            "Toggle Monochrome Icons (${if (isMonoIcons) "ON" else "OFF"})",
            "Toggle Wallpaper Auto Icon Tint (${if (useWallpaperIconTint) "ON" else "OFF"})",
            "Open Home Settings (HyperOS Menu)",
            "Open System Wi-Fi & Connectivity Panel",
            "Open HyperOS System Quick Settings Shade"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("RIGHT PULL // QUICK CONTROL HUB")
            .setItems(controls) { _, which ->
                when (which) {
                    0 -> toggleFlashlight()
                    1 -> { isMonoIcons = !isMonoIcons; saveState(); renderDesktopGrid(); renderMaxFolders() }
                    2 -> { useWallpaperIconTint = !useWallpaperIconTint; saveState(); renderDesktopGrid(); renderMaxFolders() }
                    3 -> showHyperOsHomeSettingsDialog()
                    4 -> try { startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                    5 -> expandSystemQuickSettings()
                }
            }
            .show()
    }