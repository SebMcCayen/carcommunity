package com.kungsbackacarcommunity.app.convoy

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure HttpsError-code → user-facing-error mapping, per callable. */
class ConvoyErrorMapperTest {

    @Test
    fun `auth codes map identically across every callable`() {
        val mappers =
            listOf(
                ConvoyErrorMapper::mapCreate,
                ConvoyErrorMapper::mapRespond,
                ConvoyErrorMapper::mapStart,
                ConvoyErrorMapper::mapEnd,
                ConvoyErrorMapper::mapList,
            )
        for (map in mappers) {
            assertEquals(ConvoyActionError.SignedOut, map(ConvoyErrorCode.Unauthenticated))
            assertEquals(ConvoyActionError.NotMember, map(ConvoyErrorCode.PermissionDenied))
        }
    }

    @Test
    fun `create precondition means no valid invitees`() {
        assertEquals(ConvoyActionError.NoInvitees, ConvoyErrorMapper.mapCreate(ConvoyErrorCode.FailedPrecondition))
        assertEquals(ConvoyActionError.Invalid, ConvoyErrorMapper.mapCreate(ConvoyErrorCode.InvalidArgument))
    }

    @Test
    fun `respond not-found and precondition both collapse to invite gone`() {
        assertEquals(ConvoyActionError.InviteGone, ConvoyErrorMapper.mapRespond(ConvoyErrorCode.NotFound))
        assertEquals(ConvoyActionError.InviteGone, ConvoyErrorMapper.mapRespond(ConvoyErrorCode.FailedPrecondition))
    }

    @Test
    fun `start distinguishes not-found (non-owner) from cannot-start`() {
        assertEquals(ConvoyActionError.NotFound, ConvoyErrorMapper.mapStart(ConvoyErrorCode.NotFound))
        assertEquals(ConvoyActionError.CannotStart, ConvoyErrorMapper.mapStart(ConvoyErrorCode.FailedPrecondition))
    }

    @Test
    fun `end distinguishes not-found from already-ended`() {
        assertEquals(ConvoyActionError.NotFound, ConvoyErrorMapper.mapEnd(ConvoyErrorCode.NotFound))
        assertEquals(ConvoyActionError.AlreadyEnded, ConvoyErrorMapper.mapEnd(ConvoyErrorCode.FailedPrecondition))
    }

    /**
     * `clear` is the ONE convoy callable where permission-denied does not mean
     * "you are not in this convoy". It means "you are, but you neither set this
     * destination nor own the convoy". Reporting that as NotMember sends a member
     * looking for a membership problem they do not have.
     */
    @Test
    fun `clearDestination reports permission-denied as NotAllowed, not NotMember`() {
        assertEquals(
            ConvoyActionError.NotAllowed,
            ConvoyErrorMapper.mapClearDestination(ConvoyErrorCode.PermissionDenied),
        )
        // ...while every other convoy mapper keeps reserving it for a genuine
        // non-member, so the distinction above is a real difference and not just
        // a renamed constant.
        assertEquals(
            ConvoyActionError.NotMember,
            ConvoyErrorMapper.mapSetDestination(ConvoyErrorCode.PermissionDenied),
        )
        assertEquals(
            ConvoyActionError.NotMember,
            ConvoyErrorMapper.mapEnd(ConvoyErrorCode.PermissionDenied),
        )
    }

    @Test
    fun `unknown codes collapse to Generic`() {
        assertEquals(ConvoyActionError.Generic, ConvoyErrorMapper.mapList(ConvoyErrorCode.Other))
        assertEquals(ConvoyActionError.Generic, ConvoyErrorMapper.mapCreate(ConvoyErrorCode.Other))
    }
}
