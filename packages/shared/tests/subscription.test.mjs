import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

import {
  PLUS_MONTHLY_PRODUCT_ID,
  SUBSCRIPTION_CAPABILITY_PROFILES,
  SUPPORTER_MONTHLY_PRODUCT_ID,
  grantsLegacyActiveMember,
  hasSubscriptionCapability,
  isTierAtLeast,
  isTierCapabilitySuperset,
  resolveSubscriptionTier,
  subscriptionTierForLegacyEntitlement,
} from '../dist/subscription.js';

const subscriptionSchema = JSON.parse(
  readFileSync(
    new URL('../../../contracts/schemas/subscription.schema.json', import.meta.url),
    'utf8',
  ),
);

describe('subscription tier contract', () => {
  it('keeps product ids, monthly list prices, and the no-discount decision exact', () => {
    assert.equal(PLUS_MONTHLY_PRODUCT_ID, 'plus_monthly');
    assert.equal(SUPPORTER_MONTHLY_PRODUCT_ID, 'supporter_monthly');
    assert.equal(SUBSCRIPTION_CAPABILITY_PROFILES.community.monthlyPriceSek, null);
    assert.equal(SUBSCRIPTION_CAPABILITY_PROFILES.plus.monthlyPriceSek, 39);
    assert.equal(SUBSCRIPTION_CAPABILITY_PROFILES.supporter.monthlyPriceSek, 119);
    assert.equal(
      Object.values(SUBSCRIPTION_CAPABILITY_PROFILES).every(
        (profile) => profile.hasIntroDiscount === false,
      ),
      true,
    );
  });

  it('maps legacy member_monthly explicitly to Plus', () => {
    assert.equal(subscriptionTierForLegacyEntitlement('none'), 'community');
    assert.equal(subscriptionTierForLegacyEntitlement('member_monthly'), 'plus');
    assert.equal(
      resolveSubscriptionTier({ entitlement: 'member_monthly', tier: 'supporter' }),
      'supporter',
    );
    assert.equal(resolveSubscriptionTier({ entitlement: 'none', tier: 'supporter' }), 'community');
  });

  it('keeps the language-neutral schema rolling-compatible and separate from entitlements', () => {
    assert.deepEqual(subscriptionSchema.$defs.subscriptionEntitlement.enum, [
      'none',
      'member_monthly',
    ]);
    assert.deepEqual(subscriptionSchema.$defs.subscriptionTier.enum, [
      'community',
      'plus',
      'supporter',
    ]);
    assert.deepEqual(subscriptionSchema.$defs.subscriptionProductId.enum, [
      'plus_monthly',
      'supporter_monthly',
    ]);
    assert.equal(subscriptionSchema.$defs.subscriptionRecord.required.includes('tier'), false);
    assert.equal(subscriptionSchema.$defs.subscriptionRecord.required.includes('startsAt'), false);
  });

  it('pins limits and existing paid capability decisions', () => {
    const { community, plus, supporter } = SUBSCRIPTION_CAPABILITY_PROFILES;
    assert.deepEqual(
      [community.garageVehicleLimit, plus.garageVehicleLimit, supporter.garageVehicleLimit],
      [2, 5, 10],
    );
    assert.deepEqual(community.driveHistory, { kind: 'latest', count: 5 });
    assert.deepEqual(plus.driveHistory, { kind: 'rolling_days', days: 90 });
    assert.deepEqual(supporter.driveHistory, { kind: 'unlimited' });
    // Legacy map capability values remain unchanged and do not activate a map gate.
    assert.deepEqual(
      [
        community.exactOtherUserLivePositions,
        plus.exactOtherUserLivePositions,
        supporter.exactOtherUserLivePositions,
      ],
      [false, true, true],
    );
    assert.equal(plus.fullEventDetails, true);
    for (const tier of ['community', 'plus', 'supporter']) {
      assert.equal(hasSubscriptionCapability(tier, 'fullEventDetails'), true);
      for (const capability of ['eventCheckIn', 'attendeeNames']) {
        assert.equal(hasSubscriptionCapability(tier, capability), tier !== 'community');
        assert.equal(
          subscriptionSchema.$defs.tierCapabilityProfile.required.includes(capability),
          true,
        );
      }
    }
    assert.equal(plus.partnerOffers, true);
    assert.equal(hasSubscriptionCapability('community', 'fullEventDetails'), true);
    assert.equal(hasSubscriptionCapability('plus', 'fullEventDetails'), true);
    assert.deepEqual(supporter.supporterBadge, { available: true, defaultVisible: true });
  });

  it('projects Plus and Supporter to the legacy activeMember compatibility flag', () => {
    assert.equal(
      grantsLegacyActiveMember({
        entitlement: 'member_monthly',
        status: 'active',
        tier: 'plus',
      }),
      true,
    );
    assert.equal(
      grantsLegacyActiveMember({
        entitlement: 'member_monthly',
        status: 'grace_period',
        tier: 'supporter',
      }),
      true,
    );
    assert.equal(
      grantsLegacyActiveMember({
        entitlement: 'member_monthly',
        status: 'cancelled',
        tier: 'plus',
      }),
      true,
    );
    assert.equal(
      grantsLegacyActiveMember({ entitlement: 'none', status: 'active', tier: 'supporter' }),
      false,
    );
  });

  it('proves Supporter is a strict capability superset of Plus', () => {
    assert.equal(isTierCapabilitySuperset('supporter', 'plus'), true);
    assert.equal(isTierCapabilitySuperset('plus', 'supporter'), false);
    assert.equal(isTierAtLeast('supporter', 'plus'), true);
    assert.equal(isTierAtLeast('community', 'plus'), false);
  });
});
