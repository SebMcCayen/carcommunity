import { createRoot } from 'react-dom/client';
import { useState } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { AdminShell } from '@/components/layout/AdminShell';
import { ApplicationReview } from '@/app/partners/applications/page';
import type { PortalApplication } from '@/features/partners/portal';
import { application } from './fixture-api';
import '@/app/globals.css';
function Fixture() {
  const [current, setCurrent] = useState({ ...application });
  return (
    <MemoryRouter initialEntries={['/partners/applications?application=qa-application']}>
      <AdminShell>
        <div
          role="status"
          style={{
            padding: '6px 12px',
            border: '1px solid var(--border-color)',
            marginBottom: 18,
            fontSize: 12,
          }}
        >
          ISOLERAD QA · Testdata · Ingen anslutning till Firebase eller produktion
        </div>
        <ApplicationReview
          application={current as PortalApplication}
          onRefresh={async () => setCurrent({ ...application })}
        />
      </AdminShell>
    </MemoryRouter>
  );
}
createRoot(document.getElementById('root')!).render(<Fixture />);
