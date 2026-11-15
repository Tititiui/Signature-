package com.example.signature

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ───────────────────────── 基础工具 ─────────────────────────

private val IOS_BLUE = Color.rgb(0, 122, 255)
private val IOS_RED = Color.rgb(255, 59, 48)
private val IOS_GRAY = Color.rgb(142, 142, 147)
private val IOS_INK = Color.rgb(28, 28, 30)
private val IOS_BAR = Color.rgb(242, 242, 247)
private val IOS_LINE = Color.rgb(209, 209, 214)

private fun View.dp(v: Float): Float = v * resources.displayMetrics.density
private fun View.dpi(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
private fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
private fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

// ───────────────────────── 全局状态 ─────────────────────────
// 折叠 / 展开屏幕时 Activity 会重建，所以笔迹和设置放在这里，不会丢。

object AppState {
    val strokes = ArrayList<Stroke>()
    var colorIndex = 0                              // 0-4 常用色，5 = 调色板自选色
    var customColor = Color.rgb(175, 82, 222)
    var penWidthDp = 3f
    var eraserWidthDp = 20f
    var eraserOn = false
    val presetColors = intArrayOf(
        Color.BLACK, Color.rgb(255, 204, 0), Color.rgb(255, 59, 48),
        Color.rgb(52, 199, 89), Color.rgb(0, 122, 255)
    )

    fun currentColor(): Int =
        if (colorIndex < presetColors.size) presetColors[colorIndex] else customColor
}

/** 一笔。坐标单位是 dp（与屏幕密度无关），保存时可以按任意倍数矢量放大。 */
class Stroke(val kind: Int, val color: Int, val widthDp: Float) {
    val pts = ArrayList<PointF>()

    companion object {
        const val PEN = 0
        const val ERASER = 1
        const val CLEAR = 2   // “清空”也记成一步，所以清空之后还能后退找回
    }
}

// ───────────────────────── 笔迹绘制（屏幕显示和高清导出共用） ─────────────────────────

/** 用“中点二次贝塞尔”把采样点连成平滑曲线，消除折线毛刺。 */
fun buildPath(pts: List<PointF>, s: Float): Path {
    val path = Path()
    val n = pts.size
    path.moveTo(pts[0].x * s, pts[0].y * s)
    if (n == 2) {
        path.lineTo(pts[1].x * s, pts[1].y * s)
    } else if (n > 2) {
        path.lineTo((pts[0].x + pts[1].x) / 2f * s, (pts[0].y + pts[1].y) / 2f * s)
        for (i in 1 until n - 1) {
            val mx = (pts[i].x + pts[i + 1].x) / 2f * s
            val my = (pts[i].y + pts[i + 1].y) / 2f * s
            path.quadTo(pts[i].x * s, pts[i].y * s, mx, my)
        }
        path.lineTo(pts[n - 1].x * s, pts[n - 1].y * s)
    }
    return path
}

/** live=true 表示在屏幕上实时预览：橡皮擦用白色盖上去（画布本来就是白的，效果一样）。 */
fun renderStroke(c: Canvas, st: Stroke, s: Float, live: Boolean) {
    if (st.kind == Stroke.CLEAR) {
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        return
    }
    if (st.pts.isEmpty()) return
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    if (st.kind == Stroke.ERASER) {
        if (live) p.color = Color.WHITE else p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    } else {
        p.color = st.color
    }
    p.strokeCap = Paint.Cap.ROUND
    p.strokeJoin = Paint.Join.ROUND
    p.strokeWidth = st.widthDp * s
    if (st.pts.size == 1) {
        p.style = Paint.Style.FILL
        c.drawCircle(st.pts[0].x * s, st.pts[0].y * s, st.widthDp * s / 2f, p)
    } else {
        p.style = Paint.Style.STROKE
        c.drawPath(buildPath(st.pts, s), p)
    }
}

// ───────────────────────── 画布 ─────────────────────────

class SignatureView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private var bitmap: Bitmap? = null
    private var bmpCanvas: Canvas? = null
    private var current: Stroke? = null
    private var drawing = false
    private var inMenu = false
    private var pointerId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var cursorX = 0f
    private var cursorY = 0f
    private val smooth = 0.6f          // 手抖滤波强度：越小越平滑但跟手感越慢
    private val minStepDp = 0.4f       // 小于这个距离的抖动点直接丢掉

    var onChanged: (() -> Unit)? = null
    var menuForwarder: ((Float, Float, Int) -> Unit)? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(150, 120, 120, 128)
        strokeWidth = density
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        bitmap?.recycle()
        bitmap = null
        bmpCanvas = null
        if (w > 0 && h > 0) {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = b
            bmpCanvas = Canvas(b)
            rebuild()
            // 防止在画布边缘书写时触发系统“返回”手势
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }
    }

    fun visibleStrokes(): List<Stroke> {
        val list = AppState.strokes
        val start = list.indexOfLast { it.kind == Stroke.CLEAR } + 1
        return ArrayList(list.subList(start, list.size))
    }

    private fun rebuild() {
        val c = bmpCanvas ?: return
        bitmap?.eraseColor(Color.TRANSPARENT)
        for (st in visibleStrokes()) renderStroke(c, st, density, false)
        invalidate()
    }

    fun undo() {
        if (drawing || AppState.strokes.isEmpty()) return
        AppState.strokes.removeAt(AppState.strokes.size - 1)
        rebuild()
        onChanged?.invoke()
    }

    fun clearAll() {
        if (drawing || visibleStrokes().isEmpty()) return
        AppState.strokes.add(Stroke(Stroke.CLEAR, 0, 0f))
        rebuild()
        onChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        current?.let { renderStroke(canvas, it, density, true) }
        if (AppState.eraserOn && drawing) {
            canvas.drawCircle(cursorX, cursorY, AppState.eraserWidthDp * density / 2f, ringPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pointerId = e.getPointerId(0)
                handlePoint(e.x, e.y, false)
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(pointerId)
                if (idx >= 0) {
                    // 把系统合并掉的历史采样点也用上，笔迹更细腻
                    for (h in 0 until e.historySize) {
                        handlePoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), false)
                    }
                    handlePoint(e.getX(idx), e.getY(idx), false)
                    if (inMenu) forward(e.getX(idx), e.getY(idx), MotionEvent.ACTION_MOVE)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                var idx = e.findPointerIndex(pointerId)
                if (idx < 0) idx = 0
                val x = e.getX(idx)
                val y = e.getY(idx)
                handlePoint(x, y, true)
                if (inMenu) {
                    forward(x, y, MotionEvent.ACTION_UP)
                    inMenu = false
                }
                if (drawing) endStroke()
                pointerId = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (drawing) endStroke()
                if (inMenu) {
                    forward(0f, 0f, MotionEvent.ACTION_CANCEL)
                    inMenu = false
                }
                pointerId = -1
                invalidate()
            }
        }
        return true
    }

    private fun forward(x: Float, y: Float, action: Int) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        menuForwarder?.invoke(loc[0] + x, loc[1] + y, action)
    }

    private fun handlePoint(x: Float, y: Float, final: Boolean) {
        // 橡皮擦滑出画布下沿 → 进入菜单栏，变成“菜单选择工具”
        if (AppState.eraserOn && y > height) {
            if (drawing) endStroke()
            inMenu = true
            return
        }
        if (inMenu) {
            inMenu = false
            menuForwarder?.invoke(0f, 0f, MotionEvent.ACTION_CANCEL)
        }
        val inside = x >= 0f && y >= 0f && x <= width && y <= height
        if (!inside) {
            if (drawing) endStroke()
            return
        }
        if (!drawing) startStroke(x, y) else extendStroke(x, y, final)
        cursorX = x
        cursorY = y
    }

    private fun startStroke(x: Float, y: Float) {
        val st = if (AppState.eraserOn) {
            Stroke(Stroke.ERASER, 0, AppState.eraserWidthDp)
        } else {
            Stroke(Stroke.PEN, AppState.currentColor(), AppState.penWidthDp)
        }
        st.pts.add(PointF(x / density, y / density))
        lastX = x
        lastY = y
        current = st
        drawing = true
    }

    private fun extendStroke(x: Float, y: Float, final: Boolean) {
        val st = current ?: return
        val k = if (st.kind == Stroke.ERASER || final) 1f else smooth
        val nx = lastX + (x - lastX) * k
        val ny = lastY + (y - lastY) * k
        val minStep = if (final) 0.2f else minStepDp * density
        if (hypot(nx - lastX, ny - lastY) < minStep) return
        lastX = nx
        lastY = ny
        st.pts.add(PointF(nx / density, ny / density))
    }

    private fun endStroke() {
        val st = current ?: return
        current = null
        drawing = false
        AppState.strokes.add(st)
        bmpCanvas?.let { renderStroke(it, st, density, false) }
        onChanged?.invoke()
        invalidate()
    }
}

