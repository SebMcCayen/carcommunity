import { useEffect, useRef, type ReactNode } from 'react';
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
    dialog?.showModal?.();
    return () => dialog?.close?.();
  }, []);
  return (
    <dialog
      ref={ref}
      aria-label={title}
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
      style={{
        maxWidth: 560,
        padding: 24,
        borderRadius: 12,
        border: '1px solid var(--border-color)',
        background: 'var(--bg-surface)',
        color: 'var(--text-primary)',
      }}
    >
      <h3>{title}</h3>
      {children}
      <button type="button" onClick={onCancel}>
        Avbryt
      </button>
    </dialog>
  );
}
