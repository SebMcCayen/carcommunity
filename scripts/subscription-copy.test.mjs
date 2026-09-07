import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

for (const [locale, free, checkIn, names, action] of [
  ['en', 'free', 'Check-in', 'attendee names', 'See Plus and Supporter'],
  ['sv', 'gratis', 'Incheckning', 'deltagarnamn', 'Se Plus och Supporter'],
]) {
  test(`${locale} event copy matches free details/RSVP and narrow paid benefits`, () => {
    const { events } = JSON.parse(
      readFileSync(new URL(`../contracts/localization/${locale}.json`, import.meta.url), 'utf8'),
    );
    for (const key of ['memberRequiredBody', 'memberGateBody', 'upgradeDetailsBody']) {
      assert.ok(events[key].includes('RSVP') && events[key].includes(free), key);
    }
    assert.ok(events.memberGateBody.includes(checkIn));
    assert.ok(events.memberGateBody.includes(names));
    assert.ok(
      events.memberGateBody.includes('Plus') && events.memberGateBody.includes('Supporter'),
    );
    assert.equal(events.upgradeDetailsAction, action);
    assert.ok(!events.memberRequiredTitle.includes('Membership'));
    assert.ok(!events.memberRequiredTitle.includes('Medlem'));
  });
}