// ───────────────────────── 高清透明 PNG 导出 ─────────────────────────

/** 返回给用户看的提示文字。在后台线程里调用。 */
fun exportSignature(ctx: Context, strokes: List<Stroke>): String {
    var l = Float.MAX_VALUE
    var t = Float.MAX_VALUE
    var r = -Float.MAX_VALUE
    var b = -Float.MAX_VALUE
    for (st in strokes) {
        if (st.kind != Stroke.PEN) continue
        val hw = st.widthDp / 2f
        for (p in st.pts) {
            l = min(l, p.x - hw); t = min(t, p.y - hw)
            r = max(r, p.x + hw); b = max(b, p.y + hw)
        }
    }
    if (l > r) return "画布是空的，没有可保存的笔迹"

    val margin = 6f
    l -= margin; t -= margin; r += margin; b += margin
    val wDp = r - l
    val hDp = b - t

    // 每 dp 渲染成多少像素：最高 16（约为手机屏幕的 6 倍），并受内存上限约束
    var s = 16f
    s = min(s, 8000f / max(wDp, hDp))
    s = min(s, sqrt(16_000_000f / (wDp * hDp)))
    val w = max(1, ceil(wDp * s).toInt())
    val h = max(1, ceil(hDp * s).toInt())

    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.translate(-l * s, -t * s)
    for (st in strokes) renderStroke(c, st, s, false)

    // 按实际有墨迹的像素裁掉多余透明边（橡皮擦可能擦掉了一部分）
    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1
    val row = IntArray(w)
    for (y in 0 until h) {
        bmp.getPixels(row, 0, w, 0, y, w, 1)
        var first = -1
        var last = -1
        for (x in 0 until w) {
            if ((row[x] ushr 24) != 0) {
                if (first < 0) first = x
                last = x
            }
        }
        if (first >= 0) {
            if (y < minY) minY = y
            maxY = y
            if (first < minX) minX = first
            if (last > maxX) maxX = last
        }
    }
    if (maxX < 0) {
        bmp.recycle()
        return "笔迹已被全部擦除，没有可保存的内容"
    }
    val pad = (s * 3f).toInt()
    val x0 = max(0, minX - pad)
    val y0 = max(0, minY - pad)
    val x1 = min(w, maxX + 1 + pad)
    val y1 = min(h, maxY + 1 + pad)
    val out = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
    if (out !== bmp) bmp.recycle()

    val name = "signature_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Signature")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = ctx.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) {
        out.recycle()
        return "保存失败：无法创建文件"
    }
    val dims = "${out.width}×${out.height}"
    try {
        val ok = resolver.openOutputStream(uri)?.use { os ->
            out.compress(Bitmap.CompressFormat.PNG, 100, os)
        } ?: false
        if (!ok) {
            resolver.delete(uri, null, null)
            return "保存失败：写入出错"
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        return "保存失败：" + e.message
    } finally {
        out.recycle()
    }
    return "已保存到 图库 → Signature 相册（$dims 像素，透明背景）"
}

