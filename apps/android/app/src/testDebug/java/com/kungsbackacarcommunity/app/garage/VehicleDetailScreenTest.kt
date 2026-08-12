package com.kungsbackacarcommunity.app.garage

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose UI tests for the car-detail page + tap-to-open (car-detail-gallery). */
@RunWith(AndroidJUnit4::class)
class VehicleDetailScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun vehicle() =
        Vehicle(
            id = "v1",
            make = "Volvo",
            model = "240",
            modelYear = 1988,
            powertrain = VehiclePowertrain.PETROL,
            engineDescription = "B230",
            modifications = "Lowered on Bilstein",
        )

    @Test
    fun tappingACard_opensTheDetailPage() {
        var opened: Vehicle? = null
        composeTestRule.setContent {
            KccTheme {
                GarageScreen(
                    state = GarageState.Loaded(listOf(vehicle())),
                    onAdd = {},
                    onEdit = {},
                    onDelete = {},
                    onOpen = { opened = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Volvo 240 (1988)").performClick()
        assertEquals("v1", opened?.id)
    }

    @Test
    fun detailPage_showsAllInfo_andHidesAddMoreWithoutUploader() {
        composeTestRule.setContent {
            KccTheme {
                VehicleDetailScreen(
                    vehicle = vehicle(),
                    onEdit = {},
                    onDelete = {},
                    onSetMain = {},
                    // onAddPhoto left null: no uploader wired (config-less build).
                )
            }
        }
        composeTestRule.onNodeWithText("Volvo 240 (1988)").assertIsDisplayed()
        composeTestRule.onNodeWithText("B230").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Lowered on Bilstein").performScrollTo().assertIsDisplayed()
        // With no uploader the "add more photos" affordance is hidden entirely.
        composeTestRule.onNodeWithText(str(R.string.garage_photoAddMore)).assertDoesNotExist()
    }

    @Test
    fun detailPage_addMore_isEnabledAndInvokesPicker_whenUploaderWired() {
        var picks = 0
        composeTestRule.setContent {
            KccTheme {
                VehicleDetailScreen(
                    vehicle = vehicle(),
                    onEdit = {},
                    onDelete = {},
                    onSetMain = {},
                    onAddPhoto = { picks++ },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.garage_photoAddMore))
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, picks)
    }

    @Test
    fun galleryActions_setCoverAndRemove_invokeCallbacks() {
        val photoPaths = listOf("vehicleImages/u/v1/a.jpg", "vehicleImages/u/v1/b.jpg")
        var covered: String? = null
        var removed: String? = null
        composeTestRule.setContent {
            KccTheme {
                // Match the production layout: VehicleGalleryPager renders inside
                // AeroPage's Column, so its pager / counter / thumbnail strip /
                // action row stack vertically. Without a Column they would all be
                // placed at the setContent root origin and overlap, and a
                // thumbnail tap would land on the set-cover button drawn on top of
                // it instead of paging the gallery.
                Column {
                    VehicleGalleryPager(
                        photoPaths = photoPaths,
                        onSetCover = { covered = it },
                        onRemovePhoto = { removed = it },
                    )
                }
            }
        }
        // The current photo starts on the cover (index 0), so "set as cover" is
        // disabled there.
        composeTestRule.onNodeWithTag(VEHICLE_GALLERY_SET_COVER_TAG).assertIsNotEnabled()

        // Navigate to the non-cover photo via its thumbnail; "set as cover" now
        // becomes enabled and, when tapped, fires onSetCover with that photo.
        // The tap animates the pager to that page, and the fling settles on the
        // real-time clock — waitForIdle can race ahead of it (as with a debounced
        // delay), so poll until "set as cover" actually flips to enabled before
        // exercising it.
        composeTestRule.onNodeWithTag(vehicleGalleryThumbnailTag(1)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(VEHICLE_GALLERY_SET_COVER_TAG)
                .fetchSemanticsNodes()
                .any { SemanticsProperties.Disabled !in it.config }
        }
        composeTestRule.onNodeWithTag(VEHICLE_GALLERY_SET_COVER_TAG)
            .assertIsEnabled()
            .performClick()
        assertEquals("vehicleImages/u/v1/b.jpg", covered)

        // "remove" on the current (now second) photo opens a confirm dialog then
        // fires onRemovePhoto with that path.
        composeTestRule.onNodeWithTag(VEHICLE_GALLERY_REMOVE_TAG).performClick()
        composeTestRule.onNodeWithText(str(R.string.garage_photoRemoveConfirmButton)).performClick()
        assertEquals("vehicleImages/u/v1/b.jpg", removed)
    }

    @Test
    fun detailPage_editAndDelete_invokeCallbacks() {
        var edited = 0
        var deleted = 0
        composeTestRule.setContent {
            KccTheme {
                VehicleDetailScreen(
                    vehicle = vehicle(),
                    onEdit = { edited++ },
                    onDelete = { deleted++ },
                    onSetMain = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.garage_editVehicle)).performScrollTo().performClick()
        assertEquals(1, edited)
        // Delete asks for confirmation first.
        composeTestRule.onNodeWithText(str(R.string.garage_deleteVehicle)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.garage_deleteConfirmButton)).performClick()
        assertEquals(1, deleted)
    }

    /**
     * The thumbnail strip is built to scale to N photos. With a strip wider than
     * the screen, a thumbnail past the visible width must still be reachable —
     * this fails against a non-scrollable Row (it can't scroll to the tile) and
     * passes with the LazyRow. Driven through the [VehicleGalleryPager] seam
     * because the single-photo data model never yields a multi-photo strip via
     * the public screen today.
     */
    @Test
    fun thumbnailStrip_scrollsToReachLateThumbnails() {
        val photoPaths = List(10) { "vehicleImages/u/v1/photo_$it.jpg" }
        composeTestRule.setContent {
            KccTheme {
                VehicleGalleryPager(photoPaths = photoPaths)
            }
        }
        // A late thumbnail past the screen width is unreachable without a
        // scrollable strip; scrolling the LazyRow brings it into view.
        composeTestRule.onNodeWithTag(VEHICLE_GALLERY_STRIP_TAG).performScrollToIndex(9)
        composeTestRule.onNodeWithTag(vehicleGalleryThumbnailTag(9)).assertIsDisplayed()
    }
}
