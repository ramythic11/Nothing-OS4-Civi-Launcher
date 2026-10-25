package com.pratik.nothingcivi

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.*
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
import android.view.animation.DecelerateInterpolator
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
    val category: String
)

class MainActivity : Activity(), SensorEventListener {

    private var isExtraDark = true
    private val COLOR_BG get() = if (isExtraDark) Color.parseColor("#000000") else Color.parseColor("#0D0E11")
    private val COLOR_CARD get() = if (isExtraDark) Color.parseColor("#111111") else Color.parseColor("#191B20")
    private val COLOR_CARD_ALT get() = if (isExtraDark) Color.parseColor("#1A1A1A") else Color.parseColor("#22252C")
    private val COLOR_STROKE get() = if (isExtraDark) Color.parseColor("#262626") else Color.parseColor("#323640")
    private val COLOR_RED = Color.parseColor("#D71921")
    private val COLOR_WHITE = Color.parseColor("#FFFFFF")
    private val COLOR_MUTED = Color.parseColor("#7E7E7E")

    private lateinit var prefs: SharedPreferences
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val allApps = mutableListOf<AppEntry>()
    private val favoritePkgs = mutableListOf<String>()
    private val maxIconPkgs = mutableSetOf<String>()
    private val hiddenPkgs = mutableSetOf<String>()
    private val privatePkgs = mutableSetOf<String>()
    private val mindfulPkgs = mutableSetOf<String>()
    private val customFolders = mutableMapOf<String, MutableList<String>>()

    private var isMonoIcons = true
    private var isSmartDrawer = false
    private var includeHiddenInSearch = false
    private var gridColumns = 4
    private var clockFaceMode = 0
    private var widgetSizeSpan = 1
    private var isTorchOn = false
    private var isDrawerOpen = false
    private var isNoteExpanded = false
    private var drawerViewTab = 0

    private var sensorManager: SensorManager? = null
    private var stepBaseline = -1f
    private var dailySteps = 0
    private var sessionStartMs = SystemClock.elapsedRealtime()
    private var accumulatedUsageMin = 0
    private var weatherCity = "AHMEDABAD"
    private var weatherCond = "CLEAR"

    private var glyphRunning = false
    private var glyphSecondsLeft = 25 * 60
    private var glyphRunnable: Runnable? = null

    private lateinit var rootStack: FrameLayout
    private lateinit var homeContainer: LinearLayout
    private lateinit var drawerContainer: LinearLayout
    private lateinit var dotMatrixHeader: DotMatrixView
    private lateinit var telemetrySubText: TextView
    private lateinit var faceBadgeText: TextView
    private lateinit var widgetsGrid: GridLayout
    private lateinit var weatherWidgetText: TextView
    private lateinit var pedometerWidgetText: TextView
    private lateinit var pedometerBar: DotProgressBarView
    private lateinit var screenTimeWidgetText: TextView
    private lateinit var glyphStatusText: TextView
    private lateinit var glyphProgressView: DotProgressBarView
    private lateinit var noteInput: EditText
    private lateinit var foldersRowLayout: LinearLayout
    private lateinit var favGridLayout: GridLayout
    private lateinit var dockLayout: LinearLayout
    private lateinit var torchTileBtn: TextView
    private lateinit var monoTileBtn: TextView
    private lateinit var ringerTileBtn: TextView
    private lateinit var themeTileBtn: TextView
    private lateinit var searchInput: EditText
    private lateinit var calcResultBanner: TextView
    private lateinit var appsGridView: GridView
    private lateinit var tabAllBtn: TextView
    private lateinit var tabSmartBtn: TextView
    private lateinit var tabHiddenBtn: TextView
    private lateinit var tabPrivateBtn: TextView

