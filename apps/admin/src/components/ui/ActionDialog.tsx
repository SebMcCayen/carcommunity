import { useEffect, useRef, type ReactNode } from 'react';
import styles from './ActionDialog.module.css';
/** Native focus trap, Escape handling, and return focus supplied by <dialog>. */
export function ActionDialog({
  title,
  children,
  onCancel,
}: {
  title: string;
  children: ReactNode;
  onCancel: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    const previousFocus =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialog?.showModal?.();
    return () => {
      dialog?.close?.();
      if (previousFocus?.isConnected) previousFocus.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className={styles.dialog}
      aria-label={title}
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
      style={{
        width: 'min(560px, calc(100vw - 32px))',
        margin: 'auto',
        boxSizing: 'border-box',
        maxHeight: '90dvh',
        overflowY: 'auto',
        padding: 24,
        borderRadius: 12,
        border: '1px solid var(--border-color)',
        background: 'var(--bg-surface)',
        color: 'var(--text-primary)',
      }}
    >
      <div className={styles.content}>
        <h3>{title}</h3>
        {children}
        <button className={styles.cancel} type="button" onClick={onCancel}>
          Avbryt
        </button>
      </div>
    </dialog>
  );
}
