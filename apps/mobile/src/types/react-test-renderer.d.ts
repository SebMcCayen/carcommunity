declare module 'react-test-renderer' {
  import type { ReactElement } from 'react';

  export function act(callback: () => void): void;
  export function act(callback: () => Promise<void>): Promise<void>;

  interface Renderer {
    unmount(): void;
  }

  interface TestRendererModule {
    create(element: ReactElement): Renderer;
  }

  const TestRenderer: TestRendererModule;

  export default TestRenderer;
}
