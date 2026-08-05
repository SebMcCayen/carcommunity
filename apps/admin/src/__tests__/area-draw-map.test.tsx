/**
 * Component test for AreaDrawMap's map-load behaviour.
 *
 * Regression guard for the edit flow: AreasTab seeds an existing area's shape
 * BEFORE the Mapbox map finishes loading. The tool/style effect must enter the
 * draw mode when the style becomes ready WITHOUT clearing the current shape —
 * an earlier version called onShapeDrawn(null) on the style-load pass, which
 * wiped the seeded shape and left "Save changes" disabled until the operator
 * redrew the whole area.
 *
 * Mapbox GL + mapbox-gl-draw are unavailable under jsdom, so both are mocked
 * with tiny event-emitter fakes; the test drives the real component's effects by
 * firing the map 'load' event.
 */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

interface FakeListeners {
  [type: string]: Array<(ev?: unknown) => void>;
}

const glInstances = vi.hoisted(() => ({ maps: [] as FakeMap[], draws: [] as FakeDraw[] }));

class FakeMap {
  listeners: FakeListeners = {};
  constructor() {
    glInstances.maps.push(this);
  }
  on(type: string, cb: (ev?: unknown) => void) {
    (this.listeners[type] ||= []).push(cb);
  }
  off() {}
  fire(type: string, ev?: unknown) {
    (this.listeners[type] ?? []).forEach((cb) => cb(ev));
  }
  addControl() {}
  setConfigProperty() {}
  addSource() {}
  getSource() {
    return undefined;
  }
  addLayer() {}
  getLayer() {
    return undefined;
  }
  isStyleLoaded() {
    return true;
  }
  easeTo() {}
  remove() {}
}

class FakeDraw {
  deleteAll = vi.fn();
  changeMode = vi.fn();
  getAll = vi.fn(() => ({ features: [] as unknown[] }));
  constructor() {
    glInstances.draws.push(this);
  }
}

// Cut the crown-hunt barrel's path to lib/firebase (reads VITE_FIREBASE_* env).
vi.mock('@/lib/callables', () => ({ callAdmin: vi.fn() }));
vi.mock('@/lib/firestore', () => ({
  getAdminFirestore: () => ({}),
  isFirestoreEmulatorEnabled: () => false,
}));

vi.mock('mapbox-gl', () => ({
  default: {
    accessToken: '',
    Map: FakeMap,
    Marker: class {},
    NavigationControl: class {},
  },
}));
vi.mock('mapbox-gl/dist/mapbox-gl.css', () => ({}));
vi.mock('@mapbox/mapbox-gl-draw', () => {
  const Draw = FakeDraw as unknown as { new (opts: unknown): FakeDraw; modes: Record<string, unknown> };
  Draw.modes = {};
  return { default: Draw };
});
vi.mock('@mapbox/mapbox-gl-draw/dist/mapbox-gl-draw.css', () => ({}));

import { AreaDrawMap } from '@/components/map/AreaDrawMap';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

const labels = { attribution: 'osm', unavailable: 'n/a', loadError: 'err', hint: 'hint' };

async function flush(times = 6) {
  for (let i = 0; i < times; i += 1) {
     
    await act(async () => {
      await Promise.resolve();
    });
  }
}

beforeEach(() => {
  vi.stubEnv('VITE_MAPBOX_TOKEN', 'pk.test');
  glInstances.maps.length = 0;
  glInstances.draws.length = 0;
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.unstubAllEnvs();
});

describe('AreaDrawMap — map load', () => {
  it('enters the draw mode on style load WITHOUT clearing the current shape', async () => {
    const onShapeDrawn = vi.fn();
    await act(async () => {
      root.render(
        <AreaDrawMap
          tool="polygon"
          circleRadiusMeters={250}
          circleCenter={null}
          onCircleCenterChange={() => {}}
          onShapeDrawn={onShapeDrawn}
          existingAreas={[]}
          labels={labels}
        />,
      );
    });
    // Let the async GL init (dynamic imports + map/draw creation) settle.
    await flush();
    expect(glInstances.maps).toHaveLength(1);
    expect(glInstances.draws).toHaveLength(1);

    // Ignore anything emitted during init; only the load transition matters.
    onShapeDrawn.mockClear();

    // Fire the map 'load' event → styleLoaded flips true → the tool/style effect
    // runs with the draw instance ready.
    await act(async () => {
      glInstances.maps[0]!.fire('load');
    });
    await flush(2);

    // The effect must have entered the draw mode…
    expect(glInstances.draws[0]!.changeMode).toHaveBeenCalledWith('draw_polygon');
    // …but MUST NOT have cleared the shape (the edit-flow regression).
    expect(onShapeDrawn).not.toHaveBeenCalledWith(null);
    expect(onShapeDrawn).not.toHaveBeenCalled();
  });
});
