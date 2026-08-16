/**
 * PerkLogo a11y behaviour: a non-empty title makes the glyph a labelled image
 * (role="img" + aria-label + <title>); an omitted OR empty/whitespace title
 * makes it decorative (aria-hidden, no role, no accessible name) so it never
 * appears in the accessibility tree as an unlabelled image.
 */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { PerkLogo } from '@/app/kronjakt/PerkLogos';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
});

function render(node: React.ReactElement) {
  act(() => root.render(node));
  return container.querySelector('svg') as SVGElement;
}

describe('PerkLogo', () => {
  it('is a labelled image when given a non-empty title', () => {
    const svg = render(<PerkLogo perkId="spike_strip" title="Spikmatta" />);
    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.getAttribute('aria-label')).toBe('Spikmatta');
    expect(svg.getAttribute('aria-hidden')).toBeNull();
    expect(svg.querySelector('title')?.textContent).toBe('Spikmatta');
  });

  it('is decorative when the title is omitted', () => {
    const svg = render(<PerkLogo perkId="shield" />);
    expect(svg.getAttribute('role')).toBeNull();
    expect(svg.getAttribute('aria-label')).toBeNull();
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.querySelector('title')).toBeNull();
  });

  it('is decorative when the title is empty or whitespace', () => {
    for (const title of ['', '   ']) {
      const svg = render(<PerkLogo perkId="boost" title={title} />);
      expect(svg.getAttribute('role')).toBeNull();
      expect(svg.getAttribute('aria-label')).toBeNull();
      expect(svg.getAttribute('aria-hidden')).toBe('true');
      expect(svg.querySelector('title')).toBeNull();
    }
  });
});
