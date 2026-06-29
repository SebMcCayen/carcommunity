'use client';

/**
 * Firebase auth context provider for the admin portal.
 *
 * Wraps the application and provides the current auth state — user, admin
 * claim status, and loading flag — to all descendant components via the
 * useAdminAuth hook.
 *
 * Security:
 * - isAdmin is derived from the Firebase `admin: true` custom claim.
 * - Only trusted backend code can set custom claims; clients cannot forge them.
 * - isAdmin is a UI hint only — the backend independently enforces the claim.
 * - Admin state is not persisted beyond the active Firebase auth session.
 */

import React, {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from 'react';
import type { User } from 'firebase/auth';
import { checkAdminClaim, onAdminAuthStateChanged } from '@/lib/auth';

interface AdminAuthContextValue {
  user: User | null;
  isAdmin: boolean;
  loading: boolean;
}

const AdminAuthContext = createContext<AdminAuthContextValue>({
  user: null,
  isAdmin: false,
  loading: true,
});

export function FirebaseAuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [isAdmin, setIsAdmin] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const unsubscribe = onAdminAuthStateChanged(async (firebaseUser) => {
      try {
        if (firebaseUser) {
          const adminStatus = await checkAdminClaim(firebaseUser);
          setUser(firebaseUser);
          setIsAdmin(adminStatus);
        } else {
          setUser(null);
          setIsAdmin(false);
        }
      } catch {
        // If claim check fails (e.g. transient network error), treat the user
        // as unauthenticated to avoid leaving loading stuck or granting access.
        setUser(null);
        setIsAdmin(false);
      } finally {
        setLoading(false);
      }
    });

    return unsubscribe;
  }, []);

  return (
    <AdminAuthContext.Provider value={{ user, isAdmin, loading }}>
      {children}
    </AdminAuthContext.Provider>
  );
}

/**
 * Returns the current admin auth state.
 * Must be used inside a FirebaseAuthProvider.
 */
export function useAdminAuth(): AdminAuthContextValue {
  return useContext(AdminAuthContext);
}
