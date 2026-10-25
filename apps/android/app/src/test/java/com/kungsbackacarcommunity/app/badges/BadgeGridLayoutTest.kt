package com.kungsbackacarcommunity.app.badges

import org.junit.Assert.assertEquals
import org.junit.Test

class BadgeGridLayoutTest {
    @Test fun phoneWidthsKeepThreeReadableColumns() {
        listOf(328f, 358f, 398f).forEach { assertEquals(3, badgeGridColumns(it, 1f, 3)) }
    }

    @Test fun largeTextReducesColumnsWithoutDroppingAwards() {
        assertEquals(2, badgeGridColumns(328f, 1.5f, 3))
        assertEquals(1, badgeGridColumns(328f, 2f, 3))
    }

    @Test fun expandedWallNeverExceedsItsRequestedColumns() {
        assertEquals(3, badgeGridColumns(328f, 1f, 4))
        assertEquals(4, badgeGridColumns(800f, 1f, 4))
    }

    @Test fun transientConstraintsAndInvalidScaleStillGiveOneColumn() {
        assertEquals(1, badgeGridColumns(0f, 1f, 3))
        assertEquals(1, badgeGridColumns(Float.POSITIVE_INFINITY, 1f, 3))
        assertEquals(3, badgeGridColumns(328f, Float.NaN, 3))
        assertEquals(1, badgeGridColumns(328f, 1f, 0))
    }
}
