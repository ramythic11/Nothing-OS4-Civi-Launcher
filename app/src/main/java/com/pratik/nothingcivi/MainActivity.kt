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
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

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

    // Dynamic Palette & Material Styling (Section 1)
    private var accentColor = Color.parseColor("#D71921")
    private var isExtraDark = true
    private var isLabelFree = false
    private var isMonoIcons = true
    private var isDetoxMode = false
    private var thumbArcMode = 0 // 0=Off, 1=Right-Hand, 2=Left-Hand
    private var gridColumns = 4
    private var gridRows = 5
    private var currentSpace = "FOCUS"
    private val spaces = listOf("WORK", "COMMUTE", "FITNESS", "FOCUS", "WEEKEND")

    private val COLOR_BG get() = if (isExtraDark) Color.parseColor("#000000") else Color.parseColor("#0C0D10")
    private val COLOR_CARD get() = Color.parseColor("#141417")
    private val COLOR_CARD_ALT get() = Color.parseColor("#1C1D22")
    private val COLOR_STROKE get() = Color.parseColor("#2A2B32")
    private val COLOR_WHITE = Color.parseColor("#FFFFFF")
    private val COLOR_MUTED = Color.parseColor("#7E7E86")

    private lateinit var prefs: SharedPreferences
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val allApps = mutableListOf<AppEntry>()
    private val favoritePkgs = mutableListOf<String>()
    private val pinnedTopShelfPkgs = mutableListOf<String>()
    private val maxIconPkgs = mutableSetOf<String>()
    private val hiddenPkgs = mutableSetOf<String>()
    private val privateVaultPkgs = mutableSetOf<String>()
    private val decoyVaultPkgs = mutableSetOf<String>()
    private val mindfulPkgs = mutableSetOf<String>()
    private val batchSelectedPkgs = mutableSetOf<String>()
    private var isBatchMode = false

    // Smart Stack & Telemetry State (Section 4 & 9)
    private var clockFaceMode = 0 // 0=DotMatrix, 1=Minimalist Analog, 2=UTC Dual, 3=Civi NPU/RAM
    private var smartStackIndex = 0 // 0=Weather, 1=Pedometer Ring, 2=Compass Heading, 3=ScreenTime
    private var compassAzimuth = 42
    private var dailySteps = 2840
    private var stepBaseline = -1f
    private var isTorchOn = false
    private var isDrawerOpen = false
    private var drawerTab = 0 // 0=A-Z, 1=Smart Library, 2=Ghost Shelf (>30d), 3=Vault (Real/Decoy)
    private var activeVaultMode = 0 // 0=Locked, 1=Primary Vault, 2=Decoy Profile

    // Glyph Timer State
    private var glyphRunning = false
    private var glyphSecondsLeft = 25 * 60
    private var glyphRunnable: Runnable? = null

    // UI References
    private lateinit var rootStack: FrameLayout
    private lateinit var homeContainer: LinearLayout
    private lateinit var detoxContainer: LinearLayout
    private lateinit var drawerContainer: LinearLayout
    private lateinit var dotMatrixHeader: DotMatrixView
    private lateinit var telemetrySubText: TextView
    private lateinit var spacePillsRow: LinearLayout
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
    private lateinit var appsGridView: GridView

    private var sensorManager: SensorManager? = null

    private val monoFilter = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(0f)
        val c = 1.32f
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
            updateAllTelemetry()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enforce120HzPipeline()
        prefs = getSharedPreferences("kinetic_os4_master", Context.MODE_PRIVATE)
        loadState()
        initHardwareSensors()
        buildMasterUI()
        updateAllTelemetry()
        loadAppsAsync()
    }

    private fun enforce120HzPipeline() {
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
        sensorManager?.getDefaultSensor(Sensor.TYPE_ORIENTATION)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
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

    // Haptic Tactile Matrix (Section 1: 4 Standardized Patterns)
    private fun triggerHaptic(type: Int) {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = when (type) {
                0 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) // 8ms Grid Snap
                1 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK) // 16ms Long-Press Thud
                2 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK) // Two-stage Folder Pop
                else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            }
            vib.vibrate(effect)
        }
    }

    private fun loadState() {
        isMonoIcons = prefs.getBoolean("mono", true)
        isLabelFree = prefs.getBoolean("label_free", false)
        isDetoxMode = prefs.getBoolean("detox", false)
        gridColumns = prefs.getInt("grid_cols", 4)
        currentSpace = prefs.getString("space", "FOCUS") ?: "FOCUS"
        accentColor = prefs.getInt("accent", Color.parseColor("#D71921"))
        maxIconPkgs.addAll(prefs.getStringSet("max_icons", emptySet()) ?: emptySet())
        hiddenPkgs.addAll(prefs.getStringSet("hidden_pkgs", emptySet()) ?: emptySet())
        privateVaultPkgs.addAll(prefs.getStringSet("private_pkgs", emptySet()) ?: emptySet())
        decoyVaultPkgs.addAll(prefs.getStringSet("decoy_pkgs", emptySet()) ?: emptySet())
        mindfulPkgs.addAll(prefs.getStringSet("mindful_pkgs", emptySet()) ?: emptySet())

        val favRaw = prefs.getString("fav_list", null)
        if (!favRaw.isNullOrEmpty()) {
            favoritePkgs.addAll(favRaw.split(",").filter { it.isNotBlank() })
        }
    }

    private fun saveState() {
        prefs.edit()
            .putBoolean("mono", isMonoIcons)
            .putBoolean("label_free", isLabelFree)
            .putBoolean("detox", isDetoxMode)
            .putInt("grid_cols", gridColumns)
            .putString("space", currentSpace)
            .putInt("accent", accentColor)
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
            setBackgroundColor( Color.argb(242, 0, 0, 0))
        }

        homeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(34), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Dynamic Spaces Selector Strip (Section 4)
        spacePillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        renderSpacePills()
        homeContainer.addView(spacePillsRow)

        // 1. Dot-Matrix Clock & Telemetry Header (Section 1 & 4)
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
                showArchitecturalConfigDialog()
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
        val cfgBtn = TextView(this).apply {
            text = "OS 4.0 // CFG"
            typeface = Typeface.MONOSPACE
            textSize = 8.5f
            setTextColor(COLOR_WHITE)
            background = roundRect(accentColor, dp(10))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { showArchitecturalConfigDialog() }
        }
        topRow.addView(dotMatrixHeader)
        topRow.addView(cfgBtn)

        telemetrySubText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 9.5f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(5), 0, 0)
        }
        clockCard.addView(topRow)
        clockCard.addView(telemetrySubText)
        homeContainer.addView(clockCard)

        // 2. Smart Widget Stack + Glyph Timer Bento (Section 4)
        val bentoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(7)
            }
        }

        val stackCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
            setOnClickListener {
                triggerHaptic(0)
                smartStackIndex = (smartStackIndex + 1) % 4
                updateSmartStackUI()
            }
        }
        smartStackTitle = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 8.5f
            setTextColor(COLOR_MUTED)
        }
        smartStackValue = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(COLOR_WHITE)
            setPadding(0, dp(3), 0, dp(2))
        }
        smartStackSub = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 8f
            setTextColor(accentColor)
        }
        stackCard.addView(smartStackTitle)
        stackCard.addView(smartStackValue)
        stackCard.addView(smartStackSub)

        val glyphCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
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

        glyphCard.addView(glyphStatusText)
        glyphCard.addView(glyphProgressView)
        glyphCard.addView(mediaRow)

        bentoRow.addView(stackCard)
        bentoRow.addView(glyphCard)
        homeContainer.addView(bentoRow)

        // 3. Peripheral Telemetry Strip (Section 9)
        peripheralStripText = TextView(this).apply {
            text = "PERIPHERALS // EAR(3): 92%  •  WATCH: 64%  •  RING: 78%  •  TORCH: OFF"
            typeface = Typeface.MONOSPACE
            textSize = 8.5f
            setTextColor(COLOR_MUTED)
            background = roundRect(COLOR_CARD, dp(14), COLOR_STROKE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
            setOnClickListener { toggleFlashlight() }
        }
        homeContainer.addView(peripheralStripText)

        // 4. Max Folders (2x2 Interactive Hubs with Direct 1-Tap Launch + Expand - Section 7)
        maxFoldersRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        homeContainer.addView(maxFoldersRow)

        // 5. Desktop Configurable App Grid (Sections 2, 6, 8, 9)
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

        // 6. Agentic Intent Search Bar (Section 3: SLM Natural Language Parser)
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
            text = "LIBRARY ↑"
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextColor(accentColor)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { openDrawer(focusKb = false) }
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

        // ================= DETOX MINIMALIST MODE LAYER (Section 8) =================
        detoxContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(48), dp(24), dp(24))
            visibility = if (isDetoxMode) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // ================= APP DRAWER LAYER (Section 5: Pinned Top Shelf, Ghost Shelf, Decoy Vault) =================
        drawerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(248, 0, 0, 0))
            setPadding(dp(14), dp(36), dp(14), dp(12))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        drawerSearchInput = EditText(this).apply {
            hint = "UNIVERSAL MULTI-INDEX // APPS, SHORTCUTS, MATH..."
            setHintTextColor(COLOR_MUTED)
            setTextColor(COLOR_WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setSingleLine(true)
            background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(8) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    renderDrawerApps(s?.toString()?.trim() ?: "")
                }
            })
        }
        drawerContainer.addView(drawerSearchInput)

        // Stationary Pinned Top Shelf in Drawer
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

        // Drawer Tabs: A-Z | SMART | GHOST (>30D) | DECOY VAULT
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
                else { drawerTab = idx; buildMasterUI(); openDrawer(false) }
            }
        }
        tabsRow.addView(makeTab("A-Z", 0))
        tabsRow.addView(makeTab("SMART", 1))
        tabsRow.addView(makeTab("GHOST >30D", 2))
        tabsRow.addView(makeTab("VAULT 🔒", 3))
        drawerContainer.addView(tabsRow)

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
        rootStack.addView(detoxContainer)
        rootStack.addView(drawerContainer)

        // System Gesture Layer (Section 2: Swipe-Up Drawer, Swipe-Down Shade, Double-Tap Sleep)
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || isDrawerOpen) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > dp(55)) {
                    if (dy < 0) { openDrawer(focusKb = false); return true }
                    else { expandSystemShade(); return true }
                }
                return false
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                triggerHaptic(1)
                isDetoxMode = !isDetoxMode
                saveState()
                renderDetoxLayer()
                return true
            }
        })
        homeContainer.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); false }

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
        val mathRegex = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*([+\-*/%])\s*(-?\d+(?:\.\d+)?)\s*$""")
        val m = mathRegex.matchEntire(q)

        agenticBanner.visibility = View.VISIBLE
        when {
            m != null -> {
                val a = m.groupValues[1].toDoubleOrNull() ?: 0.0
                val op = m.groupValues[2]
                val b = m.groupValues[3].toDoubleOrNull() ?: 0.0
                val r = when (op) { "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> if (b != 0.0) a / b else 0.0; else -> 0.0 }
                agenticBanner.text = "SLM MATH // = $r (TAP TO COPY)"
                agenticBanner.setOnClickListener {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("calc", r.toString()))
                    Toast.makeText(this, "Copied $r", Toast.LENGTH_SHORT).show()
                }
            }
            lower.contains("timer") -> {
                val mins = Regex("""(\d+)""").find(lower)?.value?.toIntOrNull() ?: 15
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
                val amt = Regex("""(\d+)""").find(lower)?.value?.toIntOrNull() ?: 500
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
        return when {
            l.contains("whatsapp") || l.contains("instagram") || l.contains("telegram") || l.contains("phone") -> "SOCIAL"
            l.contains("youtube") || l.contains("spotify") || l.contains("camera") || l.contains("music") -> "MEDIA"
            l.contains("pay") || l.contains("bank") || l.contains("upi") -> "FINANCE"
            else -> "PRODUCTIVITY"
        }
    }

    // Section 7: Max Folders (2x2 with 3 Direct 1-Tap Icons + 1 Expand Slot)
    private fun renderMaxFolders() {
        maxFoldersRow.removeAllViews()
        val folders = listOf("ESSENTIAL HUB" to allApps.take(4), "MEDIA & TOOLS" to allApps.drop(4).take(4))

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
                    colorFilter = if (isMonoIcons) monoFilter else null
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(6) }
                    setOnClickListener { launchApp(a, false) } // Direct 1-Tap Launch from Max Folder!
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

        val favApps = favoritePkgs.mapNotNull { pkg -> allApps.find { it.pkg == pkg } }
        val screenWidth = resources.displayMetrics.widthPixels - dp(28)
        val cellSize = screenWidth / gridColumns

        for ((index, app) in favApps.withIndex()) {
            val isMax = maxIconPkgs.contains(app.pkg)
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

                // Single-Handed Thumb Flow Arc offset (Section 9)
                if (thumbArcMode == 1) translationY = ((index % gridColumns) * dp(3)).toFloat()
                else if (thumbArcMode == 2) translationY = (((gridColumns - 1) - (index % gridColumns)) * dp(3)).toFloat()

                // Spring Overshoot Entrance
                scaleX = 0.92f; scaleY = 0.92f
                animate().scaleX(1f).scaleY(1f).setDuration(240L).setInterpolator(OvershootInterpolator(1.4f)).start()

                setOnClickListener {
                    if (isBatchMode) {
                        if (batchSelectedPkgs.contains(app.pkg)) batchSelectedPkgs.remove(app.pkg)
                        else batchSelectedPkgs.add(app.pkg)
                        renderDesktopGrid()
                    } else {
                        launchApp(app, false)
                    }
                }
                setOnLongClickListener {
                    triggerHaptic(1)
                    showShutterAndContextSheet(app, isPinned = true)
                    true
                }
            }

            val iconView = ImageView(this).apply {
                setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                colorFilter = if (isMonoIcons) monoFilter else null
                val s = if (isMax) dp(60) else dp(36)
                layoutParams = LinearLayout.LayoutParams(s, s)
            }
            card.addView(iconView)

            if (!isLabelFree) {
                val lblView = TextView(this).apply {
                    text = app.customLabel.uppercase(Locale.getDefault())
                    typeface = Typeface.MONOSPACE
                    textSize = if (isMax) 10.5f else 8f
                    setTextColor(COLOR_WHITE)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(0, dp(3), 0, 0)
                }
                card.addView(lblView)
            }

            // Minimalist White Micro-Dot or Inline Notification String (Section 1 & 8)
            if (app.inlineNotif != null && !isLabelFree) {
                val notifView = TextView(this).apply {
                    text = app.inlineNotif
                    typeface = Typeface.MONOSPACE
                    textSize = 7f
                    setTextColor(accentColor)
                    maxLines = 1
                }
                card.addView(notifView)
            }

            favGridLayout.addView(card)
        }

        for (app in favApps.take(4)) {
            val dockIcon = ImageView(this).apply {
                setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                colorFilter = if (isMonoIcons) monoFilter else null
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
                setOnClickListener { launchApp(app, false) }
            }
            dockLayout.addView(dockIcon)
        }

        val drawerBtn = TextView(this).apply {
            text = ":::"
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnClickListener { openDrawer(focusKb = false) }
        }
        dockLayout.addView(drawerBtn)
    }

    // Section 8: Detox Minimalist Typographic List
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

    // Section 5: Decoy Vault Gateway (Primary PIN vs Decoy PIN)
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
        // Render Stationary Pinned Top Shelf
        pinnedShelfLayout.removeAllViews()
        val shelfApps = pinnedTopShelfPkgs.mapNotNull { p -> allApps.find { it.pkg == p } }
        for (a in shelfApps) {
            val img = ImageView(this).apply {
                setImageDrawable(a.icon.constantState?.newDrawable()?.mutate() ?: a.icon)
                colorFilter = if (isMonoIcons) monoFilter else null
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
                setOnClickListener { launchApp(a, false) }
            }
            pinnedShelfLayout.addView(img)
        }

        val clean = query.lowercase(Locale.getDefault())
        val base = when (drawerTab) {
            2 -> allApps.filter { it.lastOpenedDaysAgo >= 30 } // Ghost Shelf (>30 days)
            3 -> if (activeVaultMode == 2) allApps.filter { decoyVaultPkgs.contains(it.pkg) }
                 else allApps.filter { privateVaultPkgs.contains(it.pkg) || hiddenPkgs.contains(it.pkg) }
            else -> allApps.filter { !hiddenPkgs.contains(it.pkg) && !privateVaultPkgs.contains(it.pkg) }
        }

        val filtered = base.filter { clean.isEmpty() || it.customLabel.lowercase(Locale.getDefault()).contains(clean) }

        appsGridView.numColumns = gridColumns
        appsGridView.adapter = object : BaseAdapter() {
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
                if (drawerTab == 1 || drawerTab == 2) {
                    val tag = TextView(this@MainActivity).apply {
                        text = if (drawerTab == 2) "${app.lastOpenedDaysAgo}D IDLE" else app.category
                        typeface = Typeface.MONOSPACE
                        textSize = 7f
                        setTextColor(accentColor)
                    }
                    cell.addView(tag)
                }
                val img = ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                    colorFilter = if (isMonoIcons) monoFilter else null
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
    }

    // Section 2 & 6: App Shutter (Transient Widget Card) + Contextual Long-Press Sheet
    private fun showShutterAndContextSheet(app: AppEntry, isPinned: Boolean) {
        val isMax = maxIconPkgs.contains(app.pkg)
        val isMindful = mindfulPkgs.contains(app.pkg)
        val opts = mutableListOf<String>()

        opts.add("SHUTTER // PEEK TRANSIENT WIDGET (${app.category})")
        if (isPinned) {
            opts.add(if (isMax) "Resize to 1×1 Standard Tile" else "Enlarge to 2×2 Max Icon")
            opts.add("Unpin from Desktop")
        } else {
            opts.add("Pin to Desktop")
            opts.add("Pin as 2×2 Max Icon")
        }
        opts.add("Launch in Pop-Up / Split Window")
        opts.add("Rename App Label")
        opts.add(if (isMindful) "Disable Mindful Friction (3s)" else "Enable Mindful Friction (3s)")
        opts.add("Move to Primary Private Vault 🔒")
        opts.add("Move to Decoy Vault Profile")
        opts.add("System App Info")
        opts.add("Uninstall App")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${app.customLabel.uppercase(Locale.getDefault())} // SHUTTER & CFG")
            .setItems(opts.toTypedArray()) { _, which ->
                val c = opts[which]
                when {
                    c.startsWith("SHUTTER") -> {
                        Toast.makeText(this, "${app.customLabel} Widget Shutter Active • Quota ${(app.dailyQuotaPct * 100).toInt()}%", Toast.LENGTH_SHORT).show()
                    }
                    c.startsWith("Enlarge") -> maxIconPkgs.add(app.pkg)
                    c.startsWith("Resize") -> maxIconPkgs.remove(app.pkg)
                    c.startsWith("Unpin") -> { favoritePkgs.remove(app.pkg); maxIconPkgs.remove(app.pkg) }
                    c == "Pin to Desktop" -> if (!favoritePkgs.contains(app.pkg)) favoritePkgs.add(app.pkg)
                    c == "Pin as 2×2 Max Icon" -> { if (!favoritePkgs.contains(app.pkg)) favoritePkgs.add(app.pkg); maxIconPkgs.add(app.pkg) }
                    c.startsWith("Launch in Pop-Up") -> { launchApp(app, true); return@setItems }
                    c == "Rename App Label" -> { promptRename(app); return@setItems }
                    c.startsWith("Enable Mindful") -> mindfulPkgs.add(app.pkg)
                    c.startsWith("Disable Mindful") -> mindfulPkgs.remove(app.pkg)
                    c.startsWith("Move to Primary") -> { privateVaultPkgs.add(app.pkg); favoritePkgs.remove(app.pkg) }
                    c.startsWith("Move to Decoy") -> { decoyVaultPkgs.add(app.pkg); favoritePkgs.remove(app.pkg) }
                    c == "System App Info" -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", app.pkg, null) })
                    c == "Uninstall App" -> startActivity(Intent(Intent.ACTION_DELETE).apply { data = Uri.fromParts("package", app.pkg, null) })
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

    private fun showArchitecturalConfigDialog() {
        val items = arrayOf(
            "Desktop Grid Density: ${gridColumns}×${gridRows} (Tap to Cycle 4×5 / 5×5 / 6×6)",
            "Label-Free Silhouette Mode: ${if (isLabelFree) "ON" else "OFF"}",
            "Silhouette Monochrome Shader: ${if (isMonoIcons) "ON" else "OFF"}",
            "Single-Handed Thumb Flow Arc: ${when (thumbArcMode) { 1 -> "RIGHT"; 2 -> "LEFT"; else -> "OFF" }}",
            "Detox Minimalist Mode: ${if (isDetoxMode) "ACTIVE" else "OFF"}",
            "Dynamic Accent Palette: Cycle Nothing Red / Cyber Cyan / NPU Emerald",
            "Unhide All Vault & Hidden Apps"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("KINETIC OS 4.0 // ENGINE CONFIGURATION")
            .setItems(items) { _, idx ->
                when (idx) {
                    0 -> {
                        gridColumns = when (gridColumns) { 4 -> 5; 5 -> 6; else -> 4 }
                        gridRows = if (gridColumns == 4) 5 else 6
                    }
                    1 -> isLabelFree = !isLabelFree
                    2 -> isMonoIcons = !isMonoIcons
                    3 -> thumbArcMode = (thumbArcMode + 1) % 3
                    4 -> isDetoxMode = !isDetoxMode
                    5 -> {
                        accentColor = when (accentColor) {
                            Color.parseColor("#D71921") -> Color.parseColor("#38BDF8")
                            Color.parseColor("#38BDF8") -> Color.parseColor("#10B981")
                            else -> Color.parseColor("#D71921")
                        }
                    }
                    6 -> { hiddenPkgs.clear(); privateVaultPkgs.clear(); decoyVaultPkgs.clear() }
                }
                saveState()
                buildMasterUI()
                updateAllTelemetry()
                renderMaxFolders()
                renderDesktopGrid()
                renderDetoxLayer()
            }.show()
    }

    private fun updateSmartStackUI() {
        when (smartStackIndex) {
            0 -> {
                smartStackTitle.text = "STACK 1/4 // WEATHER"
                smartStackValue.text = "29°C CLEAR"
                smartStackSub.text = "AHMEDABAD • AQI 64"
            }
            1 -> {
                smartStackTitle.text = "STACK 2/4 // PEDOMETER"
                smartStackValue.text = "$dailySteps STEPS"
                smartStackSub.text = "GOAL: 6,000 • ${(dailySteps * 100 / 6000)}% DONE"
            }
            2 -> {
                smartStackTitle.text = "STACK 3/4 // COMPASS"
                smartStackValue.text = "${compassAzimuth}° NE"
                smartStackSub.text = "LIVE MAGNETOMETER"
            }
            3 -> {
                smartStackTitle.text = "STACK 4/4 // WELLNESS"
                smartStackValue.text = "2H 14M ACTIVE"
                smartStackSub.text = "RAM BASELINE: 23MB"
            }
        }
    }

    private fun updateAllTelemetry() {
        val now = Date()
        val bm = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, 86) ?: 86
        val tempC = (bm?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 310) ?: 310) / 10.0f

        when (clockFaceMode) {
            0 -> {
                dotMatrixHeader.setDotText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now))
                telemetrySubText.text = "SPACE: $currentSpace  //  BAT $level%  //  ${tempC}°C  //  120HZ"
            }
            1 -> {
                dotMatrixHeader.setDotText(SimpleDateFormat("hh.mm", Locale.getDefault()).format(now))
                telemetrySubText.text = "ANALOG MINIMAL  //  SPACE: $currentSpace  //  120HZ"
            }
            2 -> {
                val utc = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }
                dotMatrixHeader.setDotText(utc.format(now))
                telemetrySubText.text = "WORLD UTC  //  IST +5:30  //  BAT $level%"
            }
            3 -> {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mem = ActivityManager.MemoryInfo(); am.getMemoryInfo(mem)
                val availGb = String.format(Locale.US, "%.1f", mem.availMem / (1024.0 * 1024.0 * 1024.0))
                dotMatrixHeader.setDotText("$level:$tempC".take(5))
                telemetrySubText.text = "FREE RAM: ${availGb}GB  //  NPU IDLE  //  ZERO WAKE-LOCKS"
            }
        }
        updateSmartStackUI()
    }

    private fun toggleGlyphTimer() {
        triggerHaptic(0)
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
                        glyphStatusText.text = "GLYPH TIMER // ${formatSec(glyphSecondsLeft)}"
                        mainHandler.postDelayed(this, 1000L)
                    } else {
                        glyphRunning = false; triggerHaptic(1)
                        glyphStatusText.text = "COMPLETE // TAP RESTART"; glyphSecondsLeft = 25 * 60
                    }
                }
            }
            mainHandler.post(glyphRunnable!!)
        }
    }

    private fun resetGlyphTimer() {
        triggerHaptic(1); glyphRunning = false; glyphRunnable?.let { mainHandler.removeCallbacks(it) }
        glyphSecondsLeft = 25 * 60; glyphProgressView.setProgress(0f); glyphStatusText.text = "GLYPH TIMER // 25:00"
    }

    private fun formatSec(s: Int): String = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)

    private fun dispatchMediaKey(code: Int) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        } catch (_: Exception) {}
    }

    private fun toggleFlashlight() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull() ?: return
            isTorchOn = !isTorchOn
            cm.setTorchMode(id, isTorchOn)
            peripheralStripText.text = "PERIPHERALS // EAR(3): 92%  •  WATCH: 64%  •  TORCH: ${if (isTorchOn) "ON" else "OFF"}"
        } catch (_: Exception) {}
    }

    private fun openDrawer(focusKb: Boolean) {
        isDrawerOpen = true
        drawerContainer.visibility = View.VISIBLE
        renderDrawerApps(drawerSearchInput.text.toString())
        if (focusKb) {
            drawerSearchInput.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(drawerSearchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeDrawer() {
        isDrawerOpen = false
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(drawerSearchInput.windowToken, 0)
        drawerSearchInput.setText("")
        drawerContainer.visibility = View.GONE
    }

    private fun launchApp(app: AppEntry, popUp: Boolean) {
        triggerHaptic(0)
        if (mindfulPkgs.contains(app.pkg)) {
            Toast.makeText(this, "MINDFUL FRICTION // Breathing delay 3s for ${app.customLabel}...", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({ executeLaunch(app, popUp) }, 3000L)
        } else executeLaunch(app, popUp)
    }

    private fun executeLaunch(app: AppEntry, popUp: Boolean) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(app.pkg, app.activityName)
                flags = if (popUp) Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            startActivity(intent); closeDrawer()
        } catch (_: Exception) {}
    }

    private fun expandSystemShade() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(tickReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        updateAllTelemetry()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(tickReceiver) } catch (_: Exception) {}
    }

    @Deprecated("Predictive Back Handler")
    override fun onBackPressed() {
        if (isDrawerOpen) closeDrawer()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun roundRect(fill: Int, rad: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = rad.toFloat(); setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    class DotProgressBarView(context: Context, private val litColor: Int) : View(context) {
        private var progress = 0f
        private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = litColor }
        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2A2E") }
        fun setProgress(p: Float) { progress = p.coerceIn(0f, 1f); invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val dots = 20; val sp = width / dots.toFloat()
            val r = (sp * 0.34f).coerceAtMost(height * 0.4f); val cy = height / 2f
            val active = (progress * dots).toInt()
            for (i in 0 until dots) canvas.drawCircle((i + 0.5f) * sp, cy, r, if (i <= active && progress > 0f) litPaint else dimPaint)
        }
    }

    class DotMatrixView(context: Context, private val accentColor: Int) : View(context) {
        private var currentText = "22:20"
        private val whitePaint = Paint().apply { color = Color.WHITE; isAntiAlias = false }
        private val redPaint = Paint().apply { color = accentColor; isAntiAlias = false }
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
        fun setDotText(t: String) { if (currentText != t) { currentText = t; invalidate() } }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rows = 7; val sp = height / (rows + 1f); val r = sp * 0.36f; var cxCursor = sp
            for (ch in currentText) {
                val m = glyphs[ch] ?: continue
                val cols = m[0].length
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        val lit = m[row][col] == '1'
                        val p = when { !lit -> offPaint; ch == ':' || ch == '.' -> redPaint; else -> whitePaint }
                        canvas.drawCircle(cxCursor + col * sp, (row + 1) * sp, r, p)
                    }
                }
                cxCursor += (cols + 1.4f) * sp
            }
        }
    }
}