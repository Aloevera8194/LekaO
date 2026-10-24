package com.aloevera.lekao

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class Layer(
    var bitmap: Bitmap,
    var name: String,
    var visible: Boolean = true,
    var opacity: Int = 100,
    var blend: Int = 0,
    var lockAlpha: Boolean = false
)

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool {
        PENCIL, PEN, INK, MARKER, AIRBRUSH, WATERCOLOR, ERASER, FILL, PICKER, TEXT,
        LINE, RECT, OVAL, RECT_FILL, OVAL_FILL
    }

    companion object {
        const val DEFAULT_DOC = 1600
        const val MAX_LAYERS = 8
        private const val MAX_UNDO = 30
        private const val GRAIN = 128
        val BLEND_NAMES = arrayOf("Normal", "Multiplicar", "Tela", "Sobrepor")
    }

    // ---- Estado público ----
    val layers = mutableListOf<Layer>()
    var activeIndex = 0
    var tool = Tool.PEN
    var color: Int = Color.BLACK
        set(value) {
            field = value
            grainDirty = true
        }
    var symmetry = false
    var docSize = DEFAULT_DOC
    private var holdX = 0f
    private var holdY = 0f
    private val holdRunnable = Runnable {
        if (stroking && !gesture) {
            cancelStroke()
            val pts = floatArrayOf(holdX, holdY)
            toDoc(pts)
            val hx = pts[0].toInt()
            val hy = pts[1].toInt()
            if (hx in 0 until docSize && hy in 0 until docSize) {
                pickColor(hx, hy)
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }
    var brushSize = 10f
    var brushOpacity = 100
    var smoothing = 0f
    var fillTolerance = 32
    var onHistoryChanged: (() -> Unit)? = null
    var onColorPicked: ((Int) -> Unit)? = null
    var onTextRequested: ((Float, Float) -> Unit)? = null
    var dirtyState = false
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // ---- Histórico ----
    private class Edit(val layer: Layer, val rect: Rect, val before: Bitmap, val after: Bitmap)

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var nextLayerNumber = 2

    // ---- Bitmaps auxiliares ----
    private var strokeBmp: Bitmap = Bitmap.createBitmap(docSize, docSize, Bitmap.Config.ARGB_8888)
    private var strokeCanvas = Canvas(strokeBmp)
    private var backupBmp: Bitmap? = null
    private var activeCanvas: Canvas? = null

    // ---- Textura do lápis ----
    private val grainAlpha = IntArray(GRAIN * GRAIN)
    private val grainBmp: Bitmap = Bitmap.createBitmap(GRAIN, GRAIN, Bitmap.Config.ARGB_8888)
    private var grainShader: BitmapShader? = null
    private var grainDirty = true

    // ---- Visualização (zoom / girar) ----
    private val viewMatrix = Matrix()
    private val inv = Matrix()
    private val docRect = RectF(0f, 0f, docSize.toFloat(), docSize.toFloat())

    // ---- Paints ----
    private val surround = context.getColor(R.color.canvas_surround)
    private val paperPaint = Paint().apply { color = Color.WHITE }
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val compPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val dstOut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val srcAtop = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    private val blendModes = arrayOf<PorterDuffXfermode?>(
        null,
        PorterDuffXfermode(PorterDuff.Mode.MULTIPLY),
        PorterDuffXfermode(PorterDuff.Mode.SCREEN),
        PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
    )
    private val path = Path()

    // ---- Estado do traço ----
    private var stroking = false
    private var direct = false
    private var activeTool = Tool.PEN
    private var lastX = 0f
    private var lastY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastPressure = 1f
    private var hasDirty = false
    private val dirty = RectF()
    private var shapeX0 = 0f
    private var shapeY0 = 0f
    private var prevShapeRect: Rect? = null

    // ---- Toque simples (balde / conta-gotas / texto) ----
    private var tapPending = false
    private var tapX = 0f
    private var tapY = 0f

    // ---- Gestos de vários dedos ----
    private var gesture = false
    private var gestureInit = false
    private var gFx = 0f
    private var gFy = 0f
    private var gDist = 0f
    private var gAngle = 0f
    private var gTwist = 0f
    private var rotating = false
    private var maxPointers = 1
    private var maxMove = 0f
    private val startX = FloatArray(32)
    private val startY = FloatArray(32)

    init {
        val rnd = java.util.Random(7)
        for (i in grainAlpha.indices) grainAlpha[i] = 70 + rnd.nextInt(186)
        layers.add(Layer(newBitmap(), "Camada 1"))
    }

    private fun newBitmap(): Bitmap = Bitmap.createBitmap(docSize, docSize, Bitmap.Config.ARGB_8888)

    private fun updateGrain() {
        val rgb = color and 0x00FFFFFF
        val px = IntArray(GRAIN * GRAIN) { (grainAlpha[it] shl 24) or rgb }
        grainBmp.setPixels(px, 0, GRAIN, 0, 0, GRAIN, GRAIN)
        grainShader = BitmapShader(grainBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        grainDirty = false
    }

    private fun isShape(t: Tool) =
        t == Tool.LINE || t == Tool.RECT || t == Tool.OVAL || t == Tool.RECT_FILL || t == Tool.OVAL_FILL

    // ------------------------------------------------------------------
    // Visualização
    // ------------------------------------------------------------------

    private fun fit() {
        if (width == 0 || height == 0) return
        val s = min(width, height) / docSize.toFloat() * 0.96f
        viewMatrix.reset()
        viewMatrix.postScale(s, s)
        viewMatrix.postTranslate((width - docSize * s) / 2f, (height - docSize * s) / 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fit()
    }

    fun fitToScreen() {
        fit()
        invalidate()
    }

    fun rotateView() {
        viewMatrix.postRotate(90f, width / 2f, height / 2f)
        invalidate()
    }

    fun flipView() {
        viewMatrix.postScale(-1f, 1f, width / 2f, height / 2f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(surround)
        canvas.save()
        canvas.concat(viewMatrix)
        canvas.drawRect(docRect, paperPaint)
        for ((i, l) in layers.withIndex()) {
            if (!l.visible) continue
            layerPaint.alpha = l.opacity * 255 / 100
            layerPaint.xfermode = blendModes[l.blend]
            if (stroking && !direct && i == activeIndex) {
                canvas.saveLayer(docRect, layerPaint)
                canvas.drawBitmap(l.bitmap, 0f, 0f, bmpPaint)
                canvas.drawBitmap(strokeBmp, 0f, 0f, prepComp())
                canvas.restore()
            } else {
                canvas.drawBitmap(l.bitmap, 0f, 0f, layerPaint)
            }
        }
        layerPaint.xfermode = null
        canvas.restore()
    }

    private fun prepComp(): Paint {
        var a = brushOpacity / 100f
        if (activeTool == Tool.MARKER) a *= 0.6f
        if (activeTool == Tool.WATERCOLOR) a *= 0.5f
        compPaint.alpha = (a * 255).toInt().coerceIn(0, 255)
        compPaint.xfermode = when {
            activeTool == Tool.ERASER -> dstOut
            layers[activeIndex].lockAlpha -> srcAtop
            else -> null
        }
        return compPaint
    }

    // ------------------------------------------------------------------
    // Toque
    // ------------------------------------------------------------------

    private fun rememberStart(e: MotionEvent, index: Int) {
        val id = e.getPointerId(index)
        if (id in 0..31) {
            startX[id] = e.getX(index)
            startY[id] = e.getY(index)
        }
    }

    private fun trackMove(e: MotionEvent) {
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i)
            if (id in 0..31) {
                maxMove = max(maxMove, hypot(e.getX(i) - startX[id], e.getY(i) - startY[id]))
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = false
                gestureInit = false
                maxPointers = 1
                maxMove = 0f
                rememberStart(e, 0)
                if (tool == Tool.FILL || tool == Tool.PICKER || tool == Tool.TEXT) {
                    tapPending = true
                    tapX = e.x
                    tapY = e.y
                } else {
                    startStroke(e)
                    if (stroking && !isShape(activeTool) && activeTool != Tool.ERASER) {
                        holdX = e.x
                        holdY = e.y
                        postDelayed(holdRunnable, 500)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(holdRunnable)
                rememberStart(e, e.actionIndex)
                if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                    tapPending = false
                    if (stroking) cancelStroke()
                    gesture = true
                    gestureInit = false
                    maxPointers = max(maxPointers, e.pointerCount)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesture) {
                    trackMove(e)
                    if (e.pointerCount == 2) panZoom(e)
                } else if (stroking) {
                    if (hypot(e.x - holdX, e.y - holdY) > 20f) removeCallbacks(holdRunnable)
                    moveStroke(e)
                } else if (tapPending) {
                    if (hypot(e.x - tapX, e.y - tapY) > 40f) tapPending = false
                }
            }
            MotionEvent.ACTION_POINTER_UP -> gestureInit = false
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                if (gesture) {
                    // toque rápido com 2 dedos = desfazer, com 3 dedos = refazer
                    if (e.eventTime - e.downTime < 450 && maxMove < 35f) {
                        if (maxPointers == 2) undo() else if (maxPointers == 3) redo()
                    }
                } else if (tapPending) {
                    tapPending = false
                    doTap(e.x, e.y)
                } else if (stroking) {
                    endStroke(e)
                }
                gesture = false
                gestureInit = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                tapPending = false
                if (stroking) cancelStroke()
                gesture = false
                gestureInit = false
            }
        }
        return true
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        viewMatrix.getValues(v)
        return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
    }

    private fun panZoom(e: MotionEvent) {
        val x0 = e.getX(0)
        val y0 = e.getY(0)
        val x1 = e.getX(1)
        val y1 = e.getY(1)
        val fx = (x0 + x1) / 2f
        val fy = (y0 + y1) / 2f
        val d = hypot(x1 - x0, y1 - y0)
        val ang = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        if (!gestureInit) {
            gFx = fx
            gFy = fy
            gDist = d
            gAngle = ang
            gTwist = 0f
            rotating = false
            gestureInit = true
            return
        }
        if (gDist > 1f && d > 1f) {
            val cur = currentScale()
            val target = (cur * d / gDist).coerceIn(0.1f, 8f)
            val sf = target / cur
            viewMatrix.postScale(sf, sf, gFx, gFy)
        }
        viewMatrix.postTranslate(fx - gFx, fy - gFy)

        var da = ang - gAngle
        while (da > 180f) da -= 360f
        while (da < -180f) da += 360f
        gAngle = ang
        if (!rotating) {
            gTwist += da
            if (abs(gTwist) > 12f) rotating = true
        } else {
            viewMatrix.postRotate(da, fx, fy)
        }

        gFx = fx
        gFy = fy
        gDist = d
        invalidate()
    }

    private fun toDoc(pts: FloatArray) {
        viewMatrix.invert(inv)
        inv.mapPoints(pts)
    }

    // ------------------------------------------------------------------
    // Balde, conta-gotas e texto
    // ------------------------------------------------------------------

    private fun doTap(sx: Float, sy: Float) {
        val pts = floatArrayOf(sx, sy)
        toDoc(pts)
        val x = pts[0].toInt()
        val y = pts[1].toInt()
        if (x < 0 || y < 0 || x >= docSize || y >= docSize) return
        when (tool) {
            Tool.FILL -> floodFill(x, y)
            Tool.TEXT -> onTextRequested?.invoke(pts[0], pts[1])
            else -> pickColor(x, y)
        }
    }

    private fun pickColor(x: Int, y: Int) {
        val bmp = exportBitmap()
        val c = bmp.getPixel(x, y)
        bmp.recycle()
        color = c or (0xFF shl 24)
        onColorPicked?.invoke(color)
    }

    private fun floodFill(sx: Int, sy: Int) {
        val layer = layers[activeIndex]
        if (!layer.visible) {
            Toast.makeText(context, "A camada está oculta", Toast.LENGTH_SHORT).show()
            return
        }
        val w = docSize
        val h = docSize
        val merged = exportBitmap()
        val px = IntArray(w * h)
        merged.getPixels(px, 0, w, 0, 0, w, h)
        merged.recycle()

        val t = px[sy * w + sx]
        val tr = Color.red(t)
        val tg = Color.green(t)
        val tb = Color.blue(t)
        val tol = fillTolerance

        fun match(c: Int): Boolean =
            abs(Color.red(c) - tr) <= tol && abs(Color.green(c) - tg) <= tol && abs(Color.blue(c) - tb) <= tol

        val mask = BooleanArray(w * h)
        val stack = java.util.ArrayDeque<Int>()
        stack.push(sy * w + sx)
        var minX = sx
        var maxX = sx
        var minY = sy
        var maxY = sy

        while (stack.isNotEmpty()) {
            val idx = stack.pop()
            if (mask[idx] || !match(px[idx])) continue
            val y = idx / w
            val row = y * w
            var l = idx - row
            while (l > 0 && !mask[row + l - 1] && match(px[row + l - 1])) l--
            var r = idx - row
            while (r < w - 1 && !mask[row + r + 1] && match(px[row + r + 1])) r++
            for (x in l..r) mask[row + x] = true
            if (l < minX) minX = l
            if (r > maxX) maxX = r
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            for (ny in intArrayOf(y - 1, y + 1)) {
                if (ny < 0 || ny >= h) continue
                val nrow = ny * w
                var inSpan = false
                for (x in l..r) {
                    val ok = !mask[nrow + x] && match(px[nrow + x])
                    if (ok && !inSpan) {
                        stack.push(nrow + x)
                        inSpan = true
                    } else if (!ok) {
                        inSpan = false
                    }
                }
            }
        }

        // cresce 1 pixel para cobrir a borda suave das linhas
        val gx0 = max(0, minX - 1)
        val gy0 = max(0, minY - 1)
        val gx1 = min(w, maxX + 2)
        val gy1 = min(h, maxY + 2)
        val gw = gx1 - gx0
        val gh = gy1 - gy0
        val grown = BooleanArray(gw * gh)
        for (y in gy0 until gy1) {
            for (x in gx0 until gx1) {
                val i = y * w + x
                grown[(y - gy0) * gw + (x - gx0)] =
                    mask[i] ||
                        (x > 0 && mask[i - 1]) ||
                        (x < w - 1 && mask[i + 1]) ||
                        (y > 0 && mask[i - w]) ||
                        (y < h - 1 && mask[i + w])
            }
        }

        val rect = Rect(gx0, gy0, gx1, gy1)
        val before = crop(layer.bitmap, rect)
        val lp = IntArray(gw * gh)
        layer.bitmap.getPixels(lp, 0, gw, gx0, gy0, gw, gh)
        val fc = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        val lock = layer.lockAlpha
        for (i in lp.indices) {
            if (grown[i] && (!lock || Color.alpha(lp[i]) > 0)) lp[i] = fc
        }
        layer.bitmap.setPixels(lp, 0, gw, gx0, gy0, gw, gh)
        val after = crop(layer.bitmap, rect)
        pushEdit(layer, rect, before, after)
    }

    fun drawText(text: String, x: Float, y: Float) {
        if (text.isBlank()) return
        val layer = layers[activeIndex]
        if (!layer.visible) {
            Toast.makeText(context, "A camada está oculta", Toast.LENGTH_SHORT).show()
            return
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        p.alpha = brushOpacity * 255 / 100
        p.textSize = (brushSize * 3f).coerceIn(24f, 600f)
        val lines = text.split("\n")
        val lh = p.fontSpacing
        val fm = p.fontMetrics
        var maxW = 0f
        for (ln in lines) maxW = max(maxW, p.measureText(ln))
        val left = max(0, floor(x - 2f).toInt())
        val top = max(0, floor(y + fm.ascent - 2f).toInt())
        val right = min(docSize, ceil(x + maxW + 2f).toInt())
        val bottom = min(docSize, ceil(y + lh * (lines.size - 1) + fm.descent + 2f).toInt())
        val r = Rect(left, top, right, bottom)
        if (r.isEmpty) return
        val before = crop(layer.bitmap, r)
        val c = Canvas(layer.bitmap)
        for ((i, ln) in lines.withIndex()) c.drawText(ln, x, y + i * lh, p)
        val after = crop(layer.bitmap, r)
        pushEdit(layer, r, before, after)
    }

    // ------------------------------------------------------------------
    // Traço
    // ------------------------------------------------------------------

    private fun pressureOf(e: MotionEvent, p: Float): Float =
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) 1f else p.coerceIn(0.02f, 1f)

    private fun widthFor(p: Float): Float {
        val w = when (activeTool) {
            Tool.PENCIL -> brushSize * 0.6f * (0.2f + 0.8f * p)
            Tool.PEN -> brushSize * (0.45f + 0.55f * p)
            Tool.INK -> brushSize * (0.12f + 0.88f * p * p)
            Tool.MARKER -> brushSize * 1.4f * (0.8f + 0.2f * p)
            Tool.AIRBRUSH -> brushSize * 2f * (0.4f + 0.6f * p)
            Tool.WATERCOLOR -> brushSize * 1.8f * (0.6f + 0.4f * p)
            Tool.ERASER -> brushSize * 1.5f * (0.5f + 0.5f * p)
            else -> brushSize
        }
        return max(1f, w)
    }

    private fun blurRadius(w: Float): Float = when (activeTool) {
        Tool.AIRBRUSH -> w * 0.5f
        Tool.WATERCOLOR -> w * 0.15f
        else -> 0f
    }

    private fun applyBlur(w: Float) {
        val r = blurRadius(w)
        strokePaint.maskFilter = if (r > 0f) BlurMaskFilter(max(1f, r), BlurMaskFilter.Blur.NORMAL) else null
    }

    private fun addDirty(x: Float, y: Float, r: Float) {
        if (!hasDirty) {
            dirty.set(x - r, y - r, x + r, y + r)
            hasDirty = true
        } else {
            dirty.union(x - r, y - r, x + r, y + r)
        }
    }

    private fun configurePaint() {
        strokePaint.shader = null
        strokePaint.maskFilter = null
        strokePaint.xfermode = null
        strokePaint.style = Paint.Style.STROKE
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        when (activeTool) {
            Tool.ERASER -> {
                strokePaint.color = Color.BLACK
                if (direct) strokePaint.xfermode = dstOut
            }
            Tool.PENCIL -> {
                if (grainDirty) updateGrain()
                strokePaint.color = Color.BLACK
                strokePaint.shader = grainShader
            }
            Tool.AIRBRUSH -> strokePaint.color = Color.argb(70, r, g, b)
            else -> strokePaint.color = Color.rgb(r, g, b)
        }
        if (direct && activeTool != Tool.ERASER && layers[activeIndex].lockAlpha) {
            strokePaint.xfermode = srcAtop
        }
    }

    private fun startStroke(e: MotionEvent) {
        val layer = layers[activeIndex]
        if (!layer.visible) {
            Toast.makeText(context, "A camada está oculta", Toast.LENGTH_SHORT).show()
            return
        }
        activeTool = if (e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) Tool.ERASER else tool
        stroking = true
        hasDirty = false
        prevShapeRect = null
        direct = brushOpacity >= 100 &&
            (activeTool == Tool.PENCIL || activeTool == Tool.PEN || activeTool == Tool.INK ||
                activeTool == Tool.AIRBRUSH || activeTool == Tool.ERASER)

        if (direct) {
            val b = backupBmp ?: newBitmap().also { backupBmp = it }
            Canvas(b).drawBitmap(layer.bitmap, 0f, 0f, srcPaint)
            activeCanvas = Canvas(layer.bitmap)
        } else {
            strokeBmp.eraseColor(Color.TRANSPARENT)
            activeCanvas = strokeCanvas
        }
        configurePaint()

        val pts = floatArrayOf(e.getX(0), e.getY(0))
        toDoc(pts)
        if (isShape(activeTool)) {
            shapeX0 = pts[0]
            shapeY0 = pts[1]
            invalidate()
            return
        }

        lastX = pts[0]
        lastY = pts[1]
        lastMidX = lastX
        lastMidY = lastY
        lastPressure = pressureOf(e, e.getPressure(0))

        val w = widthFor(lastPressure)
        strokePaint.strokeWidth = w
        applyBlur(w)
        activeCanvas?.drawPoint(lastX, lastY, strokePaint)
        addDirty(lastX, lastY, w / 2f + 2f + blurRadius(w) * 1.2f)
        if (symmetry) {
            val d = docSize.toFloat()
            activeCanvas?.drawPoint(d - lastX, lastY, strokePaint)
            addDirty(d - lastX, lastY, w / 2f + 2f + blurRadius(w) * 1.2f)
        }
        invalidate()
    }

    private fun moveStroke(e: MotionEvent) {
        if (isShape(activeTool)) {
            updateShape(e.getX(0), e.getY(0))
            return
        }
        for (i in 0 until e.historySize) {
            addPoint(e.getHistoricalX(0, i), e.getHistoricalY(0, i), e.getHistoricalPressure(0, i), e)
        }
        addPoint(e.getX(0), e.getY(0), e.getPressure(0), e)
        invalidate()
    }

    private fun addPoint(sx: Float, sy: Float, rawPressure: Float, e: MotionEvent) {
        val pts = floatArrayOf(sx, sy)
        toDoc(pts)
        val k = 1f - smoothing * 0.92f
        val x = lastX + (pts[0] - lastX) * k
        val y = lastY + (pts[1] - lastY) * k
        val pressure = lastPressure * 0.6f + pressureOf(e, rawPressure) * 0.4f
        val mx = (lastX + x) / 2f
        val my = (lastY + y) / 2f
        drawQuad(lastMidX, lastMidY, lastX, lastY, mx, my, widthFor((lastPressure + pressure) / 2f))
        lastMidX = mx
        lastMidY = my
        lastX = x
        lastY = y
        lastPressure = pressure
    }

    private fun drawQuad(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, w: Float) {
        val c = activeCanvas ?: return
        strokePaint.strokeWidth = w
        applyBlur(w)
        path.reset()
        path.moveTo(x0, y0)
        path.quadTo(cx, cy, x1, y1)
        c.drawPath(path, strokePaint)
        val r = w / 2f + 2f + blurRadius(w) * 1.2f
        addDirty(x0, y0, r)
        addDirty(cx, cy, r)
        addDirty(x1, y1, r)
        if (symmetry) {
            val d = docSize.toFloat()
            path.reset()
            path.moveTo(d - x0, y0)
            path.quadTo(d - cx, cy, d - x1, y1)
            c.drawPath(path, strokePaint)
            addDirty(d - x0, y0, r)
            addDirty(d - cx, cy, r)
            addDirty(d - x1, y1, r)
        }
    }

    private fun updateShape(sx: Float, sy: Float) {
        val pts = floatArrayOf(sx, sy)
        toDoc(pts)
        val x1 = pts[0]
        val y1 = pts[1]
        prevShapeRect?.let { strokeCanvas.drawRect(it, clearPaint) }
        strokePaint.strokeWidth = brushSize
        val left = min(shapeX0, x1)
        val top = min(shapeY0, y1)
        val right = max(shapeX0, x1)
        val bottom = max(shapeY0, y1)
        when (activeTool) {
            Tool.LINE -> strokeCanvas.drawLine(shapeX0, shapeY0, x1, y1, strokePaint)
            Tool.RECT -> strokeCanvas.drawRect(left, top, right, bottom, strokePaint)
            Tool.OVAL -> strokeCanvas.drawOval(RectF(left, top, right, bottom), strokePaint)
            Tool.RECT_FILL -> {
                strokePaint.style = Paint.Style.FILL
                strokeCanvas.drawRect(left, top, right, bottom, strokePaint)
                strokePaint.style = Paint.Style.STROKE
            }
            Tool.OVAL_FILL -> {
                strokePaint.style = Paint.Style.FILL
                strokeCanvas.drawOval(RectF(left, top, right, bottom), strokePaint)
                strokePaint.style = Paint.Style.STROKE
            }
            else -> {}
        }
        val r = brushSize / 2f + 3f
        dirty.set(left - r, top - r, right + r, bottom + r)
        hasDirty = true
        prevShapeRect = clampRect()
        invalidate()
    }

    private fun endStroke(e: MotionEvent) {
        if (isShape(activeTool)) {
            updateShape(e.getX(0), e.getY(0))
            commitStroke()
            return
        }
        moveStroke(e)
        drawQuad(lastMidX, lastMidY, lastX, lastY, lastX, lastY, widthFor(lastPressure))
        commitStroke()
    }

    private fun clampRect(): Rect {
        val l = max(0, floor(dirty.left).toInt())
        val t = max(0, floor(dirty.top).toInt())
        val r = min(docSize, ceil(dirty.right).toInt())
        val b = min(docSize, ceil(dirty.bottom).toInt())
        return Rect(l, t, r, b)
    }

    private fun crop(src: Bitmap, r: Rect): Bitmap {
        val b = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        Canvas(b).drawBitmap(src, r, Rect(0, 0, r.width(), r.height()), srcPaint)
        return b
    }

    private fun pushEdit(layer: Layer, r: Rect, before: Bitmap, after: Bitmap) {
        undoStack.addLast(Edit(layer, r, before, after))
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        dirtyState = true
        onHistoryChanged?.invoke()
        invalidate()
    }

    private fun commitStroke() {
        stroking = false
        val layer = layers[activeIndex]
        val r = clampRect()
        if (!hasDirty || r.isEmpty) {
            if (!direct) strokeBmp.eraseColor(Color.TRANSPARENT)
            invalidate()
            return
        }
        val before: Bitmap
        if (direct) {
            before = crop(backupBmp!!, r)
        } else {
            before = crop(layer.bitmap, r)
            Canvas(layer.bitmap).drawBitmap(strokeBmp, r, r, prepComp())
            strokeCanvas.drawRect(r, clearPaint)
        }
        val after = crop(layer.bitmap, r)
        pushEdit(layer, r, before, after)
    }

    private fun cancelStroke() {
        if (!stroking) return
        stroking = false
        if (direct) {
            val b = backupBmp
            if (b != null) Canvas(layers[activeIndex].bitmap).drawBitmap(b, 0f, 0f, srcPaint)
        } else {
            strokeBmp.eraseColor(Color.TRANSPARENT)
        }
        prevShapeRect = null
        invalidate()
    }

    // ------------------------------------------------------------------
    // Desfazer / refazer
    // ------------------------------------------------------------------

    private fun applyBitmap(l: Layer, r: Rect, b: Bitmap) {
        Canvas(l.bitmap).drawBitmap(b, r.left.toFloat(), r.top.toFloat(), srcPaint)
        dirtyState = true
        onHistoryChanged?.invoke()
        invalidate()
    }

    fun undo() {
        if (stroking) return
        val e = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(e)
        applyBitmap(e.layer, e.rect, e.before)
    }

    fun redo() {
        if (stroking) return
        val e = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(e)
        applyBitmap(e.layer, e.rect, e.after)
    }

    // ------------------------------------------------------------------
    // Camadas e novo desenho
    // ------------------------------------------------------------------

    fun addLayer(): Boolean {
        if (layers.size >= MAX_LAYERS) return false
        layers.add(activeIndex + 1, Layer(newBitmap(), "Camada ${nextLayerNumber++}"))
        activeIndex += 1
        dirtyState = true
        invalidate()
        return true
    }

    fun duplicateLayer(): Boolean {
        if (layers.size >= MAX_LAYERS) return false
        val src = layers[activeIndex]
        val copy = Layer(
            src.bitmap.copy(Bitmap.Config.ARGB_8888, true),
            src.name + " cópia",
            src.visible,
            src.opacity,
            src.blend,
            src.lockAlpha
        )
        layers.add(activeIndex + 1, copy)
        activeIndex += 1
        dirtyState = true
        invalidate()
        return true
    }

    fun mergeDown(): Boolean {
        if (activeIndex <= 0) return false
        val top = layers[activeIndex]
        val below = layers[activeIndex - 1]
        layerPaint.alpha = top.opacity * 255 / 100
        layerPaint.xfermode = blendModes[top.blend]
        Canvas(below.bitmap).drawBitmap(top.bitmap, 0f, 0f, layerPaint)
        layerPaint.xfermode = null
        layers.removeAt(activeIndex)
        activeIndex -= 1
        undoStack.clear()
        redoStack.clear()
        dirtyState = true
        onHistoryChanged?.invoke()
        invalidate()
        return true
    }

    fun importBitmap(src: Bitmap): Boolean {
        if (layers.size >= MAX_LAYERS) return false
        val bmp = newBitmap()
        val scale = min(docSize / src.width.toFloat(), docSize / src.height.toFloat())
        val w = src.width * scale
        val h = src.height * scale
        val dst = RectF((docSize - w) / 2f, (docSize - h) / 2f, (docSize + w) / 2f, (docSize + h) / 2f)
        Canvas(bmp).drawBitmap(src, null, dst, bmpPaint)
        layers.add(activeIndex + 1, Layer(bmp, "Imagem ${nextLayerNumber++}"))
        activeIndex += 1
        dirtyState = true
        invalidate()
        return true
    }

    fun deleteLayer(i: Int) {
        if (layers.size <= 1 || i !in layers.indices) return
        val l = layers.removeAt(i)
        undoStack.removeAll { it.layer === l }
        redoStack.removeAll { it.layer === l }
        activeIndex = when {
            activeIndex > i -> activeIndex - 1
            activeIndex == i -> min(i, layers.size - 1)
            else -> activeIndex
        }
        dirtyState = true
        onHistoryChanged?.invoke()
        invalidate()
    }

    fun moveLayer(i: Int, dir: Int) {
        val j = i + dir
        if (i !in layers.indices || j !in layers.indices) return
        val tmp = layers[i]
        layers[i] = layers[j]
        layers[j] = tmp
        activeIndex = when (activeIndex) {
            i -> j
            j -> i
            else -> activeIndex
        }
        dirtyState = true
        invalidate()
    }

    private fun applySize(n: Int) {
        docSize = n
        strokeBmp.recycle()
        strokeBmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        strokeCanvas = Canvas(strokeBmp)
        backupBmp?.recycle()
        backupBmp = null
        docRect.set(0f, 0f, n.toFloat(), n.toFloat())
        post { fit(); invalidate() }
    }

    fun resizeDocument(n: Int): Boolean {
        if (n == docSize) return true
        if (stroking) cancelStroke()
        val novos = mutableListOf<Layer>()
        try {
            for (l in layers) {
                val nb = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
                Canvas(nb).drawBitmap(l.bitmap, null, Rect(0, 0, n, n), bmpPaint)
                novos.add(Layer(nb, l.name, l.visible, l.opacity, l.blend, l.lockAlpha))
            }
        } catch (_: OutOfMemoryError) {
            novos.forEach { it.bitmap.recycle() }
            return false
        }
        val antigos = layers.toList()
        layers.clear()
        layers.addAll(novos)
        antigos.forEach { it.bitmap.recycle() }
        applySize(n)
        undoStack.clear()
        redoStack.clear()
        dirtyState = true
        onHistoryChanged?.invoke()
        invalidate()
        return true
    }

    fun newDocument() {
        if (stroking) cancelStroke()
        layers.clear()
        layers.add(Layer(newBitmap(), "Camada 1"))
        activeIndex = 0
        nextLayerNumber = 2
        undoStack.clear()
        redoStack.clear()
        dirtyState = true
        fit()
        onHistoryChanged?.invoke()
        invalidate()
    }

    // ------------------------------------------------------------------
    // Exportar / salvar estado
    // ------------------------------------------------------------------

    fun exportBitmap(): Bitmap {
        val out = newBitmap()
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        for (l in layers) {
            if (!l.visible) continue
            layerPaint.alpha = l.opacity * 255 / 100
            layerPaint.xfermode = blendModes[l.blend]
            c.drawBitmap(l.bitmap, 0f, 0f, layerPaint)
        }
        layerPaint.xfermode = null
        return out
    }

    fun saveState(dir: File) {
        if (!dirtyState) return
        try {
            val meta = StringBuilder()
            meta.append(activeIndex).append('\n')
            for ((i, l) in layers.withIndex()) {
                FileOutputStream(File(dir, "layer_$i.png")).use {
                    l.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                meta.append(if (l.visible) 1 else 0).append('|')
                    .append(l.opacity).append('|')
                    .append(l.blend).append('|')
                    .append(if (l.lockAlpha) 1 else 0).append('|')
                    .append(l.name).append('\n')
            }
            File(dir, "state.txt").writeText(meta.toString())
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith("layer_") && f.name.endsWith(".png")) {
                    val n = f.name.removePrefix("layer_").removeSuffix(".png").toIntOrNull()
                    if (n != null && n >= layers.size) f.delete()
                }
            }
            dirtyState = false
        } catch (_: Exception) {
        }
    }

    fun loadState(dir: File) {
        val f = File(dir, "state.txt")
        if (!f.exists()) return
        try {
            val lines = f.readLines()
            val loaded = mutableListOf<Layer>()
            val f0 = File(dir, "layer_0.png")
            if (f0.exists()) {
                val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f0.path, bo)
                if (bo.outWidth == bo.outHeight && bo.outWidth in 256..4096 && bo.outWidth != docSize) {
                    applySize(bo.outWidth)
                }
            }
            for (i in 1 until lines.size) {
                val parts = lines[i].split("|", limit = 5)
                val visible: Boolean
                val opacity: Int
                var blend = 0
                var lock = false
                val name: String
                if (parts.size >= 5) {
                    visible = parts[0] == "1"
                    opacity = parts[1].toIntOrNull() ?: 100
                    blend = (parts[2].toIntOrNull() ?: 0).coerceIn(0, BLEND_NAMES.size - 1)
                    lock = parts[3] == "1"
                    name = parts[4]
                } else if (parts.size >= 3) {
                    visible = parts[0] == "1"
                    opacity = parts[1].toIntOrNull() ?: 100
                    name = parts[2]
                } else {
                    continue
                }
                val file = File(dir, "layer_${i - 1}.png")
                if (!file.exists()) continue
                val opts = BitmapFactory.Options().apply {
                    inMutable = true
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeFile(file.path, opts) ?: continue
                val bmp = if (decoded.isMutable && decoded.width == docSize && decoded.height == docSize) {
                    decoded
                } else {
                    newBitmap().also { Canvas(it).drawBitmap(decoded, 0f, 0f, srcPaint) }
                }
                loaded.add(Layer(bmp, name, visible, opacity, blend, lock))
            }
            if (loaded.isNotEmpty()) {
                layers.clear()
                layers.addAll(loaded)
                activeIndex = (lines[0].toIntOrNull() ?: 0).coerceIn(0, layers.size - 1)
                nextLayerNumber = layers.size + 1
                undoStack.clear()
                redoStack.clear()
                invalidate()
            }
        } catch (_: Exception) {
        }
    }
}
