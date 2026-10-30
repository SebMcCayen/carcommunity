import { describe, expect, it } from 'vitest';
import { serializeCalendar, escapeText, foldLine } from './ics';
import { type CalendarRow } from './contract';

const row: CalendarRow = {
  id: 'event',
  title: 'Meeting',
  locationName: 'Kungsbacka',
  startMillis: Date.parse('2026-11-01T12:00:00Z'),
  endMillis: null,
  status: 'CONFIRMED',
  sequence: 0,
  stampMillis: Date.parse('2026-10-29T12:00:00Z'),
  intentCreatedMillis: Date.parse('2026-10-28T12:00:00Z'),
};
describe('RFC 5545 UTF-8 calendar serialization', () => {
  it('uses CRLF, UTC, stable opaque UIDs, sequence/stamp and no private fields/secret URL', () => {
    const data = {
      ...row,
      organizerUid: 'PRIVATE ORGANIZER',
      attendees: ['PRIVATE MEMBER'],
      description: 'PRIVATE DESCRIPTION',
      url: 'SECRET TOKEN URL',
      address: 'PRIVATE STREET',
    };
    const body = serializeCalendar('membership-epoch', [data]);
    expect(body).toContain('DTSTART:20261101T120000Z\r\n');
    expect(body).toContain('DTSTAMP:20261029T120000Z\r\n');
    expect(body).toContain('SEQUENCE:0\r\n');
    expect(body).not.toContain('DTEND:');
    expect(body).not.toMatch(
      /PRIVATE (ORGANIZER|MEMBER|DESCRIPTION|STREET)|SECRET TOKEN URL|ATTENDEE:|ORGANIZER:|SOURCE:|URL:/,
    );
    expect(body.replace(/\r\n /g, '')).toMatch(/UID:[a-f0-9]{64}@club-calendar\.carcommunity/);
    expect(body.replace(/\r\n/g, '')).not.toMatch(/[\r\n]/);
    expect(body.endsWith('END:VCALENDAR\r\n')).toBe(true);
    expect(serializeCalendar('membership-epoch', [data])).toBe(body);
    expect(serializeCalendar('new-membership-epoch', [data])).not.toBe(body);
  });

  it('folds Swedish text and emoji at 75 UTF-8 octets including continuation spaces', () => {
    const text = 'SUMMARY:' + 'åäö🚗👨‍👩‍👧‍👦'.repeat(80);
    const folded = foldLine(text);
    for (const line of folded.split('\r\n')) {
      expect(Buffer.byteLength(line, 'utf8')).toBeLessThanOrEqual(75);
      expect(Buffer.from(line).toString('utf8')).toBe(line);
      expect(line).not.toContain('\ufffd');
    }
    expect(folded.replace(/\r\n /g, '')).toBe(text);
  });

  it('escapes text/newline injection without allowing new calendar properties', () => {
    const text = 'å,;\\\r\nBEGIN:VEVENT\rATTENDEE:bad\nSUMMARY:fake\u0000\u001b';
    expect(escapeText(text)).toBe('å\\,\\;\\\\\\nBEGIN:VEVENT\\nATTENDEE:bad\\nSUMMARY:fake');
    const body = serializeCalendar('epoch', [{ ...row, title: text }]).replace(/\r\n /g, '');
    expect(body.match(/^BEGIN:VEVENT$/gm)).toHaveLength(1);
    expect(body).not.toMatch(/\r\nATTENDEE:/);
    expect(body).not.toContain('\u0000');
  });

  it('omits METHOD:CANCEL for mixed feeds and includes per-event cancellation revision', () => {
    const body = serializeCalendar('epoch', [
      row,
      { ...row, id: 'gone', status: 'CANCELLED', sequence: 1 },
    ]);
    expect(body.match(/BEGIN:VEVENT/g)).toHaveLength(2);
    expect(body).toContain('STATUS:CANCELLED\r\n');
    expect(body).toContain('SEQUENCE:1\r\n');
    expect(body).not.toContain('METHOD:');
  });
});
