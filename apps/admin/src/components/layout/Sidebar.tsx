import { Link, useLocation } from 'react-router-dom';
import { brand } from '@/config/brand';
import { useAdminAuth } from '@/components/auth/FirebaseAuthProvider';
import { signOut } from '@/lib/auth';
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

/**
 * Every entry below must lead to a page that actually does something today.
 *
 * Deliberately NOT listed (the routes still exist in App.tsx, so reinstating an
 * entry is a one-line change here — see the comments there):
 *  - /live-location  — the page renders hardcoded zeros. Live sessions live in
 *    Realtime Database and no admin read path exists for them yet, so the page
 *    cannot show real data and its support actions are disabled by design.
 *  - /settings       — a PlaceholderPage with no backend and no actions. Its
 *    stated purpose (feature flags) is already served by the working
 *    /feature-flags page.
 *  - /reports        — a PlaceholderPage superseded by /moderation-reports; it
 *    was already absent from this nav.
 *  - /badges         — functional (badges.adminSummary / awardHelpfulMember),
 *    but adding a nav entry is a product decision, not part of this cleanup.
 */
const navGroups: NavGroup[] = [
  {
    title: 'Main',
    items: [
      { label: 'Dashboard', href: '/', icon: '◼' },
      { label: 'Users', href: '/users', icon: '◎' },
      { label: 'Subscription', href: '/subscription', icon: '★' },
    ],
  },
  {
    title: 'Content',
    items: [
      { label: 'Events', href: '/events', icon: '◈' },
      { label: 'Announcements', href: '/announcements', icon: '◻' },
      { label: 'Notifications', href: '/notifications', icon: '◻' },
      { label: 'Partners', href: '/partners', icon: '◇' },
      { label: 'Digital Billboards', href: '/billboards', icon: '▣' },
      { label: 'Kronjakt', href: '/kronjakt', icon: '✦' },
    ],
  },
  {
    title: 'Moderation',
    items: [
      { label: 'Moderation Reports', href: '/moderation-reports', icon: '◬' },
      { label: 'Error Reports', href: '/error-reports', icon: '◭' },
      { label: 'Account Deletions', href: '/account-deletions', icon: '◫' },
      { label: 'Event Chat', href: '/event-chat', icon: '◫' },
      { label: 'Support', href: '/support', icon: '◐' },
      { label: 'Audit Log', href: '/audit-log', icon: '≡' },
    ],
  },
  {
    title: 'System',
    items: [
      { label: 'Feature Flags', href: '/feature-flags', icon: '⚑' },
      { label: 'Token renewals', href: '/credentials', icon: '⧗' },
    ],
  },
];

export function Sidebar() {
  const { pathname } = useLocation();
  const { user } = useAdminAuth();

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
                to={item.href}
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
        {user ? (
          <div className={styles.authPlaceholder} role="status">
            <span className={styles.authText} title={user.email ?? undefined}>
              {user.displayName ?? user.email ?? 'Admin'}
            </span>
            <button
              type="button"
              className={styles.signOutButton}
              onClick={() => void signOut()}
              aria-label="Logga ut"
            >
              Logga ut
            </button>
          </div>
        ) : null}
      </div>
    </aside>
  );
}
