import { hash, type CalendarRow } from './contract';

/** RFC 5545 TEXT escaping; strip forbidden controls, never emit user content as properties. */
export function escapeText(value: string): string {
  return value
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, '')
    .replace(/\\/g, '\\\\')
    .replace(/\r\n|\r|\n/g, '\\n')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,');
}

/** Fold by UTF-8 octets, including continuation SP, never through a code point. */
export function foldLine(line: string): string {
  let current = '',
    bytes = 0;
  const lines: string[] = [];
  for (const character of line) {
    const size = Buffer.byteLength(character, 'utf8');
    if (bytes + size > 75) {
      lines.push(current);
      current = ' ';
      bytes = 1;
    }
    current += character;
    bytes += size;
  }
  lines.push(current);
  return lines.join('\r\n');
}

const stamp = (ms: number): string =>
  new Date(ms)
    .toISOString()
    .replace(/[-:]/g, '')
    .replace(/\.\d{3}Z$/, 'Z');

export function serializeCalendar(epoch: string, rows: readonly CalendarRow[]): string {
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//CarCommunity//Club calendar//EN',
    'CALSCALE:GREGORIAN',
    'REFRESH-INTERVAL;VALUE=DURATION:PT1H',
    'X-PUBLISHED-TTL:PT1H',
  ];
  for (const row of [...rows].sort((a, b) => a.id.localeCompare(b.id))) {
    lines.push(
      'BEGIN:VEVENT',
      `UID:${hash(`${epoch}\0${row.id}`)}@club-calendar.carcommunity`,
      `SEQUENCE:${row.sequence}`,
      `DTSTAMP:${stamp(row.stampMillis)}`,
      `LAST-MODIFIED:${stamp(row.stampMillis)}`,
      `DTSTART:${stamp(row.startMillis)}`,
      `STATUS:${row.status}`,
      'CLASS:PRIVATE',
      `SUMMARY:${escapeText(row.title)}`,
    );
    if (row.endMillis !== null) lines.push(`DTEND:${stamp(row.endMillis)}`);
    if (row.locationName) lines.push(`LOCATION:${escapeText(row.locationName)}`);
    lines.push('END:VEVENT');
  }
  return [...lines, 'END:VCALENDAR'].map(foldLine).join('\r\n') + '\r\n';
}
