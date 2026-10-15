package com.pratik.nothingcivi

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.*
import android.content.pm.ApplicationInfo
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.AlarmClock
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.*
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

class MainActivity : Activity() {

    // KINETIC OS 4.0 (Nothing OS 4.0 "Extra Dark" Industrial Palette)
    private val COLOR_BG = Color.parseColor("#000000")
    private val COLOR_CARD = Color.parseColor("#121212")
    private val COLOR_CARD_ALT = Color.parseColor("#1A1A1A")
    private val COLOR_STROKE = Color.parseColor("#262626")
    private val COLOR_RED = Color.parseColor("#D71921")
    private val COLOR_WHITE = Color.parseColor("#FFFFFF")
    private val COLOR_MUTED = Color.parseColor("#7A7A7A")

    private lateinit var prefs: SharedPreferences
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val allApps = mutableListOf<AppEntry>()
    private val favoritePkgs = mutableListOf<String>()
    private val maxIconPkgs = mutableSetOf<String>()
    private val hiddenPkgs = mutableSetOf<String>()
    private val mindfulPkgs = mutableSetOf<String>() // 3-sec delay before opening
    private val customFolders = mutableMapOf<String, MutableList<String>>() // FolderName -> List of pkgs

    private var isMonoIcons = true
    private var isSmartDrawer = false
    private var isTorchOn = false
    private var isDrawerOpen = false
    private var isNoteExpanded = false
    private var clockFaceMode = 0 // 0 = Local Time, 1 = World UTC, 2 = Civi Hardware/RAM

    // Glyph Focus Pomodoro Timer State
    private var glyphTimerRunning = false
    private var glyphSecondsLeft = 25 * 60
    private var glyphRunnable: Runnable? = null

    // UI Views
    private lateinit var rootStack: FrameLayout
    private lateinit var homeContainer: LinearLayout
    private lateinit var drawerContainer: LinearLayout
    private lateinit var dotMatrixHeader: DotMatrixView
    private lateinit var telemetrySubText: TextView
    private lateinit var faceBadgeText: TextView
    private lateinit var glyphProgressView: DotProgressBarView
    private lateinit var glyphStatusText: TextView
    private lateinit var noteInput: EditText
    private lateinit var favGridLayout: GridLayout
    private lateinit var foldersRowLayout: LinearLayout
    private lateinit var dockLayout: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var calcResultBanner: TextView
    private lateinit var appsGridView: GridView
    private lateinit var drawerModeBtn: TextView
    private lateinit var monoPillBtn: TextView
    private lateinit var torchPillBtn: TextView
    private lateinit var ringerPillBtn: TextView

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
            updateHeaderWidget()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lock120HzDisplay()

        prefs = getSharedPreferences("kinetic_os4_prefs", Context.MODE_PRIVATE)
        loadSavedState()

        buildUserInterface()
        updateHeaderWidget()
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

    private fun loadSavedState() {
        isMonoIcons = prefs.getBoolean("mono_icons", true)
        isSmartDrawer = prefs.getBoolean("smart_drawer", false)
        clockFaceMode = prefs.getInt("clock_face", 0)

        maxIconPkgs.clear()
        maxIconPkgs.addAll(prefs.getStringSet("max_icons", emptySet()) ?: emptySet())
        hiddenPkgs.clear()
        hiddenPkgs.addAll(prefs.getStringSet("hidden_pkgs", emptySet()) ?: emptySet())
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
            .putBoolean("mono_icons", isMonoIcons)
            .putBoolean("smart_drawer", isSmartDrawer)
            .putInt("clock_face", clockFaceMode)
            .putStringSet("max_icons", maxIconPkgs)
            .putStringSet("hidden_pkgs", hiddenPkgs)
            .putStringSet("mindful_pkgs", mindfulPkgs)
            .putString("fav_list", favoritePkgs.joinToString(","))
            .putString("folders_data", folderSerialized)
            .apply()
    }

