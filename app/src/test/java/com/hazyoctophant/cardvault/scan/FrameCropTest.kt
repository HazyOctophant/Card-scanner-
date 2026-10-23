package com.hazyoctophant.cardvault.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCropTest {
    @Test
    fun tallViewCropsPhotoSides() {
        // 3:4 photo on a 9:20 phone screen: the photo's height fills the view, its sides are cut off.
        val r = FrameCrop.guideRect(photoW = 1440, photoH = 1920, viewW = 1080, viewH = 2400, guideWidthFraction = 0.72f, margin = 0f)
        // scale = 2400/1920 = 1.25; guide = 777.6 x 1086 view px -> 622 x 869 photo px, centered.
        assertEquals(622.0, r.width.toDouble(), 2.0)
        assertEquals(869.0, r.height.toDouble(), 2.0)
        assertEquals(720.0, (r.left + r.right) / 2.0, 1.0)
        assertEquals(960.0, (r.top + r.bottom) / 2.0, 1.0)
    }

    @Test
    fun cropNeverLeavesThePhoto() {
        val r = FrameCrop.guideRect(photoW = 400, photoH = 300, viewW = 1080, viewH = 2400, guideWidthFraction = 0.95f, margin = 0.3f)
        assertEquals(true, r.left >= 0 && r.top >= 0 && r.right <= 400 && r.bottom <= 300)
    }
}
