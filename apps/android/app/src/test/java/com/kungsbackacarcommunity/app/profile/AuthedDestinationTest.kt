package com.kungsbackacarcommunity.app.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthedDestinationTest {
    @Test
    fun `loading maps to loading`() {
        assertEquals(AuthedDestination.Loading, authedDestination(ProfileState.Loading))
    }

    @Test
    fun `unavailable renders the main shell`() {
        assertEquals(AuthedDestination.Main, authedDestination(ProfileState.Unavailable))
    }

    @Test
    fun `a read error renders the main shell rather than staying stuck`() {
        assertEquals(AuthedDestination.Main, authedDestination(ProfileState.Error))
    }

    @Test
    fun `missing or incomplete profile requires onboarding`() {
        assertEquals(AuthedDestination.Onboarding, authedDestination(ProfileState.Loaded(null)))
        assertEquals(
            AuthedDestination.Onboarding,
            authedDestination(ProfileState.Loaded(UserProfile("Seb", null, onboardingComplete = false))),
        )
    }

    @Test
    fun `completed onboarding goes to main`() {
        assertEquals(
            AuthedDestination.Main,
            authedDestination(ProfileState.Loaded(UserProfile("Seb", "bio", onboardingComplete = true))),
        )
    }

    @Test
    fun `protected member data fails closed without an authoritative active profile`() {
        assertTrue(ProfileState.Loading.restrictsProtectedMemberData())
        assertTrue(ProfileState.Error.restrictsProtectedMemberData())
        assertTrue(ProfileState.Loaded(null).restrictsProtectedMemberData())
        assertTrue(
            ProfileState.Loaded(
                UserProfile("Seb", null, onboardingComplete = true, isRestricted = true),
            ).restrictsProtectedMemberData(),
        )
        assertFalse(
            ProfileState.Loaded(
                UserProfile("Seb", null, onboardingComplete = true, isRestricted = false),
            ).restrictsProtectedMemberData(),
        )
        assertFalse(ProfileState.Unavailable.restrictsProtectedMemberData())
    }
}
