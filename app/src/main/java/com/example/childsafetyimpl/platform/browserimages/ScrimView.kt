package com.example.childsafetyimpl.platform.browserimages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/**
 * What the scrim window draws.
 *
 * The window fills the screen and everything here is in screen coordinates; [area] is the part
 * of the screen it may paint, clipped here rather than by resizing the window. Inside it: either
 * the whole area, or — once the photos on the page are known — only a patch over each photo, with
 * the page readable in between.
 *
 * Every covered patch is an opaque dark ground first, then the photo's own frost once one
 * arrives, drawn over it untinted. The frost is opaque and already has no detail left in it, so a
 * scrim on top would hide nothing more and only make the patch read as a panel laid over the page
 * rather than the photo out of focus — rule 10. The ground is what shows where there is no frost
 * yet. The frost moves with its patch. A patch with no frost of
 * its own yet — a photo just scrolled in, a part of the page not looked through — shows the frost
 * of the whole window where it now sits: not its own colours, but blurred like every other patch
 * rather than a black hole in the page. The whole area, when it is covered whole, gets that frost
 * too. On any device or window where nothing can be captured, a patch is simply opaque.
 */
class ScrimView(context: Context) : View(context) {

    private val groundPaint = Paint().apply { color = GROUND }

    // Filtering is what turns a stretched thumbnail soft rather than blocky.
    private val frostPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val cornerRadius = CORNER_RADIUS_DP * resources.displayMetrics.density
    private val clip = Path()
    private val patch = RectF()
    private val placed = Rect()
    private val moved = Rect()
    private val frostTarget = RectF()
    private val origin = IntArray(2)

    private var backdrop: Bitmap? = null
    private val backdropArea = Rect()

    /** The part of the screen the scrim may paint, in screen coordinates. */
    val area = Rect()

    /** Patches in screen coordinates, or null when the whole area is covered. */
    var coveredPhotos: List<Photo>? = null
        private set

    /** How the patches move while the page scrolls; null for a still page. */
    var motion: PatchMotion? = null
        private set

    /** Each photo's frost, by [Photo.id]. */
    var photoFrosts: Map<Long, PhotoFrost> = emptyMap()
        private set

    val hasBackdrop: Boolean get() = backdrop != null || photoFrosts.isNotEmpty()

    fun setArea(area: Rect) {
        if (area == this.area) return
        this.area.set(area)
        invalidate()
    }

    fun coverAll() {
        if (coveredPhotos == null && motion == null) return
        coveredPhotos = null
        motion = null
        invalidate()
    }

    fun coverPhotos(photos: List<Photo>, motion: PatchMotion? = null) {
        if (photos == coveredPhotos && motion == this.motion) return
        coveredPhotos = photos.map { it.copy(bounds = Rect(it.bounds)) }
        this.motion = motion
        invalidate()
    }

    /**
     * [frosted] must already be blurred — see [BackdropBlur.frost]. This view blurs nothing.
     * [frostArea] is the part of the screen it was taken from.
     */
    fun setBackdrop(frosted: Bitmap, frostArea: Rect) {
        backdrop = frosted
        backdropArea.set(frostArea)
        invalidate()
    }

    /** Each photo's frost, by [Photo.id] — see [BackdropBlur.frostPhoto]. */
    fun setPhotoFrosts(frosts: Map<Long, PhotoFrost>) {
        if (frosts == photoFrosts) return
        photoFrosts = frosts
        invalidate()
    }

    fun clearBackdrop() {
        if (!hasBackdrop) return
        backdrop = null
        photoFrosts = emptyMap()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // The window is laid out to fill the screen from its corner, but where it really sits is
        // read rather than assumed, so a patch lands on its photo either way.
        getLocationOnScreen(origin)
        canvas.save()
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        canvas.clipRect(area)

        val photos = coveredPhotos
        if (photos == null) {
            paintArea(canvas)
        } else {
            val moving = motion
            val now = SystemClock.uptimeMillis()
            photos.forEach { photo ->
                if (moving == null) {
                    moved.set(photo.bounds)
                    placed.set(photo.bounds)
                } else {
                    moving.shift(photo, now, moved)
                    moving.place(photo, now, placed)
                }
                paintPatch(canvas, photoFrosts[photo.id], backdrop)
            }
            // Redraw every frame while the page moves, so the patches travel with it — until the
            // prediction stops, after which nothing here changes until the next update.
            if (moving != null && moving.isMoving && now - moving.latestUptime <= PatchMotion.MAX_PREDICTION_MILLIS) {
                postInvalidateOnAnimation()
            }
        }
        canvas.restore()
    }

    private fun paintArea(canvas: Canvas) {
        patch.set(area)
        canvas.drawRect(patch, groundPaint)
        backdrop?.let {
            frostTarget.set(backdropArea)
            canvas.drawBitmap(it, null, frostTarget, frostPaint)
        }
    }

    /**
     * Paints the patch at [placed]; the photo itself, and so its [frost], is at [moved]. Without a
     * frost of its own, the whole window's [fallback] shows through it where it sits.
     */
    private fun paintPatch(canvas: Canvas, frost: PhotoFrost?, fallback: Bitmap?) {
        patch.set(placed)
        canvas.save()
        clip.rewind()
        clip.addRoundRect(patch, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.drawRect(patch, groundPaint)
        if (frost != null) {
            frostTarget.set(
                moved.left + frost.part.left * moved.width(),
                moved.top + frost.part.top * moved.height(),
                moved.left + frost.part.right * moved.width(),
                moved.top + frost.part.bottom * moved.height(),
            )
            canvas.drawBitmap(frost.bitmap, null, frostTarget, frostPaint)
        } else if (fallback != null) {
            frostTarget.set(backdropArea)
            canvas.drawBitmap(fallback, null, frostTarget, frostPaint)
        }
        canvas.restore()
    }

    companion object {
        /** Fully opaque: the window is translucent, so any alpha here would let the photo through. */
        val GROUND: Int = Color.parseColor("#FF1C1B1F")
        private const val CORNER_RADIUS_DP = 8f
    }
}
