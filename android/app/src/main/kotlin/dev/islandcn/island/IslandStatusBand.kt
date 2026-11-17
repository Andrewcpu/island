package dev.islandcn.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.view.View

/**
 * Full-width status-bar strip under the island. It does not take touches.
 * Collapsed, it draws nothing, so the real status bar shows through.
 * While the pill's width spring is open, the difference mask is two halves split
 * at the camera: the left half slides off the left edge and the right half off
 * the right edge, by the same spread as that spring. Collapse runs it backwards.
 * The app-window band stays put, so wallpaper and the app do not travel with the clock.
 */
internal class IslandStatusBand(context: Context) : View(context) {
    var pill: IslandPillView? = null
    var drawn = false
        private set
    private val paint = Paint()
    private var strip: Bitmap? = null
    private var leftHalf: Bitmap? = null
    private var rightHalf: Bitmap? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    fun setFrame(frame: IslandStatusStack.Frame?) {
        val oldLeft = leftHalf
        val oldRight = rightHalf
        strip = null
        leftHalf = null
        rightHalf = null
        if (frame != null && !frame.windowStrip.isRecycled && !frame.mask.isRecycled && frame.widthPx > 2 && frame.statusPx > 1) {
            val split = splitAt(frame.widthPx)
            strip = frame.windowStrip
            leftHalf = crop(frame.mask, 0, split)
            rightHalf = crop(frame.mask, split, frame.widthPx - split)
        }
        oldLeft?.recycle()
        oldRight?.recycle()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val spread = pill?.barSpread() ?: 0f
        val base = strip
        val left = leftHalf
        val right = rightHalf
        val show = spread > 0.002f && left != null && right != null &&
            !left.isRecycled && !right.isRecycled
        drawn = show
        if (!show || left == null || right == null) return
        val shiftLeft = spread * left.width
        val shiftRight = spread * right.width
        paint.alpha = 255
        paint.isFilterBitmap = false
        // Still app pixels cover the real status bar. The halves are opaque where they
        // differ, so this plate does not tint them. Matching pixels stay transparent.
        if (base != null && !base.isRecycled) canvas.drawBitmap(base, 0f, 0f, paint)
        canvas.drawBitmap(left, -shiftLeft, 0f, paint)
        canvas.drawBitmap(right, left.width + shiftRight, 0f, paint)
    }

    private fun splitAt(width: Int): Int {
        val camera = pill?.cameraPx ?: 0
        return if (camera in 1 until width) camera else width / 2
    }

    private fun crop(source: Bitmap, x: Int, width: Int): Bitmap? {
        if (width < 1 || source.height < 1) return null
        return try {
            val image = Bitmap.createBitmap(width, source.height, Bitmap.Config.ARGB_8888)
            Canvas(image).drawBitmap(source, -x.toFloat(), 0f, null)
            image
        } catch (_: Exception) {
            null
        }
    }
}
