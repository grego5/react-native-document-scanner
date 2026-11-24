package com.margelo.nitro.documentscanner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Main thread: the text camera's guide, `TextGuide`: the box marking the region, coloured by [TextScanStatus], and the
 * status's label centred below or above it, as wide as the box at most and up to two lines. The label takes its
 * direction from its text, and screen readers announce its changes. A hidden guide draws nothing, but its box is still
 * laid out and reported to [laidOut], in pixels, with the label's while one shows. Its host lays it out: a React Native
 * parent never passes a child's layout request on, so each change asks [relayout] for a pass instead.
 */
@SuppressLint("ViewConstructor")
internal class TextGuideOverlay(context: Context, private val relayout: () -> Unit,
  private val laidOut: (box: RectF, label: RectF?) -> Unit) : FrameLayout(context) {
  private val density = context.resources.displayMetrics.density
  private var region: TextRegion? = null
  private var guide: TextGuide? = null
  private var status = TextScanStatus.OFF
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
  private val box = RectF()
  private val background = GradientDrawable()
  private val label = TextView(context).apply {
    gravity = Gravity.CENTER
    maxLines = 2
    ellipsize = TextUtils.TruncateAt.END
    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    textAlignment = View.TEXT_ALIGNMENT_GRAVITY
    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    background = this@TextGuideOverlay.background
    visibility = View.GONE
  }

  init {
    setWillNotDraw(false)
    addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    applyStyle()
  }

  fun update(region: TextRegion, guide: TextGuide?) {
    if (region == this.region && guide == this.guide) return
    val restyle = guide?.labelStyle != this.guide?.labelStyle
    this.region = region
    this.guide = guide
    if (restyle) applyStyle()
    applyLabel()
    relayout()
    invalidate()
  }

  fun show(status: TextScanStatus) {
    if (status == this.status) return
    this.status = status
    // Another label, or none, has another size.
    applyLabel()
    relayout()
    invalidate()
  }

  private fun applyStyle() {
    val style = guide?.labelStyle
    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, (style?.fontSize ?: DEFAULT_FONT_SIZE).toFloat())
    label.typeface = if (style?.fontWeight == TextGuideFontWeight.BOLD) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    label.setTextColor(style?.color?.argb() ?: DEFAULT_LABEL_COLOR)
    background.setColor(style?.backgroundColor?.argb() ?: DEFAULT_LABEL_BACKGROUND)
    background.cornerRadius = px(style?.radius ?: DEFAULT_LABEL_RADIUS)
    val padding = px(style?.padding ?: DEFAULT_LABEL_PADDING).toInt()
    label.setPadding(padding, padding, padding, padding)
  }

  private fun applyLabel() {
    val text = if (guide?.hidden == true) null else labelOf(status)
    if (text.isNullOrEmpty()) {
      label.visibility = View.GONE
      return
    }
    if (label.text.toString() != text) label.text = text
    label.visibility = View.VISIBLE
  }

  /** The label for [status]; none while the camera is off or starting, since the guide shows a scan's progress. */
  private fun labelOf(status: TextScanStatus): String? {
    val labels = guide?.labels ?: return null
    val own = when (status) {
      TextScanStatus.OFF, TextScanStatus.STARTING -> return null
      TextScanStatus.READY -> labels.ready
      TextScanStatus.FOCUSING -> labels.focusing
      TextScanStatus.BLURRED -> labels.blurred
      TextScanStatus.CAPTURING -> labels.capturing
    }
    return own ?: labels.ready
  }

  private fun colorOf(status: TextScanStatus): Int {
    val colors = guide?.colors
    val ready = colors?.ready?.argb() ?: DEFAULT_COLOR
    return when (status) {
      TextScanStatus.OFF, TextScanStatus.STARTING, TextScanStatus.READY -> ready
      TextScanStatus.FOCUSING -> colors?.focusing?.argb() ?: ready
      TextScanStatus.BLURRED -> colors?.blurred?.argb() ?: BLURRED_COLOR
      TextScanStatus.CAPTURING -> colors?.capturing?.argb() ?: ready
    }
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val width = MeasureSpec.getSize(widthMeasureSpec)
    val height = MeasureSpec.getSize(heightMeasureSpec)
    setMeasuredDimension(width, height)
    val region = region
    if (label.visibility == View.GONE || region == null) return
    val maxWidth = (region.width * width).toInt().coerceAtLeast(1)
    label.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    val region = region ?: return
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return
    box.set((region.x * width).toFloat(), (region.y * height).toFloat(),
      ((region.x + region.width) * width).toFloat(), ((region.y + region.height) * height).toFloat())
    val half = strokeWidth() / 2
    val outer = RectF(box.left - half, box.top - half, box.right + half, box.bottom + half)
    if (label.visibility == View.GONE) {
      laidOut(outer, null)
      return
    }
    val offset = px(guide?.labelOffset ?: DEFAULT_LABEL_OFFSET)
    val labelWidth = label.measuredWidth
    val labelHeight = label.measuredHeight
    val x = (outer.centerX() - labelWidth / 2f).toInt().coerceIn(0, (width - labelWidth).coerceAtLeast(0))
    val y = if (guide?.labelPlacement == TextGuideLabelPlacement.TOP) outer.top - offset - labelHeight else outer.bottom + offset
    val top = y.toInt().coerceIn(0, (height - labelHeight).coerceAtLeast(0))
    label.layout(x, top, x + labelWidth, top + labelHeight)
    laidOut(outer, RectF(x.toFloat(), top.toFloat(), (x + labelWidth).toFloat(), (top + labelHeight).toFloat()))
  }

  override fun onDraw(canvas: Canvas) {
    if (region == null || guide?.hidden == true || box.isEmpty) return
    val radius = px(guide?.radius ?: DEFAULT_RADIUS)
    stroke.strokeWidth = strokeWidth()
    stroke.color = colorOf(status)
    canvas.drawRoundRect(box, radius, radius, stroke)
  }

  private fun strokeWidth() = if (guide?.hidden == true) 0f else px(guide?.strokeWidth ?: DEFAULT_STROKE)
  private fun px(points: Double) = (points * density).toFloat()
  private fun Double.argb() = toLong().toInt()

  companion object {
    const val DEFAULT_COLOR = 0xFF61E1CF.toInt()
    /** Amber: the print is blurred, a hint to move back or hold still rather than a failure. */
    const val BLURRED_COLOR = 0xFFFFB300.toInt()
    const val DEFAULT_STROKE = 2.0
    const val DEFAULT_RADIUS = 8.0
    const val DEFAULT_FONT_SIZE = 14.0
    const val DEFAULT_LABEL_COLOR = 0xFFFFFFFF.toInt()
    const val DEFAULT_LABEL_BACKGROUND = 0x99000000.toInt()
    const val DEFAULT_LABEL_PADDING = 6.0
    const val DEFAULT_LABEL_RADIUS = 6.0
    const val DEFAULT_LABEL_OFFSET = 12.0
  }
}
