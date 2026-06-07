/** Allow CSS side-effect imports (e.g. `import './globals.css'`). */
declare module '*.css' {
  const styles: { readonly [className: string]: string };
  export default styles;
}
