import { describe, expect, it } from 'vitest';
import {
  buildCameraRequest,
  buildSpeedCameraRequest,
  cameraDocumentId,
  CAMERA_QUERY_LIMIT,
  MAX_PHOTO_BYTES,
  parseCameraCatalog,
  sanitizePhotoUrl,
  toPublicCamera,
} from './core';

const image =
  'https://api.trafikinfo.trafikverket.se/v2/Images/data/road.infrastructure.camera/TrafficFlowCamera_123.jpg';
const liveCamera = {
  Id: 'live/123',
  Name: 'Bridge',
  Active: true,
  Deleted: false,
  Type: 'Trafikflödeskamera',
  Direction: 270,
  Geometry: { WGS84: 'POINT (12.1 57.5)' },
  PhotoUrl: image,
  PhotoTime: '2026-11-12T22:00:00+01:00',
};
const speedCamera = {
  Id: 'speed/123',
  Name: 'Road 40',
  Deleted: false,
  Bearing: 90,
  Geometry: { WGS84: 'POINT (12.2 57.6)' },
};
const response = (type: string, rows: unknown[]) => ({ RESPONSE: { RESULT: [{ [type]: rows }] } });
const parse = (rows: unknown[] = [liveCamera], speedRows: unknown[] = [speedCamera]) =>
  parseCameraCatalog(response('Camera', rows), response('TrafficSafetyCamera', speedRows));

describe('Trafikverket camera request contracts', () => {
  it('uses independently verified schemas and only existing field names', () => {
    const request = buildCameraRequest('a<&"\'');
    expect(request).toContain('authenticationkey="a&lt;&amp;&quot;&apos;"');
    expect(request).toContain(
      'objecttype="Camera" namespace="Road.Infrastructure" schemaversion="1.1"',
    );
    expect(request).toContain(
      '<AND><EQ name="Deleted" value="false" /><EQ name="Active" value="true" /></AND>',
    );
    expect(request).toContain('<INCLUDE>PhotoUrl</INCLUDE>');
    const speed = buildSpeedCameraRequest('test-key');
    expect(speed).toContain(
      'objecttype="TrafficSafetyCamera" namespace="Road.Infrastructure" schemaversion="1.0"',
    );
    expect(speed).toContain('<INCLUDE>Bearing</INCLUDE>');
    expect(speed).not.toContain('Active');
    expect(speed).not.toContain('PhotoUrl');
    expect(speed).not.toContain('Direction');
  });

  it.each([0, -1, 1.5, Number.NaN, 10_001])('rejects unsafe query cap %s', (limit) => {
    expect(() => buildCameraRequest('key', limit)).toThrow('Invalid camera query limit');
  });
});

