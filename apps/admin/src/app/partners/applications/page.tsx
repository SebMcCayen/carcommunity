/**
 * Admin partner applications sub-route.
 *
 * Redirects to the main partners page which contains the applications tab.
 * This route exists to provide a direct URL for linking from other pages.
 */

import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';

export default function PartnerApplicationsPage() {
  const navigate = useNavigate();

  useEffect(() => {
    void navigate('/partners', { replace: true });
  }, [navigate]);

  return null;
}
