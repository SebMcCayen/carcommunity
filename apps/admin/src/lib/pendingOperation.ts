type PendingIdentity = { fingerprint: string; key: string };

function readPendingIdentities(storageKey: string): PendingIdentity[] {
  const saved = sessionStorage.getItem(storageKey);
  if (saved === null) return [];
  let value: unknown;
  try {
    value = JSON.parse(saved);
  } catch {
    throw new Error(
      'Sparad återförsöksinformation är skadad. Kontrollera tidigare beslut innan du fortsätter.',
    );
  }
  // Preserve an outstanding receipt written by the previous single-entry format.
  const identities: unknown[] = Array.isArray(value) ? value : [value];
  const valid = identities.every(
    (entry): entry is PendingIdentity =>
      typeof entry === 'object' &&
      entry !== null &&
      'fingerprint' in entry &&
      typeof entry.fingerprint === 'string' &&
      'key' in entry &&
      typeof entry.key === 'string' &&
      entry.key.length > 0,
  );
  if (!valid) {
    throw new Error(
      'Sparad återförsöksinformation är ogiltig. Kontrollera tidigare beslut innan du fortsätter.',
    );
  }
  return identities as PendingIdentity[];
}

/** One durable retry identity per reviewed payload, actor and member. */
export function pendingOperation(
  scope: string,
  payload: unknown,
): { key: string; clear: () => void } {
  const storageKey = `kcc-admin-pending:${scope}`;
  const fingerprint = JSON.stringify(payload);
  if (typeof fingerprint !== 'string') throw new Error('Beslutet kunde inte förberedas.');
  // Never evict an uncertain decision when another intent is reviewed. Storage
  // errors fail before mutation; private reviewed text remains in this tab only.
  const identities = readPendingIdentities(storageKey);
  const previous = identities.find((entry) => entry.fingerprint === fingerprint);
  const key = previous?.key ?? crypto.randomUUID();
  if (!previous) identities.push({ fingerprint, key });
  sessionStorage.setItem(storageKey, JSON.stringify(identities));
  return {
    key,
    clear: () => {
      // Read the current set: completing B must preserve A, and an old callback
      // must never remove a new intentional repetition of the same payload.
      const remaining = readPendingIdentities(storageKey).filter(
        (entry) => entry.fingerprint !== fingerprint || entry.key !== key,
      );
      if (remaining.length) sessionStorage.setItem(storageKey, JSON.stringify(remaining));
      else sessionStorage.removeItem(storageKey);
    },
  };
}
