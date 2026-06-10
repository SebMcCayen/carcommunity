import assert from 'node:assert/strict';
import test from 'node:test';

import {
  canAccessLiveLocationAdminSummary,
  canAccessAdminFeatures,
  canAccessMemberFeatures,
  canShareOwnLiveLocation,
  canViewOtherLiveLocations,
  hasMemberEntitlement,
  isAdminRole,
  isOwnerRole,
  isSuspendedStatus,
} from '@carcommunity/shared/users';
import {
  canViewEventTeaser,
  canViewEventDetails,
  canRsvpToEvent,
  canAccessEventAdmin,
} from '@carcommunity/shared/events';

// ---------------------------------------------------------------------------
// isAdminRole
// ---------------------------------------------------------------------------

test('isAdminRole returns true only for admin role', () => {
  assert.equal(isAdminRole('admin'), true);
  assert.equal(isAdminRole('owner'), false);
  assert.equal(isAdminRole('user'), false);
});

// ---------------------------------------------------------------------------
// isOwnerRole
// ---------------------------------------------------------------------------

test('isOwnerRole returns true only for owner role', () => {
  assert.equal(isOwnerRole('owner'), true);
  assert.equal(isOwnerRole('admin'), false);
  assert.equal(isOwnerRole('user'), false);
});

// ---------------------------------------------------------------------------
// hasMemberEntitlement
// ---------------------------------------------------------------------------

test('hasMemberEntitlement returns true only for member_monthly', () => {
  assert.equal(hasMemberEntitlement('member_monthly'), true);
  assert.equal(hasMemberEntitlement('none'), false);
});

// ---------------------------------------------------------------------------
// isSuspendedStatus
// ---------------------------------------------------------------------------

test('isSuspendedStatus returns true for temporarily_suspended and permanently_suspended', () => {
  assert.equal(isSuspendedStatus('temporarily_suspended'), true);
  assert.equal(isSuspendedStatus('permanently_suspended'), true);
  assert.equal(isSuspendedStatus('active'), false);
  assert.equal(isSuspendedStatus('warned'), false);
  assert.equal(isSuspendedStatus('deleted'), false);
});

// ---------------------------------------------------------------------------
// canAccessMemberFeatures — required test cases from spec
// ---------------------------------------------------------------------------

test('suspended user cannot access member features', () => {
  assert.equal(
    canAccessMemberFeatures({ role: 'user', status: 'temporarily_suspended', subscriptionEntitlement: 'member_monthly' }),
    false,
    'temporarily_suspended with member_monthly must be denied',
  );
  assert.equal(
    canAccessMemberFeatures({ role: 'user', status: 'permanently_suspended', subscriptionEntitlement: 'member_monthly' }),
    false,
    'permanently_suspended with member_monthly must be denied',
  );
});

test('active member with member_monthly can access member features', () => {
  assert.equal(
    canAccessMemberFeatures({ role: 'user', status: 'active', subscriptionEntitlement: 'member_monthly' }),
    true,
  );
});

test('active user without subscription cannot access member features', () => {
  assert.equal(
    canAccessMemberFeatures({ role: 'user', status: 'active', subscriptionEntitlement: 'none' }),
    false,
  );
});

// ---------------------------------------------------------------------------
// canAccessMemberFeatures + deleted — required test case from spec
// ---------------------------------------------------------------------------

test('deleted user cannot access app features', () => {
  assert.equal(
    canAccessMemberFeatures({ role: 'user', status: 'deleted', subscriptionEntitlement: 'member_monthly' }),
    false,
    'deleted user with member_monthly subscription must be denied member features',
  );
  assert.equal(
    canAccessAdminFeatures({ role: 'admin', status: 'deleted' }),
    false,
    'deleted admin must be denied admin features',
  );
  assert.equal(
    canShareOwnLiveLocation({ status: 'deleted' }),
    false,
    'deleted user must not be able to share own live location',
  );
  assert.equal(
    canViewOtherLiveLocations({ role: 'user', status: 'deleted', subscriptionEntitlement: 'member_monthly' }),
    false,
    'deleted user with member_monthly subscription must be denied live location markers',
  );
});

// ---------------------------------------------------------------------------
// canAccessAdminFeatures — required test cases from spec
// ---------------------------------------------------------------------------

test('admin can access admin features', () => {
  assert.equal(canAccessAdminFeatures({ role: 'admin', status: 'active' }), true);
});

test('owner can access admin features', () => {
  assert.equal(canAccessAdminFeatures({ role: 'owner', status: 'active' }), true);
});

test('regular user cannot access admin features', () => {
  assert.equal(canAccessAdminFeatures({ role: 'user', status: 'active' }), false);
});

test('suspended admin cannot access admin features', () => {
  assert.equal(canAccessAdminFeatures({ role: 'admin', status: 'temporarily_suspended' }), false);
  assert.equal(canAccessAdminFeatures({ role: 'admin', status: 'permanently_suspended' }), false);
});

// ---------------------------------------------------------------------------
// canShareOwnLiveLocation
// ---------------------------------------------------------------------------

test('free user can share own live location if not suspended', () => {
  assert.equal(
    canShareOwnLiveLocation({ status: 'active' }),
    true,
    'active free user may share own location',
  );
  assert.equal(
    canShareOwnLiveLocation({ status: 'temporarily_suspended' }),
    false,
    'temporarily_suspended user may not share own location',
  );
  assert.equal(
    canShareOwnLiveLocation({ status: 'permanently_suspended' }),
    false,
    'permanently_suspended user may not share own location',
  );
});

test('warned user can still share own live location', () => {
  assert.equal(canShareOwnLiveLocation({ status: 'warned' }), true);
});

