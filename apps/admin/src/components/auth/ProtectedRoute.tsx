import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAdminAuth } from './FirebaseAuthProvider';

interface ProtectedRouteProps {
  children: ReactNode;
}

/**
 * Guards a route tree behind Firebase Authentication and the `admin: true`
 * custom claim.
 *
 * - While the auth state is being resolved, renders nothing (avoids flash).
 * - Unauthenticated users are redirected to /login, preserving the intended
 *   destination so they can be sent back after sign-in.
 * - Authenticated users without the admin claim are redirected to /unauthorized.
 * - Authenticated admins see the wrapped content.
 *
 * Security:
 * - The admin claim is verified server-side on every API request.
 * - This component is a UX guard only and does NOT constitute a security boundary.
 */
export function ProtectedRoute({ children }: ProtectedRouteProps) {
  const { user, isAdmin, loading } = useAdminAuth();
  const location = useLocation();

  if (loading) {
    // Render nothing while auth state resolves to avoid a flash of the login page.
    return null;
  }

  if (!user) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (!isAdmin) {
    return <Navigate to="/unauthorized" replace />;
  }

  return <>{children}</>;
}
