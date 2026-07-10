import { Suspense } from 'react';
import { Outlet } from 'react-router-dom';
import { AdminShell } from './AdminShell';
import { RouteFallback } from '@/components/ui/RouteFallback';

/**
 * Layout component for protected admin routes.
 * Renders the AdminShell (sidebar + content area) and places the matched
 * child route into the content area via React Router's <Outlet />.
 *
 * Pages are lazy-loaded (see App.tsx), so the Suspense boundary lives here —
 * inside the shell — to keep the sidebar visible while a page chunk loads and
 * to show the same loading affordance pages use while fetching data.
 */
export function AdminLayout() {
  return (
    <AdminShell>
      <Suspense fallback={<RouteFallback />}>
        <Outlet />
      </Suspense>
    </AdminShell>
  );
}