// ---------------------------------------------------------------------------
// canViewOtherLiveLocations — required test cases from spec
// ---------------------------------------------------------------------------

test('normal member can view other live locations', () => {
  assert.equal(
    canViewOtherLiveLocations({ role: 'user', status: 'active', subscriptionEntitlement: 'member_monthly' }),
    true,
  );
});

test('free user cannot view other live locations', () => {
  assert.equal(
    canViewOtherLiveLocations({ role: 'user', status: 'active', subscriptionEntitlement: 'none' }),
    false,
  );
});

test('admin cannot view member marker APIs without member entitlement', () => {
  assert.equal(
    canViewOtherLiveLocations({ role: 'admin', status: 'active', subscriptionEntitlement: 'none' }),
    false,
  );
});

test('owner cannot view member marker APIs without member entitlement', () => {
  assert.equal(
    canViewOtherLiveLocations({ role: 'owner', status: 'active', subscriptionEntitlement: 'none' }),
    false,
  );
});

test('suspended member cannot view other live locations', () => {
  assert.equal(
    canViewOtherLiveLocations({ role: 'user', status: 'temporarily_suspended', subscriptionEntitlement: 'member_monthly' }),
    false,
  );
});

test('admin and owner can access live location admin summary', () => {
  assert.equal(
    canAccessLiveLocationAdminSummary({ role: 'admin', status: 'active', subscriptionEntitlement: 'none' }),
    true,
  );
  assert.equal(
    canAccessLiveLocationAdminSummary({ role: 'owner', status: 'active', subscriptionEntitlement: 'none' }),
    true,
  );
  assert.equal(
    canAccessLiveLocationAdminSummary({ role: 'user', status: 'active', subscriptionEntitlement: 'member_monthly' }),
    false,
  );
});

// ---------------------------------------------------------------------------
// canViewEventTeaser
// ---------------------------------------------------------------------------

test('active free user can view event teasers', () => {
  assert.equal(canViewEventTeaser({ role: 'user', status: 'active' }), true);
});

test('warned user can view event teasers', () => {
  assert.equal(canViewEventTeaser({ role: 'user', status: 'warned' }), true);
});

test('suspended user cannot view event teasers', () => {
  assert.equal(canViewEventTeaser({ role: 'user', status: 'temporarily_suspended' }), false);
  assert.equal(canViewEventTeaser({ role: 'user', status: 'permanently_suspended' }), false);
});

test('deleted user cannot view event teasers', () => {
  assert.equal(canViewEventTeaser({ role: 'user', status: 'deleted' }), false);
});

// ---------------------------------------------------------------------------
// canViewEventDetails
// ---------------------------------------------------------------------------

test('active member can view event details', () => {
  assert.equal(
    canViewEventDetails({ role: 'user', status: 'active', subscriptionEntitlement: 'member_monthly' }),
    true,
  );
});

test('active free user cannot view event details', () => {
  assert.equal(
    canViewEventDetails({ role: 'user', status: 'active', subscriptionEntitlement: 'none' }),
    false,
  );
});

test('suspended member cannot view event details', () => {
  assert.equal(
    canViewEventDetails({ role: 'user', status: 'temporarily_suspended', subscriptionEntitlement: 'member_monthly' }),
    false,
  );
  assert.equal(
    canViewEventDetails({ role: 'user', status: 'permanently_suspended', subscriptionEntitlement: 'member_monthly' }),
    false,
  );
});

test('deleted user cannot view event details', () => {
  assert.equal(
    canViewEventDetails({ role: 'user', status: 'deleted', subscriptionEntitlement: 'member_monthly' }),
    false,
  );
});

// ---------------------------------------------------------------------------
// canRsvpToEvent
// ---------------------------------------------------------------------------

test('active member can RSVP to event', () => {
  assert.equal(
    canRsvpToEvent({ role: 'user', status: 'active', subscriptionEntitlement: 'member_monthly' }),
    true,
  );
});

test('active free user cannot RSVP to event', () => {
  assert.equal(
    canRsvpToEvent({ role: 'user', status: 'active', subscriptionEntitlement: 'none' }),
    false,
  );
});

test('suspended member cannot RSVP to event', () => {
  assert.equal(
    canRsvpToEvent({ role: 'user', status: 'temporarily_suspended', subscriptionEntitlement: 'member_monthly' }),
    false,
  );
});

test('deleted user cannot RSVP to event', () => {
  assert.equal(
    canRsvpToEvent({ role: 'user', status: 'deleted', subscriptionEntitlement: 'member_monthly' }),
    false,
  );
});

// ---------------------------------------------------------------------------
// canAccessEventAdmin
// ---------------------------------------------------------------------------

test('admin can access event admin', () => {
  assert.equal(canAccessEventAdmin({ role: 'admin', status: 'active' }), true);
});

test('owner can access event admin', () => {
  assert.equal(canAccessEventAdmin({ role: 'owner', status: 'active' }), true);
});

test('regular user cannot access event admin', () => {
  assert.equal(canAccessEventAdmin({ role: 'user', status: 'active' }), false);
});

test('member cannot access event admin without admin role', () => {
  assert.equal(canAccessEventAdmin({ role: 'user', status: 'active' }), false);
});

test('suspended admin cannot access event admin', () => {
  assert.equal(canAccessEventAdmin({ role: 'admin', status: 'temporarily_suspended' }), false);
  assert.equal(canAccessEventAdmin({ role: 'admin', status: 'permanently_suspended' }), false);
});

test('deleted admin cannot access event admin', () => {
  assert.equal(canAccessEventAdmin({ role: 'admin', status: 'deleted' }), false);
});
