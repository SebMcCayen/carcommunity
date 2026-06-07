import type { ReactNode } from 'react';
import { Sidebar } from './Sidebar';
import styles from './AdminShell.module.css';

interface AdminShellProps {
  children: ReactNode;
}

export function AdminShell({ children }: AdminShellProps) {
  return (
    <div className={styles.shell}>
      <Sidebar />
      <div className={styles.content}>
        <main className={styles.main}>{children}</main>
      </div>
    </div>
  );
}
