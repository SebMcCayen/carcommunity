'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { brand } from '@/config/brand';
import styles from './Sidebar.module.css';

interface NavItem {
  label: string;
  href: string;
  icon: string;
}

interface NavGroup {
  title: string;
  items: NavItem[];
}

const navGroups: NavGroup[] = [
  {
    title: 'Main',
    items: [
      { label: 'Dashboard', href: '/', icon: '◼' },
      { label: 'Users', href: '/users', icon: '◎' },
    ],
  },
  {
    title: 'Content',
    items: [
      { label: 'Events', href: '/events', icon: '◈' },
      { label: 'Partners', href: '/partners', icon: '◇' },
      { label: 'Digital Billboards', href: '/billboards', icon: '▣' },
      { label: 'Kronjakt', href: '/kronjakt', icon: '✦' },
    ],
  },
  {
    title: 'Moderation',
    items: [
      { label: 'Reports', href: '/reports', icon: '◬' },
      { label: 'Live Location', href: '/live-location', icon: '◉' },
      { label: 'Support', href: '/support', icon: '◐' },
      { label: 'Audit Log', href: '/audit-log', icon: '≡' },
    ],
  },
  {
    title: 'System',
    items: [{ label: 'Settings', href: '/settings', icon: '◎' }],
  },
];

export function Sidebar() {
  const pathname = usePathname();

  function isActive(href: string): boolean {
    if (href === '/') return pathname === '/';
    return pathname.startsWith(href);
  }

  return (
    <aside className={styles.sidebar}>
      <div className={styles.brand}>
        {/* Crown mark — brand asset, do not reuse outside official contexts */}
        <span className={styles.brandMark} aria-hidden="true">
          ♛
        </span>
        <span className={styles.brandName}>{brand.shortName} Admin</span>
      </div>

      <nav className={styles.nav} aria-label="Admin navigation">
        {navGroups.map((group) => (
          <div key={group.title} className={styles.group}>
            <span className={styles.groupLabel}>{group.title}</span>
            {group.items.map((item) => (
              <Link
                key={item.href}
                href={item.href}
                className={`${styles.navItem} ${isActive(item.href) ? styles.active : ''}`}
                aria-current={isActive(item.href) ? 'page' : undefined}
              >
                <span className={styles.navIcon} aria-hidden="true">
                  {item.icon}
                </span>
                <span className={styles.navLabel}>{item.label}</span>
              </Link>
            ))}
          </div>
        ))}
      </nav>

      <div className={styles.footer}>
        {/*
         * TODO: Replace with real Microsoft Entra ID authentication.
         * TODO: Admin role must be verified by the backend before production use.
         * TODO: Never trust client-side admin flags.
         */}
        <div className={styles.authPlaceholder} role="status">
          <span className={styles.authIcon} aria-hidden="true">
            ⚠
          </span>
          <span className={styles.authText}>Auth not configured</span>
        </div>
      </div>
    </aside>
  );
}
