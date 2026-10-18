package com.exemple.bureauexterne

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView

/** Le bureau affiché sur l'écran externe : fond d'écran + icônes de raccourcis. */
class DesktopActivity : Activity() {

    private lateinit var wallLayer: FrameLayout
    private lateinit var grid: GridView
    private lateinit var hint: TextView
    private lateinit var anchor: View
    private var animator: ValueAnimator? = null
    private var lastX = 0f
    private var lastY = 0f
    private val prefs by lazy { getSharedPreferences("bureau", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Si le système a ouvert le bureau sur le téléphone, on le referme et on réessaie.
        if (windowManager.defaultDisplay.displayId == Display.DEFAULT_DISPLAY) {
            Toast.makeText(this, "Le bureau doit s'ouvrir sur l'écran externe", Toast.LENGTH_SHORT).show()
            BarService.instance?.retryDesktop()
            finish()
            return
        }

        val d = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = FrameLayout(this)

        wallLayer = FrameLayout(this)
        root.addView(wallLayer, FrameLayout.LayoutParams(match, match))

        grid = GridView(this)
        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = dp(104)
        grid.verticalSpacing = dp(12)
        grid.stretchMode = GridView.NO_STRETCH
        grid.setPadding(dp(24), dp(24), dp(24), dp(80))
        grid.clipToPadding = false
        grid.setOnLongClickListener {
            showMenu()
            true
        }
        root.addView(grid, FrameLayout.LayoutParams(match, match))

        hint = TextView(this)
        hint.text = "Appui long ou clic droit sur le bureau pour ajouter des applis"
        hint.setTextColor(Color.parseColor("#B0FFFFFF"))
        hint.textSize = 16f
        hint.gravity = Gravity.CENTER
        root.addView(hint, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))

        // Point d'ancrage invisible pour les petits menus
        anchor = View(this)
        root.addView(anchor, FrameLayout.LayoutParams(1, 1))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!::wallLayer.isInitialized) return
        renderWallpaper()
        renderIcons()
    }

    override fun onPause() {
        super.onPause()
        animator?.cancel()
        animator = null
    }

    // ---------- Clic droit à la souris et touche F5 ----------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            lastX = ev.x
            lastY = ev.y
            if ((ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                showMenu()
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_F5) {
            refresh()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- Petits menus ----------

    private fun popup(labels: List<String>, onPick: (Int) -> Unit) {
        anchor.x = lastX
        anchor.y = lastY
        val pm = PopupMenu(this, anchor)
        for (i in labels.indices) {
            pm.menu.add(0, i, i, labels[i])
        }
        pm.setOnMenuItemClickListener { item ->
            onPick(item.itemId)
            true
        }
        pm.show()
    }

    private fun showMenu() {
        val labels = listOf(
            "Actualiser",
            "Changer le fond d'écran",
            "Ajouter une appli au bureau",
            "Retirer une appli du bureau",
            "Afficher la barre des tâches"
        )
        popup(labels) { which ->
            when (which) {
                0 -> refresh()
                1 -> anchor.post { wallpaperMenu() }
                2 -> anchor.post { addMenu() }
                3 -> anchor.post { removeMenu() }
                else -> BarService.instance?.expand()
            }
        }
    }

    private fun wallpaperMenu() {
        popup(Wallpapers.names.toList()) { which ->
            prefs.edit().putInt("wp", which).remove("wpUri").apply()
            renderWallpaper()
        }
    }

    private fun addMenu() {
        val all = Launcher.loadApps(this)
        popup(all.map { it.label }) { which ->
            val s = prefs.getStringSet("desk", emptySet<String>())!!.toMutableSet()
            s.add(all[which].pkg)
            prefs.edit().putStringSet("desk", s).apply()
            renderIcons()
        }
    }

    private fun removeMenu() {
        val chosen = prefs.getStringSet("desk", emptySet<String>()) ?: emptySet<String>()
        val list = Launcher.loadApps(this).filter { chosen.contains(it.pkg) }
        if (list.isEmpty()) {
            Toast.makeText(this, "Aucune appli sur le bureau", Toast.LENGTH_SHORT).show()
            return
        }
        popup(list.map { it.label }) { which ->
            val s = prefs.getStringSet("desk", emptySet<String>())!!.toMutableSet()
            s.remove(list[which].pkg)
            prefs.edit().putStringSet("desk", s).apply()
            renderIcons()
        }
    }

    private fun itemMenu(app: AppItem) {
        popup(listOf("Ouvrir", "Retirer du bureau")) { which ->
            if (which == 0) {
                openApp(app.pkg)
            } else {
                val s = prefs.getStringSet("desk", emptySet<String>())!!.toMutableSet()
                s.remove(app.pkg)
                prefs.edit().putStringSet("desk", s).apply()
                renderIcons()
            }
        }
    }

    // ---------- Fond d'écran ----------

    private fun col(s: String): Int = Color.parseColor(s)

    private fun renderWallpaper() {
        animator?.cancel()
        animator = null
        wallLayer.removeAllViews()
        wallLayer.background = null

        val idx = prefs.getInt("wp", 0)
        val uriStr = prefs.getString("wpUri", null)
        if (idx < 0 && uriStr != null) {
            showCustom(Uri.parse(uriStr), prefs.getString("wpType", "image") ?: "image")
            return
        }
        when (idx) {
            1 -> wallLayer.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(col("#0C3B3A"), col("#0F5A4F"), col("#0A2A2C"))
            )
            2 -> wallLayer.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(col("#2B2F38"), col("#1B1E25"))
            )
            3 -> animateGradient(
                intArrayOf(col("#0B1F3A"), col("#0F6B5C"), col("#5B2A86")),
                intArrayOf(col("#5B2A86"), col("#0B1F3A"), col("#0F6B5C"))
            )
            4 -> wallLayer.addView(
                StarsView(this),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            5 -> animateGradient(
                intArrayOf(col("#2B1B5A"), col("#FF5E7E"), col("#FF9A5A")),
                intArrayOf(col("#1B1040"), col("#FF8A5A"), col("#FFD27A"))
            )
            else -> wallLayer.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(col("#1B2347"), col("#2A2F5E"), col("#1A1D3A"))
            )
        }
    }

    private fun animateGradient(a: IntArray, b: IntArray) {
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, a.copyOf())
        wallLayer.background = g
        val ev = ArgbEvaluator()
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = 9000
        va.repeatCount = ValueAnimator.INFINITE
        va.repeatMode = ValueAnimator.REVERSE
        va.addUpdateListener { an ->
            val f = an.animatedValue as Float
            val cols = IntArray(a.size) { i -> ev.evaluate(f, a[i], b[i]) as Int }
            g.setColors(cols)
        }
        va.start()
        animator = va
    }

    private fun showCustom(uri: Uri, type: String) {
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        wallLayer.setBackgroundColor(Color.BLACK)
        try {
            if (type == "video") {
                val v = VideoView(this)
                v.setVideoURI(uri)
                v.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    mp.setVolume(0f, 0f)
                    v.start()
                }
                wallLayer.addView(v, params)
            } else {
                val iv = ImageView(this)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setImageURI(uri)
                wallLayer.addView(iv, params)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible de lire ce fond d'écran", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Icônes du bureau ----------

    private fun renderIcons() {
        val all = Launcher.loadApps(this)
        val chosen = prefs.getStringSet("desk", emptySet<String>()) ?: emptySet<String>()
        val list = all.filter { chosen.contains(it.pkg) }
        grid.adapter = AppAdapter(this, list)
        grid.setOnItemClickListener { _, _, pos, _ -> openApp(list[pos].pkg) }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            itemMenu(list[pos])
            true
        }
        hint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openApp(pkg: String) {
        val disp = windowManager.defaultDisplay
        val auto = prefs.getBoolean("autoshrink", true)
        val reserve = if (auto) 0 else (52 * resources.displayMetrics.density).toInt()
        Launcher.launch(this, pkg, disp, reserve, prefs.getBoolean("fullscreen", true))
        BarService.instance?.onAppLaunched()
    }

    private fun refresh() {
        renderWallpaper()
        renderIcons()
        Toast.makeText(this, "Bureau actualisé", Toast.LENGTH_SHORT).show()
    }
}
