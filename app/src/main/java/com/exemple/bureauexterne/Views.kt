package com.exemple.bureauexterne

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

data class AppItem(val label: String, val pkg: String, val icon: Drawable)

class DotsView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        paint.color = Color.WHITE
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
