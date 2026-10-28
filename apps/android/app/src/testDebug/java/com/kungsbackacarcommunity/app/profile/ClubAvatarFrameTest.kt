package com.kungsbackacarcommunity.app.profile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.Coil
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.AeroOwnerHeader
import com.kungsbackacarcommunity.app.shell.AeroOwnerAvatarTag
import com.kungsbackacarcommunity.app.shell.AeroPageHeader
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Headless-only tests; execute centrally once the parent grants a Gradle slot. */
@RunWith(AndroidJUnit4::class)
class ClubAvatarFrameTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val requests = CopyOnWriteArrayList<Any>()
    private val logo = "https://firebasestorage.googleapis.com/v0/b/example.firebasestorage.app/o/" +
        "clubLogos%2Fclub_12345678%2Fowner_12345678%2Flogo.webp?alt=media"
    private fun badge(url: String? = null) = requireNotNull(ClubBadge.fromField(
        mapOf("clubId" to "club_12345678", "clubName" to "Östra Klubben", "logoUrl" to url),
    ))

    @Before fun interceptAllImages() {
        Coil.setImageLoader(ImageLoader.Builder(compose.activity).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    requests += chain.request.data
                    return ErrorResult(
                        drawable = null, request = chain.request,
                        throwable = IllegalStateException("Test image unavailable"),
                    )
                }
            })
        }.build())
    }
    @After fun resetImages() { Coil.reset() }

    @Test fun bothBadgesStayInsideAvatarAtOppositeCornersInLtrAndRtl() {
        val size = mutableStateOf(48.dp)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            KccTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides direction.value,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale.value),
                ) {
                    SupporterAvatarFrame(true, badge()) {
                        Box(Modifier.size(size.value).testTag("photo"))
                    }
                }
            }
        }
        for (avatarSize in listOf(48.dp, 112.dp)) {
            for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                compose.runOnIdle {
                    size.value = avatarSize; direction.value = layoutDirection
                    fontScale.value = if (avatarSize == 112.dp) 2f else 1f
                }
                val photo = compose.onNodeWithTag("photo").fetchSemanticsNode().boundsInRoot
                val club = compose.onNodeWithTag("club_avatar_badge").fetchSemanticsNode().boundsInRoot
                val crown = compose.onNodeWithTag("supporter_avatar_crown").fetchSemanticsNode().boundsInRoot
                assertTrue(club.left >= photo.left && club.top >= photo.top && club.right <= photo.right && club.bottom <= photo.bottom)
                assertTrue(crown.left >= photo.left && crown.top >= photo.top && crown.right <= photo.right && crown.bottom <= photo.bottom)
                assertTrue(club.bottom <= crown.top || club.right <= crown.left || crown.right <= club.left)
                assertEquals(photo.top, club.top, .5f)
                assertEquals(photo.bottom, crown.bottom, .5f)
            }
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun clearedMembershipRemovesOnlyClubBadgeAndUnsafeUrlNeverEntersCoil() {
        val affiliation = mutableStateOf<ClubBadge?>(badge("http://untrusted.example.test/logo"))
        compose.setContent {
            KccTheme {
                SupporterAvatarFrame(true, affiliation.value) { Box(Modifier.size(48.dp)) }
            }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.clubBadge_accessibility, "Östra Klubben")).assertIsDisplayed()
        compose.onNodeWithTag("supporter_avatar_crown").assertIsDisplayed()
        compose.runOnIdle { assertTrue(requests.isEmpty()); affiliation.value = null }
        compose.onNodeWithTag("club_avatar_badge").assertDoesNotExist()
        compose.onNodeWithTag("supporter_avatar_crown").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun trustedImageFailureKeepsTheAccessibleFallbackWithoutLosingSupporter() {
        compose.setContent {
            KccTheme { SupporterAvatarFrame(true, badge(logo)) { Box(Modifier.size(48.dp)) } }
        }
        compose.onNodeWithContentDescription("Club: Östra Klubben").assertIsDisplayed()
        compose.waitForIdle()
        compose.waitUntil(5_000) { requests.contains(logo) }
        compose.onNodeWithTag("club_avatar_badge").assertIsDisplayed()
        compose.onNodeWithTag("supporter_avatar_crown").assertIsDisplayed()
        assertEquals(listOf(logo), requests.toList())
    }

    @Test fun ownerHeaderRemainsOneProfileActionAndUsesCanonicalBadge() {
        var opened = 0
        val owner = mutableStateOf(AeroOwnerHeader(null, true, { opened++ }, badge()))
        compose.setContent { KccTheme { AeroPageHeader("Profile", owner.value) } }
        compose.onNodeWithTag(AeroOwnerAvatarTag).assertHasClickAction().performClick()
        assertEquals(1, opened)
        compose.onNodeWithTag("club_avatar_badge", useUnmergedTree = true).assertExists()
        compose.runOnIdle { owner.value = owner.value.copy(clubBadge = null) }
        compose.onNodeWithTag("club_avatar_badge", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("supporter_avatar_crown", useUnmergedTree = true).assertExists()
    }
}
