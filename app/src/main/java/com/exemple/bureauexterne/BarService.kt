package com.exemple.bureauexterne

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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

/**
 * Barre des tâches sur l'écran externe (HDMI).
 * - bouton à neuf points : tiroir de toutes les applis (appui long sur une appli : l'épingler)
 * - boutons Retour, Accueil (affiche le bureau), Récents, Réduire
 * - la barre se réduit toute seule en petit bouton quand on lance une appli
 */
class BarService : Service() {

    companion object {
        var instance: BarService? = null
    }

    private lateinit var dm: DisplayManager
    private val handler = Handler(Looper.getMainLooper())

    private var ctx: Context? = null
    private var wm: WindowManager? = null
    private var barView: View? = null
    private var drawerView: View? = null
    private var displayId = -1
    private var curDisplay: Display? = null
    private var lastClose = 0L
    private var collapsed = false
    private var desktopTries = 0

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
        instance = this
        startInForeground()
        dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(listener, handler)
        sync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        try {
            dm.unregisterDisplayListener(listener)
        } catch (e: Exception) {
        }
        removeAll()
        super.onDestroy()
    }

    // ---------- Notification obligatoire pour un service qui reste actif ----------

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

    // ---------- Détection de l'écran externe ----------

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
        collapsed = false
        desktopTries = 0
        val base = createDisplayContext(d)
        val c = ContextThemeWrapper(base, android.R.style.Theme_Material)
        ctx = c
        wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        drawBar()
        handler.postDelayed({ launchDesktop() }, 400)
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

    private fun launchDesktop() {
        val disp = curDisplay ?: return
        Launcher.launchDesktop(this, disp, false)
    }

    /** Appelé par le bureau s'il s'est ouvert sur le téléphone au lieu de l'écran externe. */
    fun retryDesktop() {
        if (desktopTries >= 2) return
        desktopTries++
        handler.postDelayed({
            val disp = curDisplay
            if (disp != null) {
                Launcher.launchDesktop(this, disp, true)
            }
        }, 400)
    }

    private fun isFullscreen(): Boolean = prefs.getBoolean("fullscreen", true)

    private fun toggleMode() {
        val full = !isFullscreen()
        prefs.edit().putBoolean("fullscreen", full).apply()
        toast(if (full) "Mode plein écran" else "Mode petit écran (fenêtres)")
        drawBar()
    }

    // ---------- Applis épinglées ----------

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

    private fun toast(s: String) {
        Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()
    }

    // ---------- Lancer une appli ----------

    private fun launch(pkg: String) {
        val disp = curDisplay ?: return
        val auto = prefs.getBoolean("autoshrink", true)
        val density = ctx?.resources?.displayMetrics?.density ?: 1f
        val reserve = if (auto) 0 else (52 * density).toInt()
        Launcher.launch(this, pkg, disp, reserve, isFullscreen())
        onAppLaunched()
    }

    /** Appelé quand une appli vient d'être lancée : la barre se réduit si l'option est active. */
    fun onAppLaunched() {
        closeDrawer()
        if (prefs.getBoolean("autoshrink", true) && !collapsed) {
            collapsed = true
            drawBar()
        }
    }

    /** Réaffiche la barre complète. */
    fun expand() {
        if (collapsed) {
            collapsed = false
            drawBar()
        }
    }

    private fun navAction(action: Int) {
        val s = NavService.instance
        if (s == null) {
            toast("Active le service d'accessibilité Bureau externe dans les Réglages")
            return
        }
        s.performGlobalAction(action)
    }

    // ---------- La barre ----------

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
        val view: View
        val lp: WindowManager.LayoutParams
        if (collapsed && isFullscreen()) {
            // Plein écran : aucune icône. Un bord invisible en bas réaffiche la barre.
            view = buildHotZone(c)
            lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (8 * d).toInt(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.BOTTOM
        } else if (collapsed) {
            // Mode petit écran : un petit bouton rond
            view = buildCollapsed(c, d)
            lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.BOTTOM or Gravity.START
            lp.x = (8 * d).toInt()
            lp.y = (8 * d).toInt()
        } else {
            view = buildBar(c, d)
            lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (52 * d).toInt(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.BOTTOM
        }
        try {
            w.addView(view, lp)
            barView = view
        } catch (e: Exception) {
            toast("Impossible d'afficher la barre : " + e.message)
        }
    }

    /** La barre réduite : juste un petit bouton rond, un appui la réaffiche. */
    private fun buildCollapsed(c: Context, d: Float): View {
        fun dp(v: Int): Int = (v * d).toInt()
        val btn = FrameLayout(c)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#E6141820"))
        btn.background = bg
        btn.addView(DotsView(c), FrameLayout.LayoutParams(dp(48), dp(48)))
        btn.setOnClickListener { expand() }
        return btn
    }

    /** Bande invisible en bas de l'écran : la souris (ou un doigt) dessus réaffiche la barre. */
    private fun buildHotZone(c: Context): View {
        val v = View(c)
        v.setOnHoverListener { _, e ->
            if (e.action == MotionEvent.ACTION_HOVER_ENTER) {
                expand()
                true
            } else {
                false
            }
        }
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                expand()
                true
            } else {
                false
            }
        }
        return v
    }

    private fun buildBar(c: Context, d: Float): View {
        fun dp(v: Int): Int = (v * d).toInt()

        val bar = LinearLayout(c)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor("#E6141820"))
        bar.setPadding(dp(12), 0, dp(12), 0)

        // Bouton des applications
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

        // Applis épinglées
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

        // Espace
        bar.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))

        // Boutons : mode (petit écran / plein écran), retour, accueil, récents, réduire
        val kinds = intArrayOf(if (isFullscreen()) 5 else 4, 0, 1, 2, 3)
        for (k in kinds) {
            val v = NavIconView(c, k)
            v.setOnClickListener {
                if (k == 4 || k == 5) {
                    toggleMode()
                } else if (k == 0) {
                    navAction(AccessibilityService.GLOBAL_ACTION_BACK)
                } else if (k == 1) {
                    expand()
                    launchDesktop()
                } else if (k == 2) {
                    navAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                } else {
                    collapsed = true
                    drawBar()
                }
            }
            val lp = LinearLayout.LayoutParams(dp(40), dp(40))
            lp.leftMargin = dp(2)
            bar.addView(v, lp)
        }

        // Heure et date
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
        val colLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        colLp.leftMargin = dp(14)
        bar.addView(col, colLp)

        return bar
    }

    // ---------- Le tiroir d'applications ----------

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

        val apps = Launcher.loadApps(this)

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
