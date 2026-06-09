declare module 'react-test-renderer' {
  import type { ReactElement } from 'react';

  export function act<T>(callback: () => T): T;

  interface Renderer {
    unmount(): void;
  }

  interface TestRendererModule {
    create(element: ReactElement): Renderer;
  }

  const TestRenderer: TestRendererModule;

  export default TestRenderer;
}