describe('complete catalog parsing', () => {
  it('maps all three types and preserves WGS84 ordering and bearing semantics', () => {
    const result = parse([
      liveCamera,
      { ...liveCamera, Id: 'weather', Type: 'Väglagskamera', Direction: 360 },
    ]);
    expect(result.sourceCount).toBe(3);
    expect(result.cameraSourceCount).toBe(2);
    expect(result.speedSourceCount).toBe(1);
    expect(result.cameras.map((c) => c.type).sort()).toEqual([
      'road_condition',
      'speed',
      'traffic',
    ]);
    const traffic = result.cameras.find((c) => c.type === 'traffic')!;
    expect(traffic).toMatchObject({
      latitude: 57.5,
      longitude: 12.1,
      direction: 270,
      photoUrl: `${image}?maxage=15`,
      photoTime: '2026-11-12T21:00:00.000Z',
    });
    expect(result.cameras.find((c) => c.type === 'road_condition')!.direction).toBe(0);
    expect(result.cameras.find((c) => c.type === 'speed')).toMatchObject({
      direction: 90,
      photoUrl: null,
      photoTime: null,
    });
  });

  it('provides stable safe keys even for upstream ids containing slashes', () => {
    const first = parse().cameras;
    const second = parse().cameras;
    expect(first).toEqual(second);
    expect(first.every((c) => /^(?:speed|traffic|road_condition)_[a-f0-9]{32}$/.test(c.id))).toBe(
      true,
    );
    expect(cameraDocumentId('speed', 'same')).not.toBe(cameraDocumentId('traffic', 'same'));
  });

  it('drops duplicates without depending on input order for catalog sorting', () => {
    expect(parse([liveCamera, liveCamera]).cameras).toHaveLength(2);
  });

  it('skips invalid records while retaining valid camera entries', () => {
    const invalids = [
      null,
      'wrong',
      { ...liveCamera, Id: '' },
      { ...liveCamera, Id: 'bad\u0000id' },
      { ...liveCamera, Id: 'a'.repeat(513) },
      { ...liveCamera, Deleted: true },
      { ...liveCamera, Active: false },
      { ...liveCamera, Type: 'unknown' },
      { ...liveCamera, Geometry: { WGS84: 'POINT (12 91)' } },
      { ...liveCamera, Geometry: { WGS84: 'POINT (181 57)' } },
      { ...liveCamera, Geometry: { WGS84: 'MULTIPOINT ((12 57))' } },
      { ...liveCamera, Geometry: { WGS84: 'POINT (12 57) trailing' } },
    ];
    expect(parse([liveCamera, ...invalids]).cameras).toHaveLength(2);
  });

  it('bounds names and treats invalid direction and zoneless photo time as unavailable', () => {
    const camera = parse([
      { ...liveCamera, Name: 'x'.repeat(200), Direction: -1, PhotoTime: '2026-11-12T22:00:00' },
    ]).cameras.find((c) => c.type === 'traffic')!;
    expect(camera.name.length).toBe(160);
    expect(camera.direction).toBeNull();
    expect(camera.photoTime).toBeNull();
  });

  it('does not expose image URLs or photo timestamps in marker catalog', () => {
    const publicCamera = toPublicCamera(parse().cameras.find((c) => c.type === 'traffic')!);
    expect(publicCamera).not.toHaveProperty('photoUrl');
    expect(publicCamera).not.toHaveProperty('photoTime');
    expect(Object.keys(publicCamera).sort()).toEqual([
      'direction',
      'id',
      'latitude',
      'longitude',
      'name',
      'type',
    ]);
  });

  it.each([
    undefined,
    {},
    { RESPONSE: {} },
    { RESPONSE: { RESULT: [] } },
    { RESPONSE: { RESULT: [{ ERROR: { MESSAGE: 'denied' } }] } },
    { RESPONSE: { RESULT: [{ Camera: {} }] } },
  ])('refuses malformed upstream response %j', (value) => {
    expect(() =>
      parseCameraCatalog(value, response('TrafficSafetyCamera', [speedCamera])),
    ).toThrow();
  });

  it('refuses empty, capped and wholly unusable national snapshots', () => {
    expect(() => parse([])).toThrow('Incomplete Camera national snapshot');
    expect(() => parse([liveCamera], [])).toThrow(
      'Incomplete TrafficSafetyCamera national snapshot',
    );
    expect(() => parse(Array(CAMERA_QUERY_LIMIT).fill(liveCamera))).toThrow(
      'Incomplete Camera national snapshot',
    );
    expect(() => parse([{ ...liveCamera, Type: 'unknown' }])).toThrow('No usable Camera records');
  });
});

describe('server-only photo URL policy', () => {
  it.each([
    'TrafficFlowCamera_123.jpg',
    'TrafficFlowCamera_123_thumbnail.jpg',
    'RoadConditionCamera_VViS_123_K1.jpg',
    'RoadConditionCamera_VViS_123_K1_thumbnail.jpg',
  ])('accepts the published camera data path for %s', (filename) => {
    const url = `https://api.trafikinfo.trafikverket.se/v2/Images/data/road.infrastructure.camera/${filename}`;
    expect(sanitizePhotoUrl(url)).toBe(`${url}?maxage=15`);
  });

  it('canonicalizes standard size and bounded age, including legacy endpoint versions', () => {
    expect(sanitizePhotoUrl(`${image}?type=fullsize&maxage=999`)).toBe(`${image}?maxage=15`);
    expect(sanitizePhotoUrl(image.replace('/v2/', '/v1.3/'))).toContain('/v1.3/Images/');
    const legacy = image.replace('/data/road.infrastructure.camera', '');
    expect(sanitizePhotoUrl(legacy)).toBe(`${legacy}?maxage=15`);
    expect(MAX_PHOTO_BYTES).toBe(131_072);
  });

  it.each([
    `http://api.trafikinfo.trafikverket.se/v2/Images/a.jpeg`,
    'https://evil.example/v2/Images/a.jpeg',
    'https://api.trafikinfo.trafikverket.se.evil.example/v2/Images/a.jpeg',
    'https://user:password@api.trafikinfo.trafikverket.se/v2/Images/a.jpeg',
    'https://api.trafikinfo.trafikverket.se:443/v2/Images/a.jpeg',
    'https://api.trafikinfo.trafikverket.se/v2/Images/a.svg',
    'https://api.trafikinfo.trafikverket.se/v2/Images/a%2fb.jpeg',
    'https://api.trafikinfo.trafikverket.se/v2/Images/data/other/a.jpeg',
    'https://api.trafikinfo.trafikverket.se/v2/Images/data/road.infrastructure.camera/extra/a.jpeg',
    'https://api.trafikinfo.trafikverket.se/v2/Images/data/road.infrastructure.camera/a%2fb.jpeg',
    `${image}#fragment`,
    `${image}?authenticationkey=secret`,
    `${image}?redirect=https://evil.example`,
    null,
    123,
  ])('rejects arbitrary endpoints, secret-bearing URLs and unsafe image formats: %s', (value) => {
    expect(sanitizePhotoUrl(value)).toBeNull();
  });
});
