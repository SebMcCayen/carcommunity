declare module 'react-test-renderer' {
  import type { ReactElement } from 'react';

  export function act(callback: () => void): void;
  export function act(callback: () => Promise<void>): Promise<void>;

  export interface ReactTestRendererJSON {
    type: string;
    props: Record<string, unknown>;
    children: (ReactTestRendererJSON | string)[] | null;
  }

  interface ReactTestInstance {
    props: Record<string, unknown>;
    children: ReactTestInstance[];
    findAll(
      predicate: (node: ReactTestInstance) => boolean,
      options?: { deep: boolean },
    ): ReactTestInstance[];
  }

  interface Renderer {
    unmount(): void;
    root: ReactTestInstance;
    toJSON(): ReactTestRendererJSON | ReactTestRendererJSON[] | null;
  }

  interface TestRendererModule {
    create(element: ReactElement): Renderer;
  }

  const TestRenderer: TestRendererModule;

  export default TestRenderer;
}
