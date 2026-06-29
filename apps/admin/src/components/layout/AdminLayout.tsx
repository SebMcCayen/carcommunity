import { Outlet } from 'react-router-dom';
import { AdminShell } from './AdminShell';

/**
 * Layout component for protected admin routes.
 * Renders the AdminShell (sidebar + content area) and places the matched
 * child route into the content area via React Router's <Outlet />.
 */
export function AdminLayout() {
  return (
    <AdminShell>
      <Outlet />
    </AdminShell>
  );
}
