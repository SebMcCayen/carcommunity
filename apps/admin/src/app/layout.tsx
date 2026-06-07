import type { Metadata } from 'next';
import './globals.css';
import { AdminShell } from '@/components/layout/AdminShell';
import { brand } from '@/config/brand';

// TODO: Implement Microsoft Entra ID authentication before production use.
// TODO: Admin role must be verified by the backend on every request — never
//       trust client-side admin flags.
// TODO: All admin actions must be authorised via backend role-based access
//       control before this portal handles real data.

export const metadata: Metadata = {
  title: brand.adminTitle,
  description: `${brand.fullName} — Admin Portal`,
};

export default function RootLayout({
  children,
}: {
  children: import('react').ReactNode;
}) {
  return (
    <html lang="sv">
      <body>
        <AdminShell>{children}</AdminShell>
      </body>
    </html>
  );
}