// ───────────────────────── 菜单栏控件（iOS 风格） ─────────────────────────

open class MenuItemView(ctx: Context) : View(ctx) {
    var hovering = false
    open fun showHover(h: Boolean) {
        if (hovering != h) {
            hovering = h
            invalidate()
        }
    }
    protected val dim: Boolean get() = isPressed || hovering
}

/** 自动换行的菜单容器：宽屏（展开屏）排一行，窄屏（外屏）自动排两行。 */
class FlowLayout(ctx: Context) : ViewGroup(ctx) {
    private val gapH = dpi(6f)
    private val gapV = dpi(2f)

    fun add(v: View, wDp: Float, hDp: Float) {
        addView(v, ViewGroup.LayoutParams(dpi(wDp), dpi(hDp)))
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val maxW = MeasureSpec.getSize(ws) - paddingLeft - paddingRight
        var x = 0
        var rowH = 0
        var total = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val lp = v.layoutParams
            v.measure(
                MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
            )
            if (x > 0 && x + gapH + lp.width > maxW) {
                total += rowH + gapV
                x = 0
                rowH = 0
            }
            x += (if (x > 0) gapH else 0) + lp.width
            rowH = max(rowH, lp.height)
        }
        total += rowH
        setMeasuredDimension(MeasureSpec.getSize(ws), total + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = (r - l) - paddingLeft - paddingRight
        var i = 0
        var y = paddingTop
        while (i < childCount) {
            var j = i
            var rowW = 0
            var rowH = 0
            while (j < childCount) {
                val lp = getChildAt(j).layoutParams
                val need = (if (j > i) gapH else 0) + lp.width
                if (j > i && rowW + need > maxW) break
                rowW += need
                rowH = max(rowH, lp.height)
                j++
            }
            var x = paddingLeft + (maxW - rowW) / 2
            for (k in i until j) {
                val v = getChildAt(k)
                val top = y + (rowH - v.measuredHeight) / 2
                v.layout(x, top, x + v.measuredWidth, top + v.measuredHeight)
                x += v.measuredWidth + gapH
            }
            y += rowH + gapV
            i = j
        }
    }
}

