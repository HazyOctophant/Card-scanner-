package com.hazyoctophant.cardvault.scan

import kotlin.math.max
import kotlin.math.roundToInt

data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Works out which part of a captured photo was inside the on-screen card guide. */
object FrameCrop {
    const val CARD_ASPECT = 63f / 88f

    /**
     * The preview shows the photo scaled to fill the view and center-cropped. The guide is a card-shaped
     * box [guideWidthFraction] of the view's width, centered. Returns that box in photo pixels,
     * enlarged by [margin] so a slightly misaligned card isn't clipped.
     */
    fun guideRect(photoW: Int, photoH: Int, viewW: Int, viewH: Int, guideWidthFraction: Float, margin: Float = 0.05f): CropRect {
        if (viewW <= 0 || viewH <= 0) return CropRect(0, 0, photoW, photoH)
        val scale = max(viewW.toFloat() / photoW, viewH.toFloat() / photoH)
        val guideW = viewW * guideWidthFraction * (1 + margin) / scale
        val guideH = viewW * guideWidthFraction / CARD_ASPECT * (1 + margin) / scale
        val cx = photoW / 2f
        val cy = photoH / 2f
        return CropRect(
            left = (cx - guideW / 2).roundToInt().coerceIn(0, photoW - 1),
            top = (cy - guideH / 2).roundToInt().coerceIn(0, photoH - 1),
            right = (cx + guideW / 2).roundToInt().coerceIn(1, photoW),
            bottom = (cy + guideH / 2).roundToInt().coerceIn(1, photoH),
        )
    }
}
