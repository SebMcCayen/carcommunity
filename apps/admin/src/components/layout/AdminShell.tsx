import { useState, type ReactNode } from 'react';
import { Menu, X } from 'lucide-react';
import { brand } from '@/config/brand';
import { Sidebar } from './Sidebar';
import styles from './AdminShell.module.css';

interface AdminShellProps {
  children: ReactNode;
}

export function AdminShell({ children }: AdminShellProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  return (
    <div className={styles.shell}>
      <div className={styles.mobileBar}>
        <span>{brand.shortName} Admin</span>
        <button
          type="button"
          aria-controls="admin-sidebar"
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((open) => !open)}
        >
          {menuOpen ? <X size={20} aria-hidden="true" /> : <Menu size={20} aria-hidden="true" />}
          {menuOpen ? 'Stäng meny' : 'Öppna meny'}
        </button>
      </div>
      <div id="admin-sidebar" className={styles.sidebarRegion} data-open={menuOpen}>
        <Sidebar onNavigate={() => setMenuOpen(false)} />
      </div>
      <div className={styles.content}>
        <main className={styles.main}>{children}</main>
      </div>
    </div>
  );
}
