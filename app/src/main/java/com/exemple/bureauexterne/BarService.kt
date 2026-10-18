package com.exemple.bureauexterne

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.Toast

class BarService : Service() {

    private lateinit var dm: DisplayManager
    private val handler = Handler(Looper.getMainLooper())

    private var ctx: Context? = null
    private var wm: WindowManager? = null
    private var barView: View? = null
    private var drawerView: View? = null
    private var displayId = -1
    private var curDisplay: Display? = null
    private var lastClose = 0L

    private val prefs by lazy { getSharedPreferences("bureau", MODE_PRIVATE) }

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {
            handler.postDelayed({ sync() }, 500)
        }

        override fun onDisplayRemoved(id: Int) {
            sync()
        }

        override fun onDisplayChanged(id: Int) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(listener, handler)
        sync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try {
            dm.unregisterDisplayListener(listener)
        } catch (e: Exception) {
        }
        removeAll()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("bar", "Bureau externe", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "bar")
            .setContentTitle("Bureau externe actif")
            .setContentText("La barre s'affiche sur l'écran externe")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
        startForeground(1, n)
    }

    private fun sync() {
        val d = dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (d == null) {
            removeAll()
            displayId = -1
            curDisplay = null
            return
        }
        if (d.displayId == displayId && barView != null) return
        removeAll()
        displayId = d.displayId
        curDisplay = d
        val base = createDisplayContext(d)
        val c = ContextThemeWrapper(base, android.R.style.Theme_Material)
        ctx = c
        wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        drawBar()
    }

    private fun removeAll() {
        closeDrawer()
        val v = barView
        if (v != null) {
            try {
                wm?.removeView(v)
            } catch (e: Exception) {
            }
        }
        barView = null
    }

    private fun pins(): MutableSet<String> =
        prefs.getStringSet("pins", emptySet())!!.toMutableSet()

    private fun togglePin(pkg: String): Boolean {
        val s = pins()
        val added: Boolean
        if (s.contains(pkg)) {
            s.remove(pkg)
            added = false
        } else {
            s.add(pkg)
            added = true
        }
        prefs.edit().putStringSet("pins", s).apply()
        return added
    }

    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
    }

    private fun toast(s: String) {
        Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()
    }

    private fun launch(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeBasic()
        opts.setLaunchDisplayId(displayId)

        val disp = curDisplay
        if (disp != null) {
            val m = DisplayMetrics()
            disp.getRealMetrics(m)
            val density = ctx?.resources?.displayMetrics?.density ?: 1f
            val barPx = (52 * density).toInt()
            val full = prefs.getBoolean("fullscreen", true)
            val rect = if (full) {
                Rect(0, 0, m.widthPixels, m.heightPixels - barPx)
            } else {
                Rect(
                    (m.widthPixels * 0.12).toInt(),
                    (m.heightPixels * 0.06).toInt(),
                    (m.widthPixels * 0.88).toInt(),
                    (m.heightPixels * 0.84).toInt()
                )
            }
            opts.setLaunchBounds(rect)
        }
        try {
            startActivity(intent, opts.toBundle())
        } catch (e: Exception) {
            toast("Impossible d'ouvrir l'appli : " + e.message)
        }
    }

    private fun drawBar() {
        val c = ctx ?: return
        val w = wm ?: return
        val old = barView
        if (old != null) {
            try {
                w.removeView(old)
            } catch (e: Exception) {
            }
            barView = null
        }
        val d = c.resources.displayMetrics.density
        val view = buildBar(c, d)
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (52 * d).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM
        try {
            w.addView(view, lp)
            barView = view
        } catch (e: Exception) {
            toast("Impossible d'afficher la barre : " + e.message)
        }
    }

    private fun buildBar(c: Context, d: Float): View {
        fun dp(v: Int): Int = (v * d).toInt()

        val bar = LinearLayout(c)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor("#E6141820"))
        bar.setPadding(dp(12), 0, dp(12), 0)

        val btn = FrameLayout(c)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#33FFFFFF"))
        btn.background = bg
        btn.addView(DotsView(c), FrameLayout.LayoutParams(dp(40), dp(40)))
        btn.setOnClickListener { toggleDrawer() }
        val btnLp = LinearLayout.LayoutParams(dp(40), dp(40))
        btnLp.rightMargin = dp(14)
        bar.addView(btn, btnLp)

        val pm = packageManager
        for (pkg in pins()) {
            try {
                val iv = ImageView(c)
                iv.setImageDrawable(pm.getApplicationIcon(pkg))
                iv.setOnClickListener { launch(pkg) }
                iv.setOnLongClickListener {
                    togglePin(pkg)
                    drawBar()
                    true
                }
                val lp = LinearLayout.LayoutParams(dp(36), dp(36))
                lp.rightMargin = dp(10)
                bar.addView(iv, lp)
            } catch (e: Exception) {
            }
        }

        bar.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))

        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.END
        val t = TextClock(c)
        t.format24Hour = "HH:mm"
        t.format12Hour = "HH:mm"
        t.setTextColor(Color.WHITE)
        t.textSize = 14f
        val dt = TextClock(c)
        dt.format24Hour = "dd/MM"
        dt.format12Hour = "dd/MM"
        dt.setTextColor(Color.parseColor("#B0FFFFFF"))
        dt.textSize = 11f
        col.addView(t)
        col.addView(dt)
        bar.addView(col)

        return bar
    }

    private fun toggleDrawer() {
        if (drawerView != null) {
            closeDrawer()
        } else if (SystemClock.uptimeMillis() - lastClose > 300) {
            openDrawer()
        }
    }

    private fun closeDrawer() {
        val v = drawerView
        if (v != null) {
            try {
                wm?.removeView(v)
            } catch (e: Exception) {
            }
        }
        drawerView = null
    }

    private fun openDrawer() {
        val c = ctx ?: return
        val w = wm ?: return
        val d = c.resources.displayMetrics.density
        val m = c.resources.displayMetrics
        fun dp(v: Int): Int = (v * d).toInt()

        val apps = loadApps()

        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F2141820"))
        bg.cornerRadius = dp(16).toFloat()
        box.background = bg
        box.setPadding(dp(16), dp(16), dp(16), dp(16))
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                lastClose = SystemClock.uptimeMillis()
                closeDrawer()
                true
            } else {
                false
            }
        }

        val grid = GridView(c)
        grid.numColumns = 6
        grid.verticalSpacing = dp(8)
        grid.adapter = AppAdapter(c, apps)
        grid.setOnItemClickListener { _, _, pos, _ ->
            closeDrawer()
            launch(apps[pos].pkg)
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            val added = togglePin(apps[pos].pkg)
            toast(if (added) "Épinglé à la barre" else "Détaché de la barre")
            drawBar()
            true
        }
        box.addView(
            grid,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val lp = WindowManager.LayoutParams(
            minOf(m.widthPixels - dp(48), dp(900)),
            (m.heightPixels * 0.6).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = dp(52) + dp(8)
        try {
            w.addView(box, lp)
            drawerView = box
        } catch (e: Exception) {
            toast("Impossible d'ouvrir le tiroir : " + e.message)
        }
    }
}
