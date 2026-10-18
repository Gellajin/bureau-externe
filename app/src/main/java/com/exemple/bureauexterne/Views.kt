package com.exemple.bureauexterne

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

data class AppItem(val label: String, val pkg: String, val icon: Drawable)

/** Le bouton aux neuf points. */
class DotsView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w * 0.07f
        for (i in 0..2) {
            for (j in 0..2) {
                canvas.drawCircle(w * (0.3f + 0.2f * i), h * (0.3f + 0.2f * j), r, paint)
            }
        }
    }
}

/** Boutons : 0 = retour, 1 = accueil, 2 = récents, 3 = réduire la barre, 4 = mode petit écran, 5 = mode plein écran. */
class NavIconView(c: Context, private val kind: Int) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.06f
        paint.strokeJoin = Paint.Join.ROUND
        if (kind == 0) {
            path.reset()
            path.moveTo(w * 0.65f, h * 0.28f)
            path.lineTo(w * 0.32f, h * 0.5f)
            path.lineTo(w * 0.65f, h * 0.72f)
            path.close()
            canvas.drawPath(path, paint)
        } else if (kind == 1) {
            canvas.drawCircle(w * 0.5f, h * 0.5f, w * 0.2f, paint)
        } else if (kind == 2) {
            canvas.drawRoundRect(w * 0.3f, h * 0.3f, w * 0.7f, h * 0.7f, w * 0.06f, w * 0.06f, paint)
        } else if (kind == 4) {
            // mode petit écran : une fenêtre avec sa barre de titre
            canvas.drawRoundRect(w * 0.24f, h * 0.3f, w * 0.76f, h * 0.7f, w * 0.05f, w * 0.05f, paint)
            canvas.drawLine(w * 0.24f, h * 0.4f, w * 0.76f, h * 0.4f, paint)
        } else if (kind == 5) {
            // mode plein écran : quatre coins
            path.reset()
            path.moveTo(w * 0.3f, h * 0.42f)
            path.lineTo(w * 0.3f, h * 0.3f)
            path.lineTo(w * 0.42f, h * 0.3f)
            path.moveTo(w * 0.58f, h * 0.3f)
            path.lineTo(w * 0.7f, h * 0.3f)
            path.lineTo(w * 0.7f, h * 0.42f)
            path.moveTo(w * 0.7f, h * 0.58f)
            path.lineTo(w * 0.7f, h * 0.7f)
            path.lineTo(w * 0.58f, h * 0.7f)
            path.moveTo(w * 0.42f, h * 0.7f)
            path.lineTo(w * 0.3f, h * 0.7f)
            path.lineTo(w * 0.3f, h * 0.58f)
            canvas.drawPath(path, paint)
        } else {
            path.reset()
            path.moveTo(w * 0.3f, h * 0.4f)
            path.lineTo(w * 0.5f, h * 0.6f)
            path.lineTo(w * 0.7f, h * 0.4f)
            canvas.drawPath(path, paint)
        }
    }
}

/** Fond d'écran animé : ciel étoilé qui scintille. */
class StarsView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = java.util.Random(7)
    private val xs = FloatArray(160) { rnd.nextFloat() }
    private val ys = FloatArray(160) { rnd.nextFloat() }
    private val ph = FloatArray(160) { rnd.nextFloat() * 6.28f }
    private val sz = FloatArray(160) { 0.6f + rnd.nextFloat() * 1.8f }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#070A1A"))
        paint.style = Paint.Style.FILL
        val t = SystemClock.uptimeMillis() / 1000f
        val d = resources.displayMetrics.density
        for (i in xs.indices) {
            val s = Math.sin((t * 1.2f + ph[i]).toDouble()).toFloat()
            val a = 0.35f + 0.65f * (0.5f + 0.5f * s)
            paint.color = Color.argb((a * 255).toInt(), 255, 255, 255)
            canvas.drawCircle(xs[i] * width, ys[i] * height, sz[i] * d, paint)
        }
        postInvalidateOnAnimation()
    }
}

/** Une case du tiroir ou du bureau : icône + nom. */
class AppAdapter(private val c: Context, private val items: List<AppItem>) : BaseAdapter() {
    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val d = c.resources.displayMetrics.density
        val app = items[position]

        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())

        val img = ImageView(c)
        img.setImageDrawable(app.icon)
        box.addView(img, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))

        val tv = TextView(c)
        tv.text = app.label
        tv.setTextColor(Color.WHITE)
        tv.textSize = 12f
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.gravity = Gravity.CENTER
        box.addView(tv)

        return box
    }
}