    private val monoFilter = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(0f)
        val c = 1.28f
        val t = (-0.5f * c + 0.5f) * 255f
        postConcat(ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )))
    })

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateAllWidgets()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lock120HzDisplay()
        prefs = getSharedPreferences("kinetic_os4_complete", Context.MODE_PRIVATE)
        loadSavedState()
        initStepSensor()
        buildUserInterface()
        updateAllWidgets()
        loadInstalledAppsAsync()
    }

    private fun lock120HzDisplay() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = COLOR_BG
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val highest = display?.supportedModes?.maxByOrNull { it.refreshRate }
            if (highest != null) {
                val p = window.attributes
                p.preferredDisplayModeId = highest.modeId
                window.attributes = p
            }
        }
    }

    private fun initStepSensor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 101)
            }
        }
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepSensor != null) {
            sensorManager?.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            val totalSinceBoot = event.values[0]
            if (stepBaseline < 0f) {
                stepBaseline = prefs.getFloat("step_baseline", totalSinceBoot)
                if (totalSinceBoot < stepBaseline) stepBaseline = totalSinceBoot
                prefs.edit().putFloat("step_baseline", stepBaseline).apply()
            }
            dailySteps = (totalSinceBoot - stepBaseline).toInt().coerceAtLeast(0)
            updatePedometerUI()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun loadSavedState() {
        isExtraDark = prefs.getBoolean("extra_dark", true)
        isMonoIcons = prefs.getBoolean("mono_icons", true)
        isSmartDrawer = prefs.getBoolean("smart_drawer", false)
        includeHiddenInSearch = prefs.getBoolean("search_hidden", false)
        gridColumns = prefs.getInt("grid_cols", 4)
        clockFaceMode = prefs.getInt("clock_face", 0)
        widgetSizeSpan = prefs.getInt("widget_span", 1)
        dailySteps = prefs.getInt("saved_steps", 1420)
        accumulatedUsageMin = prefs.getInt("usage_min", 18)
        weatherCity = prefs.getString("weather_city", "AHMEDABAD") ?: "AHMEDABAD"
        weatherCond = prefs.getString("weather_cond", "CLEAR") ?: "CLEAR"

        maxIconPkgs.clear()
        maxIconPkgs.addAll(prefs.getStringSet("max_icons", emptySet()) ?: emptySet())
        hiddenPkgs.clear()
        hiddenPkgs.addAll(prefs.getStringSet("hidden_pkgs", emptySet()) ?: emptySet())
        privatePkgs.clear()
        privatePkgs.addAll(prefs.getStringSet("private_pkgs", emptySet()) ?: emptySet())
        mindfulPkgs.clear()
        mindfulPkgs.addAll(prefs.getStringSet("mindful_pkgs", emptySet()) ?: emptySet())

        favoritePkgs.clear()
        val favRaw = prefs.getString("fav_list", null)
        if (!favRaw.isNullOrEmpty()) {
            favoritePkgs.addAll(favRaw.split(",").filter { it.isNotBlank() })
        }

        customFolders.clear()
        val foldersRaw = prefs.getString("folders_data", "WORK:;SOCIAL:") ?: ""
        for (entry in foldersRaw.split("|")) {
            val parts = entry.split(":")
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                val fName = parts[0]
                val pkgs = if (parts.size > 1 && parts[1].isNotBlank()) {
                    parts[1].split(",").toMutableList()
                } else mutableListOf()
                customFolders[fName] = pkgs
            }
        }
    }

    private fun saveState() {
        val folderSerialized = customFolders.entries.joinToString("|") { "${it.key}:${it.value.joinToString(",")}" }
        prefs.edit()
            .putBoolean("extra_dark", isExtraDark)
            .putBoolean("mono_icons", isMonoIcons)
            .putBoolean("smart_drawer", isSmartDrawer)
            .putBoolean("search_hidden", includeHiddenInSearch)
            .putInt("grid_cols", gridColumns)
            .putInt("clock_face", clockFaceMode)
            .putInt("widget_span", widgetSizeSpan)
            .putInt("saved_steps", dailySteps)
            .putInt("usage_min", accumulatedUsageMin)
            .putString("weather_city", weatherCity)
            .putString("weather_cond", weatherCond)
            .putStringSet("max_icons", maxIconPkgs)
            .putStringSet("hidden_pkgs", hiddenPkgs)
            .putStringSet("private_pkgs", privatePkgs)
            .putStringSet("mindful_pkgs", mindfulPkgs)
            .putString("fav_list", favoritePkgs.joinToString(","))
            .putString("folders_data", folderSerialized)
            .apply()
    }

    private fun buildUserInterface() {
        rootStack = FrameLayout(this).apply {
            setBackgroundColor(if (isExtraDark) Color.argb(245, 0, 0, 0) else Color.argb(235, 13, 14, 17))
        }

        homeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(14))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val clockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(22), COLOR_STROKE)
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setOnClickListener {
                vibrateTick(false)
                clockFaceMode = (clockFaceMode + 1) % 4
                saveState()
                updateAllWidgets()
            }
            setOnLongClickListener {
                vibrateTick(true)
                showLauncherSettingsDialog()
                true
            }
        }

        val topHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        dotMatrixHeader = DotMatrixView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        }

        faceBadgeText = TextView(this).apply {
            text = "OS 4.0 // CFG"
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextColor(COLOR_WHITE)
            background = roundRect(COLOR_RED, dp(10))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                vibrateTick(false)
                showLauncherSettingsDialog()
            }
        }

        topHeaderRow.addView(dotMatrixHeader)
        topHeaderRow.addView(faceBadgeText)

        telemetrySubText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_MUTED)
            textSize = 10f
            setPadding(0, dp(6), 0, 0)
        }

        clockCard.addView(topHeaderRow)
        clockCard.addView(telemetrySubText)
        homeContainer.addView(clockCard)

        widgetsGrid = GridLayout(this).apply {
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        buildResizableOS4Widgets()
        homeContainer.addView(widgetsGrid)

        val bentoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }

        val glyphCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).apply { rightMargin = dp(6) }
            setOnClickListener { toggleGlyphTimer() }
            setOnLongClickListener { resetGlyphTimer(); true }
        }

        glyphStatusText = TextView(this).apply {
            text = "GLYPH LIVE // 25:00"
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextColor(COLOR_WHITE)
        }

        glyphProgressView = DotProgressBarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(4) }
        }

        glyphCard.addView(glyphStatusText)
        glyphCard.addView(glyphProgressView)

        val mediaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f)
        }

        val mediaTitle = TextView(this).apply {
            text = "MEDIA // CTRL"
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextColor(COLOR_MUTED)
        }

        val mediaBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        fun mediaBtn(lbl: String, key: Int, red: Boolean = false): TextView {
            return TextView(this).apply {
                text = lbl
                typeface = Typeface.MONOSPACE
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (red) COLOR_RED else COLOR_WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { vibrateTick(false); dispatchMediaKey(key) }
            }
        }

        mediaBtns.addView(mediaBtn("|<<", KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        mediaBtns.addView(mediaBtn("PLAY", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true))
        mediaBtns.addView(mediaBtn(">>|", KeyEvent.KEYCODE_MEDIA_NEXT))
        mediaCard.addView(mediaTitle)
        mediaCard.addView(mediaBtns)

        bentoRow.addView(glyphCard)
        bentoRow.addView(mediaCard)
        homeContainer.addView(bentoRow)

        val tilesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }

        torchTileBtn = createQuickTile("TORCH", false) { toggleFlashlight() }
        monoTileBtn = createQuickTile(if (isMonoIcons) "MONO: ON" else "MONO: OFF", isMonoIcons) {
            isMonoIcons = !isMonoIcons
            saveState()
            rebuildUI()
        }
        ringerTileBtn = createQuickTile(getRingerLabel(), false) { cycleRingerMode() }
        themeTileBtn = createQuickTile("NOTE +", false) {
            isNoteExpanded = !isNoteExpanded
            noteInput.visibility = if (isNoteExpanded) View.VISIBLE else View.GONE
        }

        tilesRow.addView(torchTileBtn, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(6) })
        tilesRow.addView(monoTileBtn, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(6) })
        tilesRow.addView(ringerTileBtn, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(6) })
        tilesRow.addView(themeTileBtn, LinearLayout.LayoutParams(0, dp(34), 1f))
        homeContainer.addView(tilesRow)

        noteInput = EditText(this).apply {
            hint = "PLAYGROUND MEMO // OFFLINE ESSENTIAL NOTE..."
            setHintTextColor(COLOR_MUTED)
            setTextColor(COLOR_WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setText(prefs.getString("essential_note", ""))
            background = roundRect(COLOR_CARD_ALT, dp(16), COLOR_RED)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString("essential_note", s?.toString() ?: "").apply()
                }
            })
        }
        homeContainer.addView(noteInput)

        val folderScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        foldersRowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        folderScroll.addView(foldersRowLayout)
        homeContainer.addView(folderScroll)

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

        val searchPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(COLOR_CARD, dp(24), COLOR_STROKE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            setOnClickListener { openDrawer(focusKeyboard = true) }
        }

        val redDot = View(this).apply {
            background = roundRect(COLOR_RED, dp(5))
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(10) }
        }
        val searchLbl = TextView(this).apply {
            text = "ESSENTIAL SEARCH // APPS, MATH & HIDDEN..."
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            setTextColor(COLOR_MUTED)
        }
        searchPill.addView(redDot)
        searchPill.addView(searchLbl)
        homeContainer.addView(searchPill)

        dockLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundRect(COLOR_CARD, dp(22), COLOR_STROKE)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
        }
        homeContainer.addView(dockLayout)

        drawerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(16), dp(38), dp(16), dp(12))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val drawerSearchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }

        searchInput = EditText(this).apply {
            hint = "SEARCH APPS, MATH (45*18) OR WEB..."
            setHintTextColor(COLOR_MUTED)
            setTextColor(COLOR_WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setSingleLine(true)
            background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString()?.trim() ?: ""
                    evaluateMathOrSearch(q)
                    renderAppDrawer(q, autoLaunchIfSingle = q.length >= 2 && drawerViewTab == 0)
                }
            })
        }

        val scopeBtn = TextView(this).apply {
            text = if (includeHiddenInSearch) "SCOPE:ALL" else "SCOPE:STD"
            typeface = Typeface.MONOSPACE
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(COLOR_WHITE)
            background = roundRect(if (includeHiddenInSearch) COLOR_RED else COLOR_CARD, dp(16), COLOR_STROKE)
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
            setOnClickListener {
                vibrateTick(false)
                includeHiddenInSearch = !includeHiddenInSearch
                text = if (includeHiddenInSearch) "SCOPE:ALL" else "SCOPE:STD"
                background = roundRect(if (includeHiddenInSearch) COLOR_RED else COLOR_CARD, dp(16), COLOR_STROKE)
                saveState()
                renderAppDrawer(searchInput.text.toString())
            }
        }

        drawerSearchRow.addView(searchInput)
        drawerSearchRow.addView(scopeBtn)
        drawerContainer.addView(drawerSearchRow)

        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }

        tabAllBtn = createQuickTile("A-Z APPS", drawerViewTab == 0 && !isSmartDrawer) {
            drawerViewTab = 0; isSmartDrawer = false; updateDrawerTabStyles(); renderAppDrawer(searchInput.text.toString())
        }
        tabSmartBtn = createQuickTile("CATEGORIES", drawerViewTab == 0 && isSmartDrawer) {
            drawerViewTab = 0; isSmartDrawer = true; updateDrawerTabStyles(); renderAppDrawer(searchInput.text.toString())
        }
        tabHiddenBtn = createQuickTile("HIDDEN (${hiddenPkgs.size})", drawerViewTab == 1) {
            drawerViewTab = 1; updateDrawerTabStyles(); renderAppDrawer(searchInput.text.toString())
        }
        tabPrivateBtn = createQuickTile("PRIVATE 🔒", drawerViewTab == 2) {
            authenticatePrivateSpace()
        }

        tabsRow.addView(tabAllBtn, LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(4) })
        tabsRow.addView(tabSmartBtn, LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(4) })
        tabsRow.addView(tabHiddenBtn, LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(4) })
        tabsRow.addView(tabPrivateBtn, LinearLayout.LayoutParams(0, dp(32), 1f))
        drawerContainer.addView(tabsRow)

        calcResultBanner = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(COLOR_WHITE)
            background = roundRect(COLOR_CARD_ALT, dp(14), COLOR_RED)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        drawerContainer.addView(calcResultBanner)

        appsGridView = GridView(this).apply {
            numColumns = gridColumns
            verticalSpacing = dp(10)
            horizontalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        drawerContainer.addView(appsGridView)

        rootStack.addView(homeContainer)
        rootStack.addView(drawerContainer)

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || isDrawerOpen) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > dp(55)) {
                    if (dy < 0) { openDrawer(focusKeyboard = false); return true }
                    else { expandNotificationShade(); return true }
                }
                return false
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                vibrateTick(true)
                clockFaceMode = (clockFaceMode + 1) % 4
                updateAllWidgets()
                return true
            }
        })

        homeContainer.setOnTouchListener { _, ev -> gestureDetector.onTouchEvent(ev); false }
        setContentView(rootStack)
    }

    private fun buildResizableOS4Widgets() {
        widgetsGrid.removeAllViews()
        val totalWidth = resources.displayMetrics.widthPixels - dp(32)
        val unitW = totalWidth / 3
        val wSpan = if (widgetSizeSpan == 2) 2 else 1

        val weatherCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = GridLayout.LayoutParams().apply {
                width = unitW * wSpan - dp(6)
                height = dp(58)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, wSpan)
                setMargins(dp(3), 0, dp(3), 0)
            }
            setOnClickListener { vibrateTick(false); promptConfigureWeather() }
            setOnLongClickListener {
                vibrateTick(true)
                widgetSizeSpan = if (widgetSizeSpan == 1) 2 else 1
                saveState()
                buildResizableOS4Widgets()
                updateAllWidgets()
                true
            }
        }

        val wTitle = TextView(this).apply {
            text = "WEATHER // $weatherCity"
            typeface = Typeface.MONOSPACE
            textSize = 8.5f
            setTextColor(COLOR_MUTED)
            maxLines = 1
        }
        weatherWidgetText = TextView(this).apply {
            text = "31°C $weatherCond"
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(COLOR_WHITE)
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        }
        weatherCard.addView(wTitle)
        weatherCard.addView(weatherWidgetText)

        val pedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = GridLayout.LayoutParams().apply {
                width = unitW - dp(6)
                height = dp(58)
                setMargins(dp(3), 0, dp(3), 0)
            }
            setOnClickListener {
                vibrateTick(false)
                dailySteps += 250
                saveState()
                updatePedometerUI()
            }
        }

        pedometerWidgetText = TextView(this).apply {
            text = "STEPS // $dailySteps"
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextColor(COLOR_WHITE)
            maxLines = 1
        }
        pedometerBar = DotProgressBarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(6) }
        }
        pedCard.addView(pedometerWidgetText)
        pedCard.addView(pedometerBar)

        widgetsGrid.addView(weatherCard)
        widgetsGrid.addView(pedCard)

        screenTimeWidgetText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(COLOR_WHITE)
            setPadding(0, dp(4), 0, 0)
        }

        if (widgetSizeSpan == 1) {
            val screenCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = unitW - dp(6)
                    height = dp(58)
                    setMargins(dp(3), 0, dp(3), 0)
                }
                setOnClickListener { vibrateTick(false); showBackgroundAppDashboard() }
            }
            val stTitle = TextView(this).apply {
                text = "SCREEN TIME"
                typeface = Typeface.MONOSPACE
                textSize = 8.5f
                setTextColor(COLOR_MUTED)
            }
            screenCard.addView(stTitle)
            screenCard.addView(screenTimeWidgetText)
            widgetsGrid.addView(screenCard)
        }
    }

    private fun updatePedometerUI() {
        pedometerWidgetText.text = "STEPS // $dailySteps"
        pedometerBar.setProgress((dailySteps / 6000f).coerceIn(0f, 1f))
    }

    private fun promptConfigureWeather() {
        val input = EditText(this).apply { setText(weatherCity); setTextColor(COLOR_WHITE) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("WEATHER WIDGET // CITY")
            .setMessage("Tip: Long-press the weather widget to resize between 1x1 and 2x1 layouts.")
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                weatherCity = input.text.toString().trim().uppercase(Locale.getDefault()).ifEmpty { "AHMEDABAD" }
                saveState(); buildResizableOS4Widgets(); updateAllWidgets()
            }.show()
    }

    private fun showBackgroundAppDashboard() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val availMb = mem.availMem / (1024 * 1024)
        val totalMb = mem.totalMem / (1024 * 1024)
        val usedPct = (((totalMb - availMb).toFloat() / totalMb) * 100).toInt()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("OS 4.0 // BACKGROUND & RAM OPTIMIZER")
            .setMessage("Active System Memory: ${totalMb - availMb} MB / $totalMb MB ($usedPct%)\nLauncher Footprint: ~22 MB\nScreen Time Today: ${getScreenTimeFormatted()}")
            .setPositiveButton("TRIM LAUNCHER MEMORY") { _, _ ->
                System.gc()
                vibrateTick(true)
                Toast.makeText(this, "Memory caches trimmed", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("SYSTEM APPS") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS)) } catch (_: Exception) {}
            }
            .setNegativeButton("CLOSE", null).show()
    }

    private fun showLauncherSettingsDialog() {
        val items = arrayOf(
            "Extra Dark Mode: ${if (isExtraDark) "PITCH BLACK (#000)" else "GRAPHITE (#0D0E11)"}",
            "Home Grid Columns: ${gridColumns}x${gridColumns}",
            "Widget Layout Size: ${if (widgetSizeSpan == 1) "1x1 Compact" else "2x1 Expanded"}",
            "Search Scope Includes Hidden Apps: ${if (includeHiddenInSearch) "YES" else "NO"}",
            "Change System Wallpaper",
            "Background App & RAM Dashboard",
            "Unhide All Hidden Apps (${hiddenPkgs.size})"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("KINETIC OS 4.0 // HOME SETTINGS")
            .setItems(items) { _, idx ->
                when (idx) {
                    0 -> { isExtraDark = !isExtraDark; saveState(); rebuildUI() }
                    1 -> { gridColumns = if (gridColumns == 4) 5 else 4; saveState(); rebuildUI() }
                    2 -> { widgetSizeSpan = if (widgetSizeSpan == 1) 2 else 1; saveState(); rebuildUI() }
                    3 -> { includeHiddenInSearch = !includeHiddenInSearch; saveState(); rebuildUI() }
                    4 -> { try { startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) } catch (_: Exception) {} }
                    5 -> showBackgroundAppDashboard()
                    6 -> { hiddenPkgs.clear(); saveState(); rebuildUI() }
                }
            }.show()
    }

    private fun rebuildUI() {
        buildUserInterface(); updateAllWidgets(); renderFoldersBar(); renderHomeFavorites(); renderAppDrawer(searchInput.text.toString())
    }

    private fun updateDrawerTabStyles() {
        tabAllBtn.background = roundRect(if (drawerViewTab == 0 && !isSmartDrawer) COLOR_RED else COLOR_CARD, dp(16), COLOR_STROKE)
        tabSmartBtn.background = roundRect(if (drawerViewTab == 0 && isSmartDrawer) COLOR_RED else COLOR_CARD, dp(16), COLOR_STROKE)
        tabHiddenBtn.text = "HIDDEN (${hiddenPkgs.size})"
        tabHiddenBtn.background = roundRect(if (drawerViewTab == 1) COLOR_RED else COLOR_CARD, dp(16), COLOR_STROKE)
        tabPrivateBtn.background = roundRect(if (drawerViewTab == 2) COLOR_RED else COLOR_CARD, dp(16), COLOR_STROKE)
    }

    private fun authenticatePrivateSpace() {
        val savedPin = prefs.getString("private_pin", null)
        val pinInput = EditText(this).apply {
            hint = if (savedPin == null) "CREATE 4-DIGIT PIN" else "ENTER 4-DIGIT PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(COLOR_WHITE); setHintTextColor(COLOR_MUTED)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("PRIVATE SPACE // AUTHENTICATION")
            .setView(pinInput)
            .setPositiveButton("UNLOCK") { _, _ ->
                val entered = pinInput.text.toString().trim()
                if (savedPin == null && entered.length >= 4) {
                    prefs.edit().putString("private_pin", entered).apply()
                    drawerViewTab = 2; updateDrawerTabStyles(); renderAppDrawer("")
                } else if (entered == savedPin) {
                    vibrateTick(true); drawerViewTab = 2; updateDrawerTabStyles(); renderAppDrawer("")
                } else {
                    Toast.makeText(this, "Invalid PIN", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun createQuickTile(label: String, active: Boolean, onTap: () -> Unit): TextView {
        return TextView(this).apply {
            text = label; typeface = Typeface.MONOSPACE; textSize = 9f; setTextColor(COLOR_WHITE); gravity = Gravity.CENTER
            background = roundRect(if (active) COLOR_RED else COLOR_CARD, dp(16), COLOR_STROKE)
            setOnClickListener { vibrateTick(false); onTap() }
        }
    }

    private fun loadInstalledAppsAsync() {
        bgExecutor.execute {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolveList = pm.queryIntentActivities(mainIntent, 0)
            val loaded = mutableListOf<AppEntry>()
            for (ri in resolveList) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName) continue
                val defaultLabel = ri.loadLabel(pm).toString()
                val customLabel = prefs.getString("rename_$pkg", null) ?: defaultLabel
                val icon = ri.loadIcon(pm)
                val cat = classifyCategory(ri.activityInfo.applicationInfo, defaultLabel)
                loaded.add(AppEntry(defaultLabel, customLabel, pkg, ri.activityInfo.name, icon, cat))
            }
            loaded.sortBy { it.customLabel.lowercase(Locale.getDefault()) }
            mainHandler.post {
                allApps.clear(); allApps.addAll(loaded)
                if (favoritePkgs.isEmpty() && allApps.isNotEmpty()) {
                    allApps.take(6).forEach { favoritePkgs.add(it.pkg) }
                    maxIconPkgs.add(favoritePkgs.first())
                    saveState()
                }
                renderFoldersBar(); renderHomeFavorites(); renderAppDrawer(searchInput.text.toString())
            }
        }
    }

    private fun classifyCategory(appInfo: ApplicationInfo, label: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (appInfo.category) {
                ApplicationInfo.CATEGORY_GAME -> return "[ GAMES ]"
                ApplicationInfo.CATEGORY_SOCIAL -> return "[ SOCIAL ]"
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> return "[ MEDIA ]"
                ApplicationInfo.CATEGORY_PRODUCTIVITY, ApplicationInfo.CATEGORY_NEWS -> return "[ WORK ]"
            }
        }
        val l = label.lowercase(Locale.getDefault())
        return when {
            l.contains("whatsapp") || l.contains("instagram") || l.contains("telegram") || l.contains("phone") || l.contains("message") -> "[ SOCIAL ]"
            l.contains("youtube") || l.contains("spotify") || l.contains("camera") || l.contains("gallery") || l.contains("music") -> "[ MEDIA ]"
            else -> "[ UTILITIES ]"
        }
    }

    private fun renderFoldersBar() {
        foldersRowLayout.removeAllViews()
        for ((folderName, pkgList) in customFolders) {
            val pill = TextView(this).apply {
                text = ":: $folderName (${pkgList.size})"
                typeface = Typeface.MONOSPACE; textSize = 9.5f; setTextColor(COLOR_WHITE)
                background = roundRect(COLOR_CARD_ALT, dp(16), COLOR_STROKE)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
                setOnClickListener { vibrateTick(false); showFolderPopup(folderName, pkgList) }
            }
            foldersRowLayout.addView(pill)
        }
        val addFolderBtn = TextView(this).apply {
            text = "+ FOLDER"; typeface = Typeface.MONOSPACE; textSize = 9.5f; setTextColor(COLOR_RED)
            background = roundRect(COLOR_CARD, dp(16), COLOR_STROKE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { vibrateTick(false); promptCreateFolder() }
        }
        foldersRowLayout.addView(addFolderBtn)
    }

    private fun showFolderPopup(folderName: String, pkgList: MutableList<String>) {
        val folderApps = pkgList.mapNotNull { p -> allApps.find { it.pkg == p } }
        if (folderApps.isEmpty()) {
            Toast.makeText(this, "Long-press any app to add it to $folderName", Toast.LENGTH_SHORT).show()
            return
        }
        val names = folderApps.map { it.customLabel }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("FOLDER // $folderName")
            .setItems(names) { _, idx -> launchApp(folderApps[idx], false) }
            .setNegativeButton("Delete Folder") { _, _ -> customFolders.remove(folderName); saveState(); renderFoldersBar() }
            .setNeutralButton("Close", null).show()
    }

    private fun promptCreateFolder() {
        val input = EditText(this).apply { hint = "FOLDER NAME"; setTextColor(COLOR_WHITE); setHintTextColor(COLOR_MUTED) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("NEW FOLDER").setView(input)
            .setPositiveButton("CREATE") { _, _ ->
                val name = input.text.toString().trim().uppercase(Locale.getDefault())
                if (name.isNotEmpty()) { customFolders[name] = mutableListOf(); saveState(); renderFoldersBar() }
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun renderHomeFavorites() {
        favGridLayout.removeAllViews(); favGridLayout.columnCount = gridColumns; dockLayout.removeAllViews()
        val favApps = favoritePkgs.mapNotNull { pkg -> allApps.find { it.pkg == pkg } }
        val screenWidth = resources.displayMetrics.widthPixels - dp(32)
        val cellSize = screenWidth / gridColumns

        for (app in favApps) {
            val isMax = maxIconPkgs.contains(app.pkg)
            val span = if (isMax) 2 else 1
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = roundRect(COLOR_CARD, dp(20), if (isMax) COLOR_RED else COLOR_STROKE)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cellSize * span - dp(8)
                    height = if (isMax) cellSize * 2 - dp(12) else cellSize + dp(2)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, span)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                setOnClickListener { launchApp(app, false) }
                setOnLongClickListener { vibrateTick(true); showAppOptionsDialog(app, isPinned = true); true }
            }
            val iconView = ImageView(this).apply {
                setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                colorFilter = if (isMonoIcons) monoFilter else null
                val s = if (isMax) dp(64) else dp(38)
                layoutParams = LinearLayout.LayoutParams(s, s)
            }
            val lblView = TextView(this).apply {
                text = app.customLabel.uppercase(Locale.getDefault())
                typeface = Typeface.MONOSPACE; textSize = if (isMax) 11f else 8.5f; setTextColor(COLOR_WHITE)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
            }
            card.addView(iconView); card.addView(lblView); favGridLayout.addView(card)
        }

        for (app in favApps.take(4)) {
            val dockIcon = ImageView(this).apply {
                setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                colorFilter = if (isMonoIcons) monoFilter else null
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
                setOnClickListener { launchApp(app, false) }
            }
            dockLayout.addView(dockIcon)
        }
        val drawerBtn = TextView(this).apply {
            text = ":::"; typeface = Typeface.MONOSPACE; textSize = 18f; setTextColor(COLOR_RED); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
            setOnClickListener { openDrawer(focusKeyboard = false) }
        }
        dockLayout.addView(drawerBtn)
    }

    private fun evaluateMathOrSearch(query: String) {
        if (query.isEmpty()) { calcResultBanner.visibility = View.GONE; return }
        val mathRegex = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*([+\-*/%])\s*(-?\d+(?:\.\d+)?)\s*$""")
        val match = mathRegex.matchEntire(query)
        if (match != null) {
            val a = match.groupValues[1].toDoubleOrNull() ?: 0.0
            val op = match.groupValues[2]
            val b = match.groupValues[3].toDoubleOrNull() ?: 0.0
            val res = when (op) { "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> if (b != 0.0) a / b else Double.NaN; "%" -> a % b; else -> 0.0 }
            val formatted = if (res % 1.0 == 0.0) res.toLong().toString() else String.format(Locale.US, "%.2f", res)
            calcResultBanner.text = "INSTANT CALC // = $formatted  (TAP TO COPY)"
            calcResultBanner.visibility = View.VISIBLE
            calcResultBanner.setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("calc", formatted))
                Toast.makeText(this, "Copied $formatted", Toast.LENGTH_SHORT).show()
            }
        } else {
            calcResultBanner.text = "WEB SEARCH // TAP TO SEARCH \"$query\""
            calcResultBanner.visibility = View.VISIBLE
            calcResultBanner.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
                closeDrawer()
            }
        }
    }

    private fun renderAppDrawer(query: String, autoLaunchIfSingle: Boolean = false) {
        val clean = query.lowercase(Locale.getDefault())
        val baseList = when (drawerViewTab) {
            1 -> allApps.filter { hiddenPkgs.contains(it.pkg) }
            2 -> allApps.filter { privatePkgs.contains(it.pkg) }
            else -> allApps.filter { app -> !privatePkgs.contains(app.pkg) && (!hiddenPkgs.contains(app.pkg) || (clean.isNotEmpty() && includeHiddenInSearch)) }
        }
        val filtered = baseList.filter { app -> clean.isEmpty() || app.customLabel.lowercase(Locale.getDefault()).contains(clean) }.let { list ->
            if (drawerViewTab == 0 && isSmartDrawer && clean.isEmpty()) list.sortedWith(compareBy({ it.category }, { it.customLabel.lowercase(Locale.getDefault()) })) else list
        }
        if (autoLaunchIfSingle && filtered.size == 1) {
            searchInput.setText(""); closeDrawer(); launchApp(filtered.first(), false); return
        }
        appsGridView.numColumns = gridColumns
        appsGridView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = filtered.size
            override fun getItem(position: Int): Any = filtered[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val app = filtered[position]
                val cell = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(8))
                    background = roundRect(COLOR_CARD, dp(16), COLOR_STROKE)
                }
                if (drawerViewTab == 0 && isSmartDrawer && clean.isEmpty()) {
                    cell.addView(TextView(this@MainActivity).apply { text = app.category; typeface = Typeface.MONOSPACE; textSize = 7f; setTextColor(COLOR_RED); maxLines = 1 })
                }
                val img = ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                    colorFilter = if (isMonoIcons) monoFilter else null
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { topMargin = dp(4) }
                }
                val lbl = TextView(this@MainActivity).apply {
                    text = app.customLabel; typeface = Typeface.MONOSPACE; textSize = 9f; setTextColor(COLOR_WHITE)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
                }
                cell.addView(img); cell.addView(lbl)
                cell.setOnClickListener { launchApp(app, false) }
                cell.setOnLongClickListener { vibrateTick(true); showAppOptionsDialog(app, isPinned = favoritePkgs.contains(app.pkg)); true }
                return cell
            }
        }
    }

    private fun showAppOptionsDialog(app: AppEntry, isPinned: Boolean) {
        val isMax = maxIconPkgs.contains(app.pkg)
        val isHidden = hiddenPkgs.contains(app.pkg)
        val isPrivate = privatePkgs.contains(app.pkg)
        val isMindful = mindfulPkgs.contains(app.pkg)
        val options = mutableListOf<String>()

        if (isPinned) {
            options.add(if (isMax) "Shrink to 1x1 Icon" else "Enlarge to 2x2 Max Icon")
            options.add("Move Position Left / Up")
            options.add("Move Position Right / Down")
            options.add("Unpin from Home Screen")
        } else {
            options.add("Pin to Home Screen")
            options.add("Pin as 2x2 Max Icon")
        }
        options.add("Launch in Pop-Up / Multi-Window View")
        options.add("Rename App Label")
        for (folderName in customFolders.keys) options.add("Add to Folder: $folderName")
        options.add(if (isHidden) "Unhide from Hidden Icons" else "Hide App Icon (Move to Hidden)")
        options.add(if (isPrivate) "Remove from Private Space" else "Move to PIN Private Space 🔒")
        options.add(if (isMindful) "Disable 3-Sec Mindful Delay" else "Enable 3-Sec Mindful Delay")
        options.add("System App Info")
        options.add("Uninstall App")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.customLabel.uppercase(Locale.getDefault()))
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                when {
                    choice.startsWith("Enlarge") -> maxIconPkgs.add(app.pkg)
                    choice.startsWith("Shrink") -> maxIconPkgs.remove(app.pkg)
                    choice == "Move Position Left / Up" -> {
                        val idx = favoritePkgs.indexOf(app.pkg)
                        if (idx > 0) Collections.swap(favoritePkgs, idx, idx - 1)
                    }
                    choice == "Move Position Right / Down" -> {
                        val idx = favoritePkgs.indexOf(app.pkg)
                        if (idx in 0 until favoritePkgs.size - 1) Collections.swap(favoritePkgs, idx, idx + 1)
                    }
                    choice.startsWith("Unpin") -> { favoritePkgs.remove(app.pkg); maxIconPkgs.remove(app.pkg) }
                    choice == "Pin to Home Screen" -> if (!favoritePkgs.contains(app.pkg)) favoritePkgs.add(app.pkg)
                    choice == "Pin as 2x2 Max Icon" -> { if (!favoritePkgs.contains(app.pkg)) favoritePkgs.add(app.pkg); maxIconPkgs.add(app.pkg) }
                    choice.startsWith("Launch in Pop-Up") -> { launchApp(app, popUpWindow = true); return@setItems }
                    choice == "Rename App Label" -> { promptRenameApp(app); return@setItems }
                    choice.startsWith("Add to Folder: ") -> {
                        val fName = choice.removePrefix("Add to Folder: ")
                        val list = customFolders[fName]
                        if (list != null && !list.contains(app.pkg)) list.add(app.pkg)
                        renderFoldersBar()
                    }
                    choice.startsWith("Hide App") -> { hiddenPkgs.add(app.pkg); favoritePkgs.remove(app.pkg) }
                    choice.startsWith("Unhide") -> hiddenPkgs.remove(app.pkg)
                    choice.startsWith("Move to PIN Private") -> { privatePkgs.add(app.pkg); favoritePkgs.remove(app.pkg) }
                    choice.startsWith("Remove from Private") -> privatePkgs.remove(app.pkg)
                    choice.startsWith("Enable 3-Sec") -> mindfulPkgs.add(app.pkg)
                    choice.startsWith("Disable 3-Sec") -> mindfulPkgs.remove(app.pkg)
                    choice == "System App Info" -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", app.pkg, null) })
                    choice == "Uninstall App" -> startActivity(Intent(Intent.ACTION_DELETE).apply { data = Uri.fromParts("package", app.pkg, null) })
                }
                saveState(); updateDrawerTabStyles(); renderHomeFavorites(); renderAppDrawer(searchInput.text.toString())
            }.show()
    }

    private fun promptRenameApp(app: AppEntry) {
        val input = EditText(this).apply { setText(app.customLabel); setTextColor(COLOR_WHITE) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("RENAME // ${app.defaultLabel}").setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val newName = input.text.toString().trim().ifEmpty { app.defaultLabel }
                app.customLabel = newName
                prefs.edit().putString("rename_${app.pkg}", newName).apply()
                renderHomeFavorites(); renderAppDrawer(searchInput.text.toString())
            }
            .setNeutralButton("RESET") { _, _ ->
                app.customLabel = app.defaultLabel
                prefs.edit().remove("rename_${app.pkg}").apply()
                renderHomeFavorites(); renderAppDrawer(searchInput.text.toString())
            }.show()
    }

    private fun getScreenTimeFormatted(): String {
        val sessionMin = ((SystemClock.elapsedRealtime() - sessionStartMs) / 60000L).toInt()
        val total = accumulatedUsageMin + sessionMin
        return "${total / 60}H ${total % 60}M"
    }

    private fun updateAllWidgets() {
        val now = Date()
        val bm = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val tempC = (bm?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f

        when (clockFaceMode) {
            0 -> {
                dotMatrixHeader.setDotText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now))
                faceBadgeText.text = "OS 4.0 // CFG"
                telemetrySubText.text = "${SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(now).uppercase(Locale.getDefault())}  //  BAT $level%  //  $tempC°C  //  120HZ"
            }
            1 -> {
                val utcFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }
                dotMatrixHeader.setDotText(utcFmt.format(now))
                faceBadgeText.text = "UTC // DUAL"
                telemetrySubText.text = "WORLD UTC TIME  //  IST +5:30  //  BAT $level%"
            }
            2 -> {
                dotMatrixHeader.setDotText(SimpleDateFormat("hh.mm", Locale.getDefault()).format(now))
                faceBadgeText.text = "LOCK // 12H"
                telemetrySubText.text = "MINIMAL LOCK CLOCK  //  TAP FOR CIVI RAM"
            }
            3 -> {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mem = ActivityManager.MemoryInfo(); am.getMemoryInfo(mem)
                val availGb = String.format(Locale.US, "%.1f", mem.availMem / (1024.0 * 1024.0 * 1024.0))
                dotMatrixHeader.setDotText("$level:$tempC".take(5))
                faceBadgeText.text = "CIVI // RAM"
                telemetrySubText.text = "FREE RAM: ${availGb}GB  //  CORES: ${Runtime.getRuntime().availableProcessors()}  //  $tempC°C"
            }
        }
        weatherWidgetText.text = "${tempC.toInt()}°C $weatherCond"
        screenTimeWidgetText.text = getScreenTimeFormatted()
        updatePedometerUI()
    }

    private fun toggleGlyphTimer() {
        vibrateTick(false)
        if (glyphRunning) {
            glyphRunning = false
            glyphRunnable?.let { mainHandler.removeCallbacks(it) }
            glyphStatusText.text = "PAUSED // ${formatSec(glyphSecondsLeft)}"
        } else {
            glyphRunning = true
            glyphRunnable = object : Runnable {
                override fun run() {
                    if (!glyphRunning) return
                    if (glyphSecondsLeft > 0) {
                        glyphSecondsLeft--
                        glyphProgressView.setProgress(1f - (glyphSecondsLeft / (25f * 60f)))
                        glyphStatusText.text = "GLYPH LIVE // ${formatSec(glyphSecondsLeft)}"
                        mainHandler.postDelayed(this, 1000L)
                    } else {
                        glyphRunning = false; vibrateTick(true)
                        glyphStatusText.text = "COMPLETE // TAP TO START"; glyphSecondsLeft = 25 * 60
                    }
                }
            }
            mainHandler.post(glyphRunnable!!)
        }
    }

    private fun resetGlyphTimer() {
        vibrateTick(true); glyphRunning = false; glyphRunnable?.let { mainHandler.removeCallbacks(it) }
        glyphSecondsLeft = 25 * 60; glyphProgressView.setProgress(0f); glyphStatusText.text = "GLYPH LIVE // 25:00"
    }

    private fun formatSec(sec: Int): String = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)

    private fun dispatchMediaKey(keyCode: Int) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (_: Exception) {}
    }

    private fun getRingerLabel(): String {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "RING: MUTE"; AudioManager.RINGER_MODE_VIBRATE -> "RING: VIB"; else -> "RING: LOUD" }
    }

    private fun cycleRingerMode() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
            ringerTileBtn.text = getRingerLabel(); vibrateTick(true)
        } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
    }

    private fun toggleFlashlight() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull() ?: return
            isTorchOn = !isTorchOn; cm.setTorchMode(id, isTorchOn)
            torchTileBtn.text = if (isTorchOn) "TORCH: ON" else "TORCH"
            torchTileBtn.background = roundRect(if (isTorchOn) COLOR_RED else COLOR_CARD, dp(16), COLOR_STROKE)
        } catch (_: Exception) {}
    }

    private fun openDrawer(focusKeyboard: Boolean) {
        isDrawerOpen = true; drawerViewTab = 0; updateDrawerTabStyles()
        drawerContainer.alpha = 0f; drawerContainer.translationY = dp(40).toFloat(); drawerContainer.visibility = View.VISIBLE
        drawerContainer.animate().alpha(1f).translationY(0f).setDuration(180L).setInterpolator(DecelerateInterpolator()).start()
        if (focusKeyboard) {
            searchInput.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeDrawer() {
        isDrawerOpen = false
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.setText(""); calcResultBanner.visibility = View.GONE; drawerContainer.visibility = View.GONE
    }

    private fun launchApp(app: AppEntry, popUpWindow: Boolean) {
        vibrateTick(false)
        if (mindfulPkgs.contains(app.pkg)) {
            Toast.makeText(this, "MINDFUL DELAY // Opening ${app.customLabel} in 3s...", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({ executeLaunchIntent(app, popUpWindow) }, 3000L)
        } else executeLaunchIntent(app, popUpWindow)
    }

    private fun executeLaunchIntent(app: AppEntry, popUpWindow: Boolean) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(app.pkg, app.activityName)
                flags = if (popUpWindow) Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            startActivity(intent); closeDrawer()
        } catch (_: Exception) {}
    }

    private fun expandNotificationShade() {
        try {
            val service = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(service)
        } catch (_: Exception) {}
    }

    private fun vibrateTick(heavy: Boolean) {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vib?.vibrate(VibrationEffect.createPredefined(if (heavy) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_TICK))
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(tickReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        updateAllWidgets(); ringerTileBtn.text = getRingerLabel(); loadInstalledAppsAsync()
    }

    override fun onPause() {
        super.onPause()
        val sessionMin = ((SystemClock.elapsedRealtime() - sessionStartMs) / 60000L).toInt()
        if (sessionMin > 0) { accumulatedUsageMin += sessionMin; sessionStartMs = SystemClock.elapsedRealtime(); saveState() }
        try { unregisterReceiver(tickReceiver) } catch (_: Exception) {}
    }

    @Deprecated("Launcher Back Handler")
    override fun onBackPressed() { if (isDrawerOpen) closeDrawer() }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun roundRect(fill: Int, rad: Int, stroke: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = rad.toFloat(); setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }

    class DotProgressBarView(context: Context) : View(context) {
        private var progress = 0f
        private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D71921") }
        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2A2A") }
        fun setProgress(p: Float) { progress = p.coerceIn(0f, 1f); invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val totalDots = 18; val spacing = width / totalDots.toFloat()
            val r = (spacing * 0.34f).coerceAtMost(height * 0.4f); val cy = height / 2f
            val activeDots = (progress * totalDots).toInt()
            for (i in 0 until totalDots) canvas.drawCircle((i + 0.5f) * spacing, cy, r, if (i <= activeDots && progress > 0f) litPaint else dimPaint)
        }
    }

    class DotMatrixView(context: Context) : View(context) {
        private var currentText = "12:00"
        private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D71921") }
        private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#222222") }
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
        fun setDotText(t: String) { if (currentText != t) { currentText = t; invalidate() } }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rows = 7; val dotSpacing = height / (rows + 1f); val r = dotSpacing * 0.36f; var cursorX = dotSpacing
            for (ch in currentText) {
                val matrix = glyphs[ch] ?: continue
                val cols = matrix[0].length
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        val lit = matrix[row][col] == '1'
                        val p = when { !lit -> offPaint; ch == ':' || ch == '.' -> redPaint; else -> whitePaint }
                        canvas.drawCircle(cursorX + col * dotSpacing, (row + 1) * dotSpacing, r, p)
                    }
                }
                cursorX += (cols + 1.4f) * dotSpacing
            }
        }
    }
}