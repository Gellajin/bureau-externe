package com.exemple.bureauexterne

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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

        val swFull = Switch(this)
        swFull.text = "Plein écran sans bordure (taille de l'écran externe)"
        swFull.isChecked = prefs.getBoolean("fullscreen", true)
        swFull.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("fullscreen", on).apply()
        }
        swFull.setPadding(0, pad / 2, 0, pad / 2)

        val swAuto = Switch(this)
        swAuto.text = "Réduire la barre automatiquement quand je lance une appli"
        swAuto.isChecked = prefs.getBoolean("autoshrink", true)
        swAuto.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("autoshrink", on).apply()
        }
        swAuto.setPadding(0, pad / 2, 0, pad / 2)

        root.addView(title)
        root.addView(status)
        root.addView(info)
        root.addView(swFull)
        root.addView(swAuto)
        root.addView(makeButton("1. Autoriser l'affichage par-dessus les autres applis") { askOverlay() })
        root.addView(makeButton("2. Activer les boutons Retour / Récents (accessibilité)") {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Ouvre Réglages > Accessibilité", Toast.LENGTH_LONG).show()
            }
        })
        root.addView(makeButton("3. Démarrer la barre et le bureau") { startBar() })
        root.addView(makeButton("Arrêter la barre") {
            stopService(Intent(this, BarService::class.java))
            Toast.makeText(this, "Barre arrêtée", Toast.LENGTH_SHORT).show()
        })
        root.addView(makeButton("Fond d'écran du bureau") { wallpaperDialog() })
        root.addView(makeButton("Options développeur (pour supprimer les bandes noires)") {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Ouvre Réglages > Options pour les développeurs", Toast.LENGTH_LONG).show()
            }
        })

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { action() }
        return b
    }

    override fun onResume() {
        super.onResume()
        status.text = if (Settings.canDrawOverlays(this)) {
            "Autorisation accordée. Branche ton écran externe, puis appuie sur le bouton 3."
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

    // ---------- Fond d'écran ----------

    private fun wallpaperDialog() {
        val names = Wallpapers.names + "Image ou vidéo de mon téléphone…"
        AlertDialog.Builder(this)
            .setTitle("Fond d'écran du bureau")
            .setItems(names) { _, which ->
                if (which < Wallpapers.names.size) {
                    prefs.edit().putInt("wp", which).remove("wpUri").apply()
                    Toast.makeText(this, "Fond d'écran : " + Wallpapers.names[which], Toast.LENGTH_SHORT).show()
                } else {
                    pickMedia()
                }
            }
            .show()
    }

    private fun pickMedia() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        startActivityForResult(i, 42)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 42 && resultCode == RESULT_OK && uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            val type = contentResolver.getType(uri) ?: ""
            prefs.edit()
                .putInt("wp", -1)
                .putString("wpUri", uri.toString())
                .putString("wpType", if (type.startsWith("video")) "video" else "image")
                .apply()
            Toast.makeText(this, "Fond d'écran personnalisé enregistré", Toast.LENGTH_SHORT).show()
        }
    }
}