class Slider(
    ctx: Context,
    private val minV: Float,
    private val maxV: Float,
    var value: Float,
    private val onChange: (Float) -> Unit
) : MenuItemView(ctx) {
    var accent: Int = IOS_BLUE
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun setFromLocalX(x: Float) {
        val pad = dp(14f)
        val f = ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        value = minV + f * (maxV - minV)
        onChange(value)
        invalidate()
    }

    fun setFromScreenX(sx: Float) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        setFromLocalX(sx - loc[0])
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                setFromLocalX(e.x)
            }
            MotionEvent.ACTION_MOVE -> setFromLocalX(e.x)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val l = dp(14f)
        val r = width - dp(14f)
        val f = ((value - minV) / (maxV - minV)).coerceIn(0f, 1f)
        val x = l + (r - l) * f
        val th = dp(2f)
        p.style = Paint.Style.FILL
        p.color = IOS_LINE
        canvas.drawRoundRect(RectF(l, cy - th, r, cy + th), th, th, p)
        p.color = accent
        canvas.drawRoundRect(RectF(l, cy - th, x, cy + th), th, th, p)
        p.color = Color.WHITE
        p.setShadowLayer(dp(3f), 0f, dp(1f), Color.argb(70, 0, 0, 0))
        canvas.drawCircle(x, cy, dp(11f), p)
        p.clearShadowLayer()
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(0.5f)
        p.color = Color.argb(45, 0, 0, 0)
        canvas.drawCircle(x, cy, dp(11f), p)
    }
}

