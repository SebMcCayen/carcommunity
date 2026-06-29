import type { Metadata } from 'next';
import './globals.css';
import { AdminShell } from '@/components/layout/AdminShell';
import { FirebaseAuthProvider } from '@/components/auth/FirebaseAuthProvider';
import { brand } from '@/config/brand';

export const metadata: Metadata = {
  title: brand.adminTitle,
  description: `${brand.fullName} — Admin Portal`,
};

export default function RootLayout({ children }: { children: import('react').ReactNode }) {
  return (
    <html lang="sv">
      <body>
        <FirebaseAuthProvider>
          <AdminShell>{children}</AdminShell>
        </FirebaseAuthProvider>
      </body>
    </html>
  );
}
