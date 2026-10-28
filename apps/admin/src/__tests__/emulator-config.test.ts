import { describe, it, expect } from 'vitest';
import { validateEmulatorConfig } from '@/lib/emulator-config';
const hosts = { auth: '127.0.0.1:9099', firestore: 'localhost:8080', functions: '127.0.0.1:5001' };
describe('isolated admin emulator configuration', () => {
  it('accepts a complete loopback demo stack and leaves normal cloud builds unchanged', () => {
    expect(() => validateEmulatorConfig('demo-test', hosts, false)).not.toThrow();
    expect(() => validateEmulatorConfig('real-project', {}, false)).not.toThrow();
    expect(() => validateEmulatorConfig('real-project', hosts, true)).not.toThrow();
  });
  it('rejects cloud identity or a partial emulator configuration', () => {
    expect(() => validateEmulatorConfig('real-project', hosts, false)).toThrow('demo-*');
    expect(() =>
      validateEmulatorConfig('demo-test', { firestore: hosts.firestore }, false),
    ).toThrow('emulators together');
  });
  it.each(['remote.example:8080', 'localhost:99999', 'localhost', 'http://localhost:8080'])(
    'rejects the non-local or malformed endpoint %s',
    (host) => {
      expect(() =>
        validateEmulatorConfig('demo-test', { ...hosts, firestore: host }, false),
      ).toThrow('loopback');
    },
  );
});
