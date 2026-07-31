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
        assertEquals(ConvoyActionError.SignedOut, ConvoyErrorMapper.mapEnd(ConvoyErrorCode.Unauthenticated))
    }

    /**
     * `leave` is the ONE callable where `failed-precondition` is overloaded across
     * two backend cases (already-ended / not-an-accepted-member), separated only by
     * message text the code-only client never reads. It must map to the neutral
     * [ConvoyActionError.LeaveFailed] rather than asserting one specific cause like
     * [ConvoyActionError.AlreadyEnded], which would be wrong half the time. A
     * genuine non-member — including someone retrying a leave that already
     * succeeded — is still `not-found`.
     */
    @Test
    fun `leave maps precondition to neutral LeaveFailed, not AlreadyEnded`() {
        assertEquals(ConvoyActionError.NotFound, ConvoyErrorMapper.mapLeave(ConvoyErrorCode.NotFound))
        assertEquals(
            ConvoyActionError.LeaveFailed,
            ConvoyErrorMapper.mapLeave(ConvoyErrorCode.FailedPrecondition),
        )
        assertEquals(ConvoyActionError.SignedOut, ConvoyErrorMapper.mapLeave(ConvoyErrorCode.Unauthenticated))
        assertEquals(ConvoyActionError.NotMember, ConvoyErrorMapper.mapLeave(ConvoyErrorCode.PermissionDenied))
    }

    /**
     * TWO convoy callables treat permission-denied as "you are in this convoy, but
     * you may not do THIS", rather than the usual "you are not in this convoy":
     * `clearDestination` (you neither set the destination nor lead the convoy) and
     * `end` (ending is for EVERYONE, so it is leader-only — a member who is not the
     * leader is refused by name, while a total outsider still gets not-found so a
     * convoy cannot be probed). Reporting either as NotMember sends a member
     * looking for a membership problem they do not have.
     */
    @Test
    fun `clearDestination and end report permission-denied as member-but-not-permitted`() {
        assertEquals(
            ConvoyActionError.NotAllowed,
            ConvoyErrorMapper.mapClearDestination(ConvoyErrorCode.PermissionDenied),
        )
        // `end` gets its OWN case, not NotAllowed: the useful thing to tell this
        // caller is "only the leader can end it — you can leave instead", which
        // the destination message would not say.
        assertEquals(
            ConvoyActionError.NotLeader,
            ConvoyErrorMapper.mapEnd(ConvoyErrorCode.PermissionDenied),
        )
        // ...while the other convoy mappers keep reserving it for a genuine
        // non-member, so the distinction above is a real difference and not just
        // a renamed constant.
        assertEquals(
            ConvoyActionError.NotMember,
            ConvoyErrorMapper.mapSetDestination(ConvoyErrorCode.PermissionDenied),
        )
        assertEquals(
            ConvoyActionError.NotMember,
            ConvoyErrorMapper.mapLeave(ConvoyErrorCode.PermissionDenied),
        )
    }

    @Test
    fun `unknown codes collapse to Generic`() {
        assertEquals(ConvoyActionError.Generic, ConvoyErrorMapper.mapList(ConvoyErrorCode.Other))
        assertEquals(ConvoyActionError.Generic, ConvoyErrorMapper.mapCreate(ConvoyErrorCode.Other))
    }
}