    private fun buildUserInterface() {
        rootStack = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(242, 0, 0, 0))
        }

        // ================= HOME SCREEN LAYER =================
        homeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(38), dp(18), dp(18))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Multi-Face Dot-Matrix Clock & Telemetry Card (Tap to switch faces, Long-press for Alarms)
        val clockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(24), COLOR_STROKE)
            setPadding(dp(18), dp(16), dp(18), dp(14))
            setOnClickListener {
                vibrateTick()
                clockFaceMode = (clockFaceMode + 1) % 3
                saveState()
                updateHeaderWidget()
            }
            setOnLongClickListener {
                vibrateTick()
                try { startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)) } catch (_: Exception) {}
                true
            }
        }

        val topHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        dotMatrixHeader = DotMatrixView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
        }

        faceBadgeText = TextView(this).apply {
            text = "KINETIC // 01"
            typeface = Typeface.MONOSPACE
            textSize = 9.5f
            setTextColor(COLOR_WHITE)
            background = roundRect(COLOR_RED, dp(10))
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }

        topHeaderRow.addView(dotMatrixHeader)
        topHeaderRow.addView(faceBadgeText)

        telemetrySubText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_MUTED)
            textSize = 10.5f
            setPadding(0, dp(8), 0, 0)
        }

        clockCard.addView(topHeaderRow)
        clockCard.addView(telemetrySubText)
        homeContainer.addView(clockCard)

        // 2. Glyph Focus Timer + Interactive Media Controller Strip (2-Column Bento Row)
        val bentoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        // Left Bento: Glyph Focus Timer (25m Pomodoro)
        val glyphCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(20), COLOR_STROKE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                rightMargin = dp(8)
            }
            setOnClickListener { toggleGlyphTimer() }
            setOnLongClickListener {
                resetGlyphTimer()
                true
            }
        }

        glyphStatusText = TextView(this).apply {
            text = "GLYPH FOCUS // 25:00"
            typeface = Typeface.MONOSPACE
            textSize = 9.5f
            setTextColor(COLOR_WHITE)
        }

        glyphProgressView = DotProgressBarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)).apply {
                topMargin = dp(6)
            }
        }

        glyphCard.addView(glyphStatusText)
        glyphCard.addView(glyphProgressView)

        // Right Bento: Universal Media Controller Pill
        val mediaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dp(20), COLOR_STROKE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
        }

        val mediaTitle = TextView(this).apply {
            text = "AUDIO // CONTROL"
            typeface = Typeface.MONOSPACE
            textSize = 9.5f
            setTextColor(COLOR_MUTED)
        }

        val mediaBtnsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(4)
            }
        }

        fun makeMediaBtn(symbol: String, keyCode: Int, isRed: Boolean = false): TextView {
            return TextView(this).apply {
                text = symbol
                typeface = Typeface.MONOSPACE
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (isRed) COLOR_RED else COLOR_WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    vibrateTick()
                    dispatchMediaKey(keyCode)
                }
            }
        }

        mediaBtnsRow.addView(makeMediaBtn("|<<", KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        mediaBtnsRow.addView(makeMediaBtn("PLAY", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, isRed = true))
        mediaBtnsRow.addView(makeMediaBtn(">>|", KeyEvent.KEYCODE_MEDIA_NEXT))

        mediaCard.addView(mediaTitle)
        mediaCard.addView(mediaBtnsRow)

        bentoRow.addView(glyphCard)
        bentoRow.addView(mediaCard)
        homeContainer.addView(bentoRow)

        // 3. Quick-Toggle Pills Row (Torch, Mono Icons, Ringer Mode, Essential Note)
        val pillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        torchPillBtn = createPill("TORCH", false) { toggleFlashlight() }
        monoPillBtn = createPill(if (isMonoIcons) "MONO: ON" else "MONO: OFF", isMonoIcons) {
            isMonoIcons = !isMonoIcons
            monoPillBtn.text = if (isMonoIcons) "MONO: ON" else "MONO: OFF"
            monoPillBtn.background = roundRect(if (isMonoIcons) COLOR_RED else COLOR_CARD, dp(18), COLOR_STROKE)
            saveState()
            renderHomeFavorites()
            renderAppDrawer(searchInput.text.toString())
        }
        ringerPillBtn = createPill(getRingerLabel(), false) { cycleRingerMode() }
        val notePillBtn = createPill("NOTE +", false) {
            isNoteExpanded = !isNoteExpanded
            noteInput.visibility = if (isNoteExpanded) View.VISIBLE else View.GONE
        }

        pillsRow.addView(torchPillBtn, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(6) })
        pillsRow.addView(monoPillBtn, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(6) })
        pillsRow.addView(ringerPillBtn, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(6) })
        pillsRow.addView(notePillBtn, LinearLayout.LayoutParams(0, dp(36), 1f))
        homeContainer.addView(pillsRow)

        // Collapsible Offline Essential Note Scratchpad
        noteInput = EditText(this).apply {
            hint = "ESSENTIAL NOTE // TYPE OFFLINE MEMO HERE..."
            setHintTextColor(COLOR_MUTED)
            setTextColor(COLOR_WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setText(prefs.getString("essential_note", ""))
            background = roundRect(COLOR_CARD_ALT, dp(18), COLOR_RED)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString("essential_note", s?.toString() ?: "").apply()
                }
            })
        }
        homeContainer.addView(noteInput)

        // 4. Nothing Max Folders Bar
        foldersRowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        homeContainer.addView(foldersRowLayout)

        // 5. Scrollable Home Grid (Supports 1x1 Standard & 2x2 Enlarged Max Icons)
        val favScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(6)
            }
        }
        favGridLayout = GridLayout(this).apply {
            columnCount = 4
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        favScroll.addView(favGridLayout)
        homeContainer.addView(favScroll)

        // 6. Essential Search Pill Trigger
        val searchPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(COLOR_CARD, dp(26), COLOR_STROKE)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setOnClickListener { openDrawer(focusKeyboard = true) }
        }

        val redDot = View(this).apply {
            background = roundRect(COLOR_RED, dp(5))
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(10) }
        }
        val searchLbl = TextView(this).apply {
            text = "ESSENTIAL SEARCH // APPS, MATH & WEB..."
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(COLOR_MUTED)
        }
        searchPill.addView(redDot)
        searchPill.addView(searchLbl)
        homeContainer.addView(searchPill)

        // 7. Bottom Dock
        dockLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundRect(COLOR_CARD, dp(24), COLOR_STROKE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68))
        }
        homeContainer.addView(dockLayout)

        // ================= APP DRAWER LAYER =================
        drawerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(16), dp(40), dp(16), dp(14))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val drawerTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        searchInput = EditText(this).apply {
            hint = "SEARCH APPS OR MATH (E.G. 45*18)..."
            setHintTextColor(COLOR_MUTED)
            setTextColor(COLOR_WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setSingleLine(true)
            background = roundRect(COLOR_CARD, dp(20), COLOR_STROKE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString()?.trim() ?: ""
                    evaluateMathOrSearch(q)
                    renderAppDrawer(q, autoLaunchIfSingle = q.length >= 2)
                }
            })
        }

        drawerModeBtn = TextView(this).apply {
            text = if (isSmartDrawer) "SMART" else "A-Z"
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(COLOR_WHITE)
            background = roundRect(if (isSmartDrawer) COLOR_RED else COLOR_CARD, dp(18), COLOR_STROKE)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42))
            setOnClickListener {
                vibrateTick()
                isSmartDrawer = !isSmartDrawer
                text = if (isSmartDrawer) "SMART" else "A-Z"
                background = roundRect(if (isSmartDrawer) COLOR_RED else COLOR_CARD, dp(18), COLOR_STROKE)
                saveState()
                renderAppDrawer(searchInput.text.toString())
            }
        }

        drawerTopRow.addView(searchInput)
        drawerTopRow.addView(drawerModeBtn)
        drawerContainer.addView(drawerTopRow)

        // Instant Calculator / Direct Web Search Banner inside Drawer
        calcResultBanner = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(COLOR_WHITE)
            background = roundRect(COLOR_CARD_ALT, dp(16), COLOR_RED)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        drawerContainer.addView(calcResultBanner)

        appsGridView = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(12)
            horizontalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        drawerContainer.addView(appsGridView)

        rootStack.addView(homeContainer)
        rootStack.addView(drawerContainer)

        // Gestures: Swipe Up -> Drawer, Swipe Down -> Notification Shade, Double-Tap -> Lock/Sleep Screen Info
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || isDrawerOpen) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > dp(60)) {
                    if (dy < 0) {
                        openDrawer(focusKeyboard = false)
                        return true
                    } else {
                        expandNotificationShade()
                        return true
                    }
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                vibrateTick()
                clockFaceMode = (clockFaceMode + 1) % 3
                updateHeaderWidget()
                return true
            }
        })

        homeContainer.setOnTouchListener { _, ev ->
            gestureDetector.onTouchEvent(ev)
            false
        }

        setContentView(rootStack)
    }

    private fun createPill(label: String, active: Boolean, onTap: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            typeface = Typeface.MONOSPACE
            textSize = 9.5f
            setTextColor(COLOR_WHITE)
            gravity = Gravity.CENTER
            background = roundRect(if (active) COLOR_RED else COLOR_CARD, dp(18), COLOR_STROKE)
            setOnClickListener {
                vibrateTick()
                onTap()
            }
        }
    }

    private fun loadInstalledAppsAsync() {
        bgExecutor.execute {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
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
                allApps.clear()
                allApps.addAll(loaded)

                if (favoritePkgs.isEmpty() && allApps.isNotEmpty()) {
                    allApps.take(6).forEach { favoritePkgs.add(it.pkg) }
                    maxIconPkgs.add(favoritePkgs.first())
                    saveState()
                }

                renderFoldersBar()
                renderHomeFavorites()
                renderAppDrawer(searchInput.text.toString())
            }
        }
    }

    private fun classifyCategory(appInfo: ApplicationInfo, label: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (appInfo.category) {
                ApplicationInfo.CATEGORY_GAME -> return "[ GAMES ]"
                ApplicationInfo.CATEGORY_SOCIAL -> return "[ SOCIAL ]"
                ApplicationInfo.CATEGORY_AUDIO,
                ApplicationInfo.CATEGORY_VIDEO,
                ApplicationInfo.CATEGORY_IMAGE -> return "[ MEDIA ]"
                ApplicationInfo.CATEGORY_PRODUCTIVITY,
                ApplicationInfo.CATEGORY_NEWS -> return "[ WORK ]"
            }
        }
        val l = label.lowercase(Locale.getDefault())
        return when {
            l.contains("whatsapp") || l.contains("instagram") || l.contains("telegram") || l.contains("phone") || l.contains("message") -> "[ SOCIAL ]"
            l.contains("youtube") || l.contains("spotify") || l.contains("camera") || l.contains("gallery") || l.contains("music") -> "[ MEDIA ]"
            else -> "[ SYSTEM & TOOLS ]"
        }
    }

    private fun renderFoldersBar() {
        foldersRowLayout.removeAllViews()
        for ((folderName, pkgList) in customFolders) {
            val pill = TextView(this).apply {
                text = ":: $folderName (${pkgList.size})"
                typeface = Typeface.MONOSPACE
                textSize = 10f
                setTextColor(COLOR_WHITE)
                background = roundRect(COLOR_CARD_ALT, dp(16), COLOR_STROKE)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8)
                }
                setOnClickListener {
                    vibrateTick()
                    showFolderPopup(folderName, pkgList)
                }
            }
            foldersRowLayout.addView(pill)
        }

        val addFolderBtn = TextView(this).apply {
            text = "+ FOLDER"
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(COLOR_RED)
            background = roundRect(COLOR_CARD, dp(16), COLOR_STROKE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                vibrateTick()
                promptCreateFolder()
            }
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
            .setItems(names) { _, idx -> launchApp(folderApps[idx]) }
            .setNegativeButton("Delete Folder") { _, _ ->
                customFolders.remove(folderName)
                saveState()
                renderFoldersBar()
            }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun promptCreateFolder() {
        val input = EditText(this).apply {
            hint = "FOLDER NAME (E.G. GAMING)"
            setTextColor(COLOR_WHITE)
            setHintTextColor(COLOR_MUTED)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("NEW NOTHING FOLDER")
            .setView(input)
            .setPositiveButton("CREATE") { _, _ ->
                val name = input.text.toString().trim().uppercase(Locale.getDefault())
                if (name.isNotEmpty()) {
                    customFolders[name] = mutableListOf()
                    saveState()
                    renderFoldersBar()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun renderHomeFavorites() {
        favGridLayout.removeAllViews()
        dockLayout.removeAllViews()

        val favApps = favoritePkgs.mapNotNull { pkg -> allApps.find { it.pkg == pkg } }
        val screenWidth = resources.displayMetrics.widthPixels - dp(36)
        val cellSize = screenWidth / 4

        for (app in favApps) {
            val isMax = maxIconPkgs.contains(app.pkg)
            val span = if (isMax) 2 else 1

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = roundRect(COLOR_CARD, dp(22), if (isMax) COLOR_RED else COLOR_STROKE)
                setPadding(dp(6), dp(8), dp(6), dp(8))

                val params = GridLayout.LayoutParams().apply {
                    width = cellSize * span - dp(10)
                    height = if (isMax) cellSize * 2 - dp(14) else cellSize + dp(4)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, span)
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                }
                layoutParams = params

                setOnClickListener { launchApp(app) }
                setOnLongClickListener {
                    vibrateTick()
                    showAppOptionsDialog(app, isPinned = true)
                    true
                }
            }

            val iconView = ImageView(this).apply {
                setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                colorFilter = if (isMonoIcons) monoFilter else null
                val s = if (isMax) dp(68) else dp(40)
                layoutParams = LinearLayout.LayoutParams(s, s)
            }

            val lblView = TextView(this).apply {
                text = app.customLabel.uppercase(Locale.getDefault())
                typeface = Typeface.MONOSPACE
                textSize = if (isMax) 11.5f else 9f
                setTextColor(COLOR_WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
            }

            card.addView(iconView)
            card.addView(lblView)
            favGridLayout.addView(card)
        }

        // Bottom Dock (First 4 Pinned Apps + Red Matrix All-Apps Trigger)
        for (app in favApps.take(4)) {
            val dockIcon = ImageView(this).apply {
                setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                colorFilter = if (isMonoIcons) monoFilter else null
                setPadding(dp(9), dp(9), dp(9), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
                setOnClickListener { launchApp(app) }
            }
            dockLayout.addView(dockIcon)
        }

        val drawerBtn = TextView(this).apply {
            text = ":::"
            typeface = Typeface.MONOSPACE
            textSize = 18f
            setTextColor(COLOR_RED)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            setOnClickListener { openDrawer(focusKeyboard = false) }
        }
        dockLayout.addView(drawerBtn)
    }

    private fun evaluateMathOrSearch(query: String) {
        if (query.isEmpty()) {
            calcResultBanner.visibility = View.GONE
            return
        }
        // Check if query is a simple math expression (e.g., 45*18, 120+35, 100/4)
        val mathRegex = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*([+\-*/%])\s*(-?\d+(?:\.\d+)?)\s*$""")
        val match = mathRegex.matchEntire(query)
        if (match != null) {
            val a = match.groupValues[1].toDoubleOrNull() ?: 0.0
            val op = match.groupValues[2]
            val b = match.groupValues[3].toDoubleOrNull() ?: 0.0
            val res = when (op) {
                "+" -> a + b
                "-" -> a - b
                "*" -> a * b
                "/" -> if (b != 0.0) a / b else Double.NaN
                "%" -> a % b
                else -> 0.0
            }
            val formatted = if (res % 1.0 == 0.0) res.toLong().toString() else String.format(Locale.US, "%.2f", res)
            calcResultBanner.text = "CALC RESULT // = $formatted  (TAP TO COPY)"
            calcResultBanner.visibility = View.VISIBLE
            calcResultBanner.setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("calc", formatted))
                Toast.makeText(this, "Copied $formatted", Toast.LENGTH_SHORT).show()
            }
        } else {
            calcResultBanner.text = "WEB SEARCH // TAP TO SEARCH \"$query\" ON GOOGLE"
            calcResultBanner.visibility = View.VISIBLE
            calcResultBanner.setOnClickListener {
                val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                closeDrawer()
            }
        }
    }

    private fun renderAppDrawer(query: String, autoLaunchIfSingle: Boolean = false) {
        val clean = query.lowercase(Locale.getDefault())
        val filtered = allApps.filter { app ->
            !hiddenPkgs.contains(app.pkg) &&
                (clean.isEmpty() || app.customLabel.lowercase(Locale.getDefault()).contains(clean))
        }.let { list ->
            if (isSmartDrawer && clean.isEmpty()) {
                list.sortedWith(compareBy({ it.category }, { it.customLabel.lowercase(Locale.getDefault()) }))
            } else list
        }

        if (autoLaunchIfSingle && filtered.size == 1) {
            searchInput.setText("")
            closeDrawer()
            launchApp(filtered.first())
            return
        }

        appsGridView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = filtered.size
            override fun getItem(position: Int): Any = filtered[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val app = filtered[position]
                val cell = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    background = roundRect(COLOR_CARD, dp(18), COLOR_STROKE)
                }

                if (isSmartDrawer && clean.isEmpty()) {
                    val badge = TextView(this@MainActivity).apply {
                        text = app.category
                        typeface = Typeface.MONOSPACE
                        textSize = 7.5f
                        setTextColor(COLOR_RED)
                        maxLines = 1
                    }
                    cell.addView(badge)
                }

                val img = ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                    colorFilter = if (isMonoIcons) monoFilter else null
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { topMargin = dp(4) }
                }

                val lbl = TextView(this@MainActivity).apply {
                    text = app.customLabel
                    typeface = Typeface.MONOSPACE
                    textSize = 9.5f
                    setTextColor(COLOR_WHITE)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(0, dp(5), 0, 0)
                }

                cell.addView(img)
                cell.addView(lbl)
                cell.setOnClickListener { launchApp(app) }
                cell.setOnLongClickListener {
                    vibrateTick()
                    showAppOptionsDialog(app, isPinned = favoritePkgs.contains(app.pkg))
                    true
                }
                return cell
            }
        }
    }

    private fun showAppOptionsDialog(app: AppEntry, isPinned: Boolean) {
        val isMax = maxIconPkgs.contains(app.pkg)
        val isMindful = mindfulPkgs.contains(app.pkg)
        val options = mutableListOf<String>()

        if (isPinned) {
            options.add(if (isMax) "Shrink to 1x1 Icon" else "Enlarge to 2x2 Max Icon")
            options.add("Unpin from Home Screen")
        } else {
            options.add("Pin to Home Screen")
            options.add("Pin as 2x2 Max Icon")
        }
        options.add("Rename App Label")
        for (folderName in customFolders.keys) {
            options.add("Add to Folder: $folderName")
        }
        options.add(if (isMindful) "Disable 3-Sec Mindful Delay" else "Enable 3-Sec Mindful Delay")
        options.add("Hide App from Drawer")
        options.add("System App Info")
        options.add("Uninstall App")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.customLabel.uppercase(Locale.getDefault()))
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                when {
                    choice.startsWith("Enlarge") -> maxIconPkgs.add(app.pkg)
                    choice.startsWith("Shrink") -> maxIconPkgs.remove(app.pkg)
                    choice.startsWith("Unpin") -> {
                        favoritePkgs.remove(app.pkg)
                        maxIconPkgs.remove(app.pkg)
                    }
                    choice == "Pin to Home Screen" -> {
                        if (!favoritePkgs.contains(app.pkg)) favoritePkgs.add(app.pkg)
                    }
                    choice == "Pin as 2x2 Max Icon" -> {
                        if (!favoritePkgs.contains(app.pkg)) favoritePkgs.add(app.pkg)
                        maxIconPkgs.add(app.pkg)
                    }
                    choice == "Rename App Label" -> {
                        promptRenameApp(app)
                        return@setItems
                    }
                    choice.startsWith("Add to Folder: ") -> {
                        val fName = choice.removePrefix("Add to Folder: ")
                        val list = customFolders[fName]
                        if (list != null && !list.contains(app.pkg)) list.add(app.pkg)
                        renderFoldersBar()
                    }
                    choice.startsWith("Enable 3-Sec") -> mindfulPkgs.add(app.pkg)
                    choice.startsWith("Disable 3-Sec") -> mindfulPkgs.remove(app.pkg)
                    choice.startsWith("Hide") -> {
                        hiddenPkgs.add(app.pkg)
                        favoritePkgs.remove(app.pkg)
                    }
                    choice == "System App Info" -> {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", app.pkg, null)
                        })
                    }
                    choice == "Uninstall App" -> {
                        startActivity(Intent(Intent.ACTION_DELETE).apply {
                            data = Uri.fromParts("package", app.pkg, null)
                        })
                    }
                }
                saveState()
                renderHomeFavorites()
                renderAppDrawer(searchInput.text.toString())
            }
            .setNegativeButton("Unhide All Apps") { _, _ ->
                hiddenPkgs.clear()
                saveState()
                renderAppDrawer(searchInput.text.toString())
            }
            .show()
    }

    private fun promptRenameApp(app: AppEntry) {
        val input = EditText(this).apply {
            setText(app.customLabel)
            setTextColor(COLOR_WHITE)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("RENAME // ${app.defaultLabel}")
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val newName = input.text.toString().trim().ifEmpty { app.defaultLabel }
                app.customLabel = newName
                prefs.edit().putString("rename_${app.pkg}", newName).apply()
                renderHomeFavorites()
                renderAppDrawer(searchInput.text.toString())
            }
            .setNeutralButton("RESET") { _, _ ->
                app.customLabel = app.defaultLabel
                prefs.edit().remove("rename_${app.pkg}").apply()
                renderHomeFavorites()
                renderAppDrawer(searchInput.text.toString())
            }
            .show()
    }

    private fun updateHeaderWidget() {
        val now = Date()
        val bm = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val tempC = (bm?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f

        when (clockFaceMode) {
            0 -> {
                // Face 1: Local Time + Date + Battery + Civi Temp
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                val dateStr = SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(now).uppercase(Locale.getDefault())
                dotMatrixHeader.setDotText(timeStr)
                faceBadgeText.text = "CLOCK // 01"
                telemetrySubText.text = "$dateStr  //  BAT $level%  //  $tempC°C  //  120HZ"
            }
            1 -> {
                // Face 2: UTC World Clock
                val utcFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                dotMatrixHeader.setDotText(utcFmt.format(now))
                faceBadgeText.text = "UTC // 02"
                telemetrySubText.text = "WORLD STANDARD TIME (UTC)  //  TAP FOR CIVI RAM"
            }
            2 -> {
                // Face 3: Civi 14 Live RAM & Hardware Monitor
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val availGb = String.format(Locale.US, "%.1f", memInfo.availMem / (1024.0 * 1024.0 * 1024.0))
                val totalGb = String.format(Locale.US, "%.0f", memInfo.totalMem / (1024.0 * 1024.0 * 1024.0))
                dotMatrixHeader.setDotText("$level:$tempC".take(5))
                faceBadgeText.text = "CIVI // 03"
                telemetrySubText.text = "FREE RAM: ${availGb}GB / ${totalGb}GB  //  CORES: ${Runtime.getRuntime().availableProcessors()}  //  $tempC°C"
            }
        }
    }

    private fun toggleGlyphTimer() {
        vibrateTick()
        if (glyphTimerRunning) {
            glyphTimerRunning = false
            glyphRunnable?.let { mainHandler.removeCallbacks(it) }
            glyphStatusText.text = "PAUSED // ${formatSec(glyphSecondsLeft)} (HOLD TO RESET)"
        } else {
            glyphTimerRunning = true
            glyphRunnable = object : Runnable {
                override fun run() {
                    if (!glyphTimerRunning) return
                    if (glyphSecondsLeft > 0) {
                        glyphSecondsLeft--
                        val progress = 1f - (glyphSecondsLeft / (25f * 60f))
                        glyphProgressView.setProgress(progress)
                        glyphStatusText.text = "GLYPH FOCUS // ${formatSec(glyphSecondsLeft)}"
                        mainHandler.postDelayed(this, 1000L)
                    } else {
                        glyphTimerRunning = false
                        vibrateTick()
                        glyphStatusText.text = "FOCUS COMPLETE // TAP TO RESTART"
                        glyphSecondsLeft = 25 * 60
                    }
                }
            }
            mainHandler.post(glyphRunnable!!)
        }
    }

    private fun resetGlyphTimer() {
        vibrateTick()
        glyphTimerRunning = false
        glyphRunnable?.let { mainHandler.removeCallbacks(it) }
        glyphSecondsLeft = 25 * 60
        glyphProgressView.setProgress(0f)
        glyphStatusText.text = "GLYPH FOCUS // 25:00"
    }

    private fun formatSec(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

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
        return when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "RING: MUTE"
            AudioManager.RINGER_MODE_VIBRATE -> "RING: VIB"
            else -> "RING: LOUD"
        }
    }

    private fun cycleRingerMode() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_NORMAL
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            ringerPillBtn.text = getRingerLabel()
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
        }
    }

    private fun toggleFlashlight() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull() ?: return
            isTorchOn = !isTorchOn
            cm.setTorchMode(id, isTorchOn)
            torchPillBtn.text = if (isTorchOn) "TORCH: ON" else "TORCH"
            torchPillBtn.background = roundRect(if (isTorchOn) COLOR_RED else COLOR_CARD, dp(18), COLOR_STROKE)
        } catch (_: Exception) {}
    }

    private fun openDrawer(focusKeyboard: Boolean) {
        isDrawerOpen = true
        drawerContainer.visibility = View.VISIBLE
        if (focusKeyboard) {
            searchInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeDrawer() {
        isDrawerOpen = false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.setText("")
        calcResultBanner.visibility = View.GONE
        drawerContainer.visibility = View.GONE
    }

    private fun launchApp(app: AppEntry) {
        vibrateTick()
        if (mindfulPkgs.contains(app.pkg)) {
            Toast.makeText(this, "MINDFUL BREATHER // Opening ${app.customLabel} in 3s...", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({ executeLaunchIntent(app) }, 3000L)
        } else {
            executeLaunchIntent(app)
        }
    }

    private fun executeLaunchIntent(app: AppEntry) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(app.pkg, app.activityName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            startActivity(intent)
            closeDrawer()
        } catch (_: Exception) {}
    }

    private fun expandNotificationShade() {
        try {
            val service = getSystemService("statusbar")
            val clazz = Class.forName("android.app.StatusBarManager")
            clazz.getMethod("expandNotificationsPanel").invoke(service)
        } catch (_: Exception) {}
    }

    private fun vibrateTick() {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(tickReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        updateHeaderWidget()
        ringerPillBtn.text = getRingerLabel()
        loadInstalledAppsAsync()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(tickReceiver) } catch (_: Exception) {}
    }

    @Deprecated("Launcher Home Back Handler")
    override fun onBackPressed() {
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

    // ================= DOT-MATRIX GLYPH PROGRESS BAR =================
    class DotProgressBarView(context: Context) : View(context) {
        private var progress = 0f
        private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D71921") }
        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2A2A") }

        fun setProgress(p: Float) {
            progress = p.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val totalDots = 20
            val spacing = width / totalDots.toFloat()
            val r = (spacing * 0.34f).coerceAtMost(height * 0.4f)
            val cy = height / 2f
            val activeDots = (progress * totalDots).toInt()

            for (i in 0 until totalDots) {
                val cx = (i + 0.5f) * spacing
                canvas.drawCircle(cx, cy, r, if (i <= activeDots && progress > 0f) litPaint else dimPaint)
            }
        }
    }

    // ================= PROCEDURAL 5x7 DOT-MATRIX RENDERER =================
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
                        val cx = cursorX + col * dotSpacing
                        val cy = (row + 1) * dotSpacing
                        val lit = matrix[row][col] == '1'
                        val p = when {
                            !lit -> offPaint
                            ch == ':' || ch == '.' -> redPaint
                            else -> whitePaint
                        }
                        canvas.drawCircle(cx, cy, r, p)
                    }
                }
                cursorX += (cols + 1.4f) * dotSpacing
            }
        }
    }
}
