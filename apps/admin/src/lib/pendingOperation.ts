/** One durable retry identity per reviewed payload, actor and member. */
export function pendingOperation(
  scope: string,
  payload: unknown,
): { key: string; clear: () => void } {
  const storageKey = `kcc-admin-pending:${scope}`;
  const fingerprint = JSON.stringify(payload);
  // Fail before mutation if storage is unavailable; an uncertain request must
  // remain recoverable after reload. Reviewed text (including notes) stays
  // within this origin/tab session; authentication credentials are never stored.
  const saved = sessionStorage.getItem(storageKey);
  let previous: { fingerprint: string; key: string } | null = null;
  try {
    previous = saved ? JSON.parse(saved) : null;
  } catch {
    /* corrupt entry is replaced */
  }
  const key = previous?.fingerprint === fingerprint ? previous.key : crypto.randomUUID();
  sessionStorage.setItem(storageKey, JSON.stringify({ fingerprint, key }));
  return { key, clear: () => sessionStorage.removeItem(storageKey) };
}
