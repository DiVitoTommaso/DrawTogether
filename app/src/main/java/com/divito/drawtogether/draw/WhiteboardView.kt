package com.divito.drawtogether.draw

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.divito.drawtogether.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


/** whiteboard view with online support without server using multi level draw solution to board consistence */
@Suppress("SameParameterValue")
class WhiteboardView : View {

    private lateinit var wrappers: Array<Canvas>
    private lateinit var boards: Array<Bitmap>

    private val loadLock = ReentrantLock()
    private var onLoad: (() -> Unit)? = null
    private var isLoaded = false

    // whiteboard specs
    private val backgroundStyle = Paint().apply { color = Color.WHITE }
    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private var size = Pair(0, 0)
    private var levels: Int = 1

    constructor(ctx: Context) : super(ctx)
    constructor(ctx: Context, attr: AttributeSet?) : super(ctx, attr) {
        initAttrs(ctx, attr, 0)
    }

    constructor(ctx: Context, attr: AttributeSet?, style: Int) : super(ctx, attr, style) {
        initAttrs(ctx, attr, style)
    }

    private fun initAttrs(ctx: Context, attr: AttributeSet?, style: Int) {
        val arr = ctx.theme.obtainStyledAttributes(attr, R.styleable.WhiteboardView, style, 0)
        try {
            levels = arr.getInt(R.styleable.WhiteboardView_levels, 1)
        } finally {
            arr.recycle()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            this.size = Pair(w, h)
            createNewBitmaps(w, h)
            loadLock.withLock {
                isLoaded = true
                onLoad?.invoke()
            }
            Log.i("WhiteboardActivity", "Whiteboard size change detected. w: $w h:$h")
        }
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        // draw on canvas in priority order
        if (canvas != null)
            drawToCanvas(canvas)
    }

    private fun createNewBitmaps(w: Int, h: Int) {
        boards = Array(levels) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
        wrappers = Array(levels) { i -> Canvas(boards[i]) }

    }

    private fun drawToCanvas(canvas: Canvas) {
        // draw on canvas in layer order
        for (i in (0..boards.lastIndex).reversed())
            canvas.drawBitmap(boards[i], 0.0.toFloat(), 0.0.toFloat(), null)
    }

    fun setOnSurfaceReady(f: () -> Unit) {
        loadLock.withLock {
            onLoad = f
            if (isLoaded)
                onLoad?.invoke()
        }
    }

    fun drawLine(oldX: Float, oldY: Float, toX: Float, toY: Float, style: Paint, layer: Int) {
        wrappers[layer].drawCircle((oldX + toX) / 2, (oldY + toY) / 2, style.strokeWidth / 2, clear)
        wrappers[layer].drawLine(oldX, oldY, toX, toY, style)
        invalidate()
    }

    fun drawLine(
        oldX: Float,
        oldY: Float,
        toX: Float,
        toY: Float,
        style: Paint,
        layer1: Int,
        layer2: Int
    ) {

        wrappers[layer1].drawCircle(
            (oldX + toX) / 2,
            (oldY + toY) / 2,
            style.strokeWidth / 2,
            style
        )
        wrappers[layer1].drawLine(oldX, oldY, toX, toY, style)
        wrappers[layer2].drawCircle(
            (oldX + toX) / 2,
            (oldY + toY) / 2,
            style.strokeWidth / 2,
            style
        )
        wrappers[layer2].drawLine(oldX, oldY, toX, toY, style)
        invalidate()
    }

    fun clearLine(
        oldX: Float,
        oldY: Float,
        toX: Float,
        toY: Float,
        strokeWidth: Float,
        layer: Int,
    ) {

        clear.strokeWidth = strokeWidth
        wrappers[layer].drawCircle((oldX + toX) / 2, (oldY + toY) / 2, strokeWidth / 2, clear)
        wrappers[layer].drawLine(oldX, oldY, toX, toY, clear)
        invalidate()
    }

    fun clearLine(
        oldX: Float,
        oldY: Float,
        toX: Float,
        toY: Float,
        strokeWidth: Float,
        layer1: Int,
        layer2: Int
    ) {

        clear.strokeWidth = strokeWidth
        wrappers[layer1].drawCircle((oldX + toX) / 2, (oldY + toY) / 2, strokeWidth / 2, clear)
        wrappers[layer1].drawLine(oldX, oldY, toX, toY, clear)
        wrappers[layer2].drawCircle((oldX + toX) / 2, (oldY + toY) / 2, strokeWidth / 2, clear)
        wrappers[layer2].drawLine(oldX, oldY, toX, toY, clear)
        invalidate()
    }

    private fun drawRect(
        oldX: Float,
        oldY: Float,
        toX: Float,
        toY: Float,
        style: Paint,
        layer: Int,
    ) {
        wrappers[layer].drawRect(oldX, oldY, toX, toY, style)
        invalidate()
    }


    private fun clearRect(oldX: Float, oldY: Float, toX: Float, toY: Float, layer: Int) {
        wrappers[layer].drawRect(oldX, oldY, toX, toY, clear)
        invalidate()
    }

    fun drawPicture(img: Bitmap, layer: Int) {
        wrappers[layer].drawBitmap(
            img,
            0.0.toFloat(),
            0.0.toFloat(),
            null
        )
        invalidate()
    }

    fun moveLayer(layerSource: Int, layerDest: Int) {
        // move layer to another layer
        wrappers[layerDest].drawBitmap(
            boards[layerSource],
            0.0.toFloat(),
            0.0.toFloat(),
            null
        )
        wrappers[layerSource].drawRect(
            0.0.toFloat(),
            0.0.toFloat(),
            size.first.toFloat(),
            size.second.toFloat(),
            clear
        )
        invalidate()
    }

    fun drawBackground(priority: Int) {
        drawRect(
            0.0.toFloat(),
            0.0.toFloat(),
            width.toFloat(),
            height.toFloat(),
            backgroundStyle,
            priority
        )
        invalidate()
    }

    fun clearSurface() {
        for (i in 0 until levels)
            clearRect(
                0.0.toFloat(),
                0.0.toFloat(),
                size.first.toFloat(),
                size.second.toFloat(),
                i
            )
        invalidate()
    }

    fun getCurrentSize() = size

    fun drawToBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawToCanvas(canvas)
        return bitmap
    }

    fun writeToFile(f: File) {
        // write bitmaps to PNG file
        val img = drawToBitmap()
        img.compress(Bitmap.CompressFormat.PNG, 100, FileOutputStream(f))
    }

}
