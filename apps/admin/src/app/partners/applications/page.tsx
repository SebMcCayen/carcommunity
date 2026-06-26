'use client';

/**
 * Admin partner applications sub-route.
 *
 * Redirects to the main partners page which contains the applications tab.
 * This route exists to provide a direct URL for linking from other pages.
 */

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';

export default function PartnerApplicationsPage() {
  const router = useRouter();

  useEffect(() => {
    router.replace('/partners');
  }, [router]);

  return null;
}
