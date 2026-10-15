package com.pratik.nothingcivi

import android.app.Activity
import android.content.*
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

data class AppItem(val label: String, val pkg: String, val cls: String, val icon: Drawable)

class MainActivity : Activity() {
    private val apps = mutableListOf<AppItem>()
    private val bg = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var clockText: TextView
    private lateinit var subText: TextView
    private lateinit var grid: GridView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.supportedModes?.maxByOrNull { it.refreshRate }?.let {
                window.attributes = window.attributes.apply { preferredDisplayModeId = it.modeId }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(18), dp(42), dp(18), dp(18))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#141414"))
                setStroke(dp(1), Color.parseColor("#262626"))
            }
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }

        clockText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 36f
            setTextColor(Color.WHITE)
        }
        subText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.parseColor("#D71921"))
        }
        card.addView(clockText)
        card.addView(subText)
        root.addView(card)

        val search = EditText(this).apply {
            hint = "ESSENTIAL SEARCH (AUTO-LAUNCH)..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#141414"))
                setStroke(dp(1), Color.parseColor("#262626"))
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(14)
            lp.bottomMargin = dp(14)
            layoutParams = lp
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { filterApps(s?.toString() ?: "") }
            })
        }
        root.addView(search)

        grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(12)
            horizontalSpacing = dp(10)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(grid)
        setContentView(root)
        updateTelemetry()
        loadApps()
    }

    private fun updateTelemetry() {
        val now = Date()
        clockText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val bm = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        val temp = (bm?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 330) ?: 330) / 10f
        subText.text = "NOTHING OS 4.0 // BAT " + lvl + "% // " + temp + "C // 120HZ"
    }

    private fun loadApps() {
        bg.execute {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val list = pm.queryIntentActivities(intent, 0).mapNotNull {
                val pkg = it.activityInfo.packageName
                if (pkg == packageName) null
                else AppItem(it.loadLabel(pm).toString(), pkg, it.activityInfo.name, it.loadIcon(pm))
            }.sortedBy { it.label.lowercase(Locale.getDefault()) }
            ui.post {
                apps.clear()
                apps.addAll(list)
                filterApps("")
            }
        }
    }

    private fun filterApps(q: String) {
        val clean = q.trim().lowercase(Locale.getDefault())
        val filtered = apps.filter { clean.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(clean) }
        if (clean.length >= 2 && filtered.size == 1) {
            launch(filtered.first())
            return
        }
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = filtered.size
            override fun getItem(p: Int) = filtered[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
                val item = filtered[p]
                return LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(Color.parseColor("#141414"))
                    }
                    addView(ImageView(this@MainActivity).apply {
                        setImageDrawable(item.icon)
                        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = item.label
                        typeface = Typeface.MONOSPACE
                        textSize = 9.5f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(Color.WHITE)
                    })
                    setOnClickListener { launch(item) }
                }
            }
        }
    }

    private fun launch(item: AppItem) {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(item.pkg, item.cls)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (_: Exception) {}
    }

    override fun onBackPressed() {}
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}