package com.exemple.bureauexterne

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.Display
import android.widget.Toast

object Wallpapers {
    val names = arrayOf(
        "Crépuscule",
        "Forêt",
        "Graphite",
        "Aurore (animé)",
        "Étoiles (animé)",
        "Lever de soleil (animé)"
    )
}

object Launcher {

    fun loadApps(context: Context): List<AppItem> {
        val pm = context.packageManager
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
    }

    /** Ouvre une appli sur l'écran externe, à la taille de l'écran si "fullscreen" est actif. */
    fun launch(context: Context, pkg: String, display: Display, reserveBottomPx: Int, fullscreen: Boolean) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeBasic()
        opts.setLaunchDisplayId(display.displayId)
        val m = DisplayMetrics()
        display.getRealMetrics(m)
        val rect = if (fullscreen) {
            Rect(0, 0, m.widthPixels, m.heightPixels - reserveBottomPx)
        } else {
            Rect(
                (m.widthPixels * 0.12).toInt(),
                (m.heightPixels * 0.06).toInt(),
                (m.widthPixels * 0.88).toInt(),
                (m.heightPixels * 0.84).toInt()
            )
        }
        opts.setLaunchBounds(rect)
        try {
            context.startActivity(intent, opts.toBundle())
        } catch (e: Exception) {
            Toast.makeText(context.applicationContext, "Impossible d'ouvrir l'appli : " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    /** Affiche le bureau (fond d'écran + icônes) sur l'écran externe. */
    fun launchDesktop(context: Context, display: Display) {
        val intent = Intent(context, DesktopActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val opts = ActivityOptions.makeBasic()
        opts.setLaunchDisplayId(display.displayId)
        val m = DisplayMetrics()
        display.getRealMetrics(m)
        opts.setLaunchBounds(Rect(0, 0, m.widthPixels, m.heightPixels))
        try {
            context.startActivity(intent, opts.toBundle())
        } catch (e: Exception) {
            Toast.makeText(context.applicationContext, "Impossible d'afficher le bureau : " + e.message, Toast.LENGTH_LONG).show()
        }
    }
}
