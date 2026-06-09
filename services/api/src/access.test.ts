import assert from 'node:assert/strict';
import test from 'node:test';

import {
  canAccessAdminFeatures,
  canAccessMemberFeatures,
  canShareOwnLiveLocation,
  hasMemberEntitlement,
  isAdminRole,
  isOwnerRole,
  isSuspendedStatus,
} from '@carcommunity/shared/users';
import { canViewOtherUsersLiveLocation } from '@carcommunity/shared/live-location';

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
// canViewOtherUsersLiveLocation — required test cases from spec
// ---------------------------------------------------------------------------

test('normal member can view other live locations', () => {
  assert.equal(
    canViewOtherUsersLiveLocation({ role: 'user', status: 'active', subscriptionEntitlement: 'member_monthly' }),
    true,
  );
});

test('free user cannot view other live locations', () => {
  assert.equal(
    canViewOtherUsersLiveLocation({ role: 'user', status: 'active', subscriptionEntitlement: 'none' }),
    false,
  );
});

test('admin can view other live locations without subscription', () => {
  assert.equal(
    canViewOtherUsersLiveLocation({ role: 'admin', status: 'active', subscriptionEntitlement: 'none' }),
    true,
  );
});

test('owner can view other live locations without subscription', () => {
  assert.equal(
    canViewOtherUsersLiveLocation({ role: 'owner', status: 'active', subscriptionEntitlement: 'none' }),
    true,
  );
});

test('suspended member cannot view other live locations', () => {
  assert.equal(
    canViewOtherUsersLiveLocation({ role: 'user', status: 'temporarily_suspended', subscriptionEntitlement: 'member_monthly' }),
    false,
  );
});
