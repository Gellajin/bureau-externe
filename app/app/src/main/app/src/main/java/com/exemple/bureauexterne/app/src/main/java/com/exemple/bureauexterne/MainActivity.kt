package com.exemple.bureauexterne

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var info: TextView
    private val prefs by lazy { getSharedPreferences("bureau", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Bureau externe"
        title.textSize = 26f

        status = TextView(this)
        status.textSize = 16f
        status.setPadding(0, pad / 2, 0, pad / 2)

        info = TextView(this)
        info.textSize = 14f
        info.setPadding(0, 0, 0, pad / 2)

        val sw = Switch(this)
        sw.text = "Plein écran sans bordure (taille de l'écran externe)"
        sw.isChecked = prefs.getBoolean("fullscreen", true)
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("fullscreen", on).apply()
        }
        sw.setPadding(0, pad / 2, 0, pad / 2)

        val bPerm = Button(this)
        bPerm.text = "1. Autoriser l'affichage par-dessus les autres applis"
        bPerm.setOnClickListener { askOverlay() }

        val bStart = Button(this)
        bStart.text = "2. Démarrer la barre"
        bStart.setOnClickListener { startBar() }

        val bStop = Button(this)
        bStop.text = "Arrêter la barre"
        bStop.setOnClickListener {
            stopService(Intent(this, BarService::class.java))
            Toast.makeText(this, "Barre arrêtée", Toast.LENGTH_SHORT).show()
        }

        val bDev = Button(this)
        bDev.text = "Options développeur (pour supprimer les bandes noires)"
        bDev.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Ouvre Réglages > Options pour les développeurs", Toast.LENGTH_LONG).show()
            }
        }

        root.addView(title)
        root.addView(status)
        root.addView(info)
        root.addView(sw)
        root.addView(bPerm)
        root.addView(bStart)
        root.addView(bStop)
        root.addView(bDev)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        status.text = if (Settings.canDrawOverlays(this)) {
            "Autorisation accordée. Branche ton écran externe, puis appuie sur le bouton 2."
        } else {
            "Autorisation manquante : appuie sur le bouton 1 et active-la pour cette appli."
        }
        info.text = displayInfo()
    }

    private fun displayInfo(): String {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val ext = dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
        if (ext.isEmpty()) return "Aucun écran externe détecté."
        val sb = StringBuilder()
        for (d in ext) {
            val m = DisplayMetrics()
            d.getRealMetrics(m)
            sb.append("Écran externe : ")
            sb.append(m.widthPixels)
            sb.append(" × ")
            sb.append(m.heightPixels)
            sb.append(" px, ")
            sb.append(m.densityDpi)
            sb.append(" dpi\n")
        }
        return sb.toString().trim()
    }

    private fun askOverlay() {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(i)
    }

    private fun startBar() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorise d'abord l'affichage par-dessus les autres applis", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, BarService::class.java))
        Toast.makeText(this, "Barre démarrée", Toast.LENGTH_SHORT).show()
    }
}