class ColorDot(ctx: Context, private val index: Int) : MenuItemView(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rainbow: Shader? = null

    init {
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        rainbow = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(
                Color.rgb(255, 59, 48), Color.rgb(255, 204, 0), Color.rgb(52, 199, 89),
                Color.rgb(90, 200, 250), Color.rgb(0, 122, 255), Color.rgb(175, 82, 222),
                Color.rgb(255, 59, 48)
            ),
            null
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val sel = AppState.colorIndex == index
        val col = if (index < 5) AppState.presetColors[index] else AppState.customColor
        val a = if (dim) 120 else 255
        val r = dp(9.5f)
        p.style = Paint.Style.FILL
        if (index < 5) {
            p.color = col
            p.alpha = a
            canvas.drawCircle(cx, cy, r, p)
        } else {
            p.shader = rainbow
            p.alpha = a
            canvas.drawCircle(cx, cy, r, p)
            p.shader = null
            p.color = if (sel) col else Color.WHITE
            canvas.drawCircle(cx, cy, dp(4.5f), p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(0.6f)
        p.color = Color.argb(50, 0, 0, 0)
        canvas.drawCircle(cx, cy, r, p)
        if (sel) {
            p.strokeWidth = dp(1.8f)
            p.color = col
            canvas.drawCircle(cx, cy, dp(13f), p)
        }
    }
}

class IconButton(ctx: Context, private val kind: Int) : MenuItemView(ctx) {
    companion object {
        const val ERASER = 0
        const val UNDO = 1
        const val CLEAR = 2
        const val SAVE = 3
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
    }

    private fun tint(color: Int, k: Float): Int =
        Color.argb((255 * k).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val press = if (dim) 0.45f else 1f
        p.style = Paint.Style.FILL
        when (kind) {
            ERASER -> {
                val on = AppState.eraserOn
                if (on) {
                    p.color = tint(IOS_BLUE, press)
                    canvas.drawRoundRect(
                        RectF(dp(2f), dp(3f), width - dp(2f), height - dp(3f)), dp(9f), dp(9f), p
                    )
                }
                val col = tint(if (on) Color.WHITE else IOS_INK, press)
                canvas.save()
                canvas.rotate(-45f, cx, cy)
                val rw = dp(10f)
                val rh = dp(6f)
                val mid = cx - rw * 0.1f
                p.color = col
                canvas.drawRoundRect(RectF(cx - rw, cy - rh, mid, cy + rh), dp(2.5f), dp(2.5f), p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(1.8f)
                canvas.drawRoundRect(RectF(cx - rw, cy - rh, cx + rw, cy + rh), dp(2.5f), dp(2.5f), p)
                canvas.restore()
            }
            UNDO -> {
                val k = (if (AppState.strokes.isEmpty()) 0.3f else 1f) * press
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(2f)
                p.strokeCap = Paint.Cap.ROUND
                p.strokeJoin = Paint.Join.ROUND
                p.color = tint(IOS_INK, k)
                val rr = dp(4.5f)
                val tipX = cx - dp(8f)
                val tipY = cy - dp(2.5f)
                val path = Path()
                path.moveTo(tipX, tipY)
                path.lineTo(cx + dp(3f), tipY)
                path.arcTo(RectF(cx + dp(3f) - rr, tipY, cx + dp(3f) + rr, tipY + 2 * rr), -90f, 180f)
                path.lineTo(cx - dp(3f), tipY + 2 * rr)
                path.moveTo(tipX + dp(4f), tipY - dp(4f))
                path.lineTo(tipX, tipY)
                path.lineTo(tipX + dp(4f), tipY + dp(4f))
                canvas.drawPath(path, p)
            }
            CLEAR -> {
                p.color = tint(IOS_RED, press)
                p.textAlign = Paint.Align.CENTER
                p.textSize = dp(15f)
                canvas.drawText("清空", cx, cy - (p.descent() + p.ascent()) / 2f, p)
            }
            SAVE -> {
                p.color = tint(IOS_BLUE, press)
                canvas.drawRoundRect(
                    RectF(dp(2f), dp(5f), width - dp(2f), height - dp(5f)), dp(14f), dp(14f), p
                )
                p.color = Color.WHITE
                p.textAlign = Paint.Align.CENTER
                p.textSize = dp(15f)
                p.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText("保存", cx, cy - (p.descent() + p.ascent()) / 2f, p)
            }
        }
    }
}

/** 调色板：上面是饱和度/明度方块，中间是色相条，下面是所选颜色预览。 */
class ColorPickerView(ctx: Context, initial: Int) : View(ctx) {
    private val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
    val color: Int get() = Color.HSVToColor(hsv)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var target = 0
    private val svH get() = dp(170f)
    private val hueH get() = dp(24f)
    private val prevH get() = dp(30f)
    private val gap get() = dp(14f)

    override fun onMeasure(ws: Int, hs: Int) {
        val h = paddingTop + paddingBottom + svH + hueH + prevH + 2 * gap
        setMeasuredDimension(resolveSize(dpi(280f), ws), h.roundToInt())
    }

    private fun marker(c: Canvas, x: Float, y: Float) {
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(3f)
        p.color = Color.argb(90, 0, 0, 0)
        c.drawCircle(x, y, dp(9f), p)
        p.strokeWidth = dp(2f)
        p.color = Color.WHITE
        c.drawCircle(x, y, dp(9f), p)
    }

    override fun onDraw(canvas: Canvas) {
        val l = paddingLeft.toFloat()
        val r = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val rad = dp(8f)
        p.style = Paint.Style.FILL

        val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        p.shader = LinearGradient(l, 0f, r, 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(l, top, r, top + svH, rad, rad, p)
        p.shader = LinearGradient(0f, top, 0f, top + svH, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(l, top, r, top + svH, rad, rad, p)
        marker(canvas, l + hsv[1] * (r - l), top + (1f - hsv[2]) * svH)

        val y2 = top + svH + gap
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            l, 0f, r, 0f,
            intArrayOf(
                Color.rgb(255, 0, 0), Color.rgb(255, 255, 0), Color.rgb(0, 255, 0),
                Color.rgb(0, 255, 255), Color.rgb(0, 0, 255), Color.rgb(255, 0, 255),
                Color.rgb(255, 0, 0)
            ),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(l, y2, r, y2 + hueH, hueH / 2, hueH / 2, p)
        marker(canvas, l + hsv[0] / 359.9f * (r - l), y2 + hueH / 2)

        val y3 = y2 + hueH + gap
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = color
        canvas.drawRoundRect(l, y3, r, y3 + prevH, rad, rad, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val l = paddingLeft.toFloat()
        val r = (width - paddingRight).toFloat()
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val y = e.y - paddingTop
            target = when {
                y < svH + gap / 2 -> 1
                y < svH + gap + hueH + gap / 2 -> 2
                else -> 0
            }
        }
        if (target != 0 && (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE)) {
            val fx = ((e.x - l) / (r - l)).coerceIn(0f, 1f)
            if (target == 1) {
                hsv[1] = fx
                hsv[2] = 1f - ((e.y - paddingTop) / svH).coerceIn(0f, 1f)
            } else {
                hsv[0] = fx * 359.9f
            }
            invalidate()
        }
        return true
    }
}

// ───────────────────────── 主界面 ─────────────────────────

class MainActivity : AppCompatActivity() {
    private lateinit var canvasView: SignatureView
    private lateinit var menu: FlowLayout
    private lateinit var penSlider: Slider
    private lateinit var eraserSlider: Slider
    private var hovered: MenuItemView? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        canvasView = SignatureView(this)
        root.addView(canvasView, LinearLayout.LayoutParams(match, 0, 1f))

        val divider = View(this).apply { setBackgroundColor(IOS_LINE) }
        root.addView(divider, LinearLayout.LayoutParams(match, 1))

        menu = FlowLayout(this).apply { setBackgroundColor(IOS_BAR) }
        root.addView(menu, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 1) 笔粗细
        penSlider = Slider(this, 1f, 14f, AppState.penWidthDp) { AppState.penWidthDp = it }
        menu.add(penSlider, 110f, 36f)

        // 2) 颜色：黑黄红绿蓝 + 调色板
        for (i in 0..5) {
            val dot = ColorDot(this, i)
            dot.setOnClickListener { if (i < 5) pickPreset(i) else openPicker() }
            menu.add(dot, 30f, 36f)
        }

        // 3) 橡皮擦 + 橡皮擦粗细
        val eraserBtn = IconButton(this, IconButton.ERASER)
        eraserBtn.setOnClickListener {
            AppState.eraserOn = !AppState.eraserOn
            refreshMenu()
        }
        menu.add(eraserBtn, 40f, 36f)
        eraserSlider = Slider(this, 6f, 60f, AppState.eraserWidthDp) { AppState.eraserWidthDp = it }
        menu.add(eraserSlider, 110f, 36f)

        // 4) 后退 / 清空 / 保存
        val undoBtn = IconButton(this, IconButton.UNDO)
        undoBtn.setOnClickListener { canvasView.undo() }
        menu.add(undoBtn, 40f, 36f)

        val clearBtn = IconButton(this, IconButton.CLEAR)
        clearBtn.setOnClickListener { canvasView.clearAll() }
        menu.add(clearBtn, 46f, 36f)

        val saveBtn = IconButton(this, IconButton.SAVE)
        saveBtn.setOnClickListener { save() }
        menu.add(saveBtn, 52f, 36f)

        canvasView.onChanged = { refreshMenu() }
        canvasView.menuForwarder = { sx, sy, action -> menuPointer(sx, sy, action) }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            root.setPadding(b.left, b.top, b.right, 0)
            menu.setPadding(dpi(12f), dpi(5f), dpi(12f), dpi(5f) + b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        refreshMenu()
    }

    private fun pickPreset(i: Int) {
        AppState.colorIndex = i
        AppState.eraserOn = false          // 选颜色就回到画笔
        refreshMenu()
    }

    private fun openPicker() {
        val picker = ColorPickerView(this, AppState.customColor)
        picker.setPadding(dpi(20f), dpi(16f), dpi(20f), dpi(4f))
        AlertDialog.Builder(this)
            .setView(picker)
            .setPositiveButton("使用这个颜色") { _, _ ->
                AppState.customColor = picker.color
                AppState.colorIndex = 5
                AppState.eraserOn = false
                refreshMenu()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshMenu() {
        penSlider.accent = AppState.currentColor()
        eraserSlider.accent = if (AppState.eraserOn) IOS_BLUE else IOS_GRAY
        for (i in 0 until menu.childCount) menu.getChildAt(i).invalidate()
    }

    private fun save() {
        val list = canvasView.visibleStrokes()
        Toast.makeText(this, "正在生成高清签名…", Toast.LENGTH_SHORT).show()
        val appCtx = applicationContext
        Thread {
            val msg = try {
                exportSignature(appCtx, list)
            } catch (e: OutOfMemoryError) {
                "内存不足，保存失败（可以把签名写小一点再试）"
            } catch (e: Exception) {
                "保存失败：" + e.message
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    // 橡皮擦手指滑进菜单栏后：把手指当成“选择工具”，松手时点击手指下面的按钮
    private fun menuPointer(sx: Float, sy: Float, action: Int) {
        if (action == MotionEvent.ACTION_CANCEL) {
            hovered?.showHover(false)
            hovered = null
            return
        }
        val target = nearestItem(sx, sy)
        if (target !== hovered) {
            hovered?.showHover(false)
            hovered = target
            target?.showHover(true)
        }
        if (target is Slider) target.setFromScreenX(sx)
        if (action == MotionEvent.ACTION_UP) {
            target?.showHover(false)
            hovered = null
            if (target != null && target !is Slider) target.performClick()
        }
    }

    private fun nearestItem(sx: Float, sy: Float): MenuItemView? {
        var best: MenuItemView? = null
        var bestD = dp(14f)
        val loc = IntArray(2)
        for (i in 0 until menu.childCount) {
            val v = menu.getChildAt(i) as? MenuItemView ?: continue
            v.getLocationOnScreen(loc)
            val dx = max(max(loc[0] - sx, sx - (loc[0] + v.width)), 0f)
            val dy = max(max(loc[1] - sy, sy - (loc[1] + v.height)), 0f)
            val d = hypot(dx, dy)
            if (d < bestD) {
                bestD = d
                best = v
            }
        }
        return best
    }
}
