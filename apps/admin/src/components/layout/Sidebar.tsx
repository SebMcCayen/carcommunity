import { Link, useLocation } from 'react-router-dom';
import { Crown, ChevronDown, UserRound, LogOut } from 'lucide-react';
import { brand } from '@/config/brand';
import { useAdminAuth } from '@/components/auth/FirebaseAuthProvider';
import { signOut } from '@/lib/auth';
import {
  ADMIN_MAIN_NAV,
  ADMIN_NAV_GROUPS,
  activeAdminHref,
  type AdminNavItem,
} from './admin-navigation';
import styles from './Sidebar.module.css';
export function Sidebar({ onNavigate }: { onNavigate?: () => void } = {}) {
  const { pathname } = useLocation(),
    { user } = useAdminAuth(),
    active = activeAdminHref(pathname);
  function itemLink(item: AdminNavItem) {
    const Icon = item.icon;
    return (
      <Link
        key={item.href}
        to={item.href}
        onClick={onNavigate}
        className={`${styles.navItem} ${active === item.href ? styles.active : ''}`}
        aria-current={active === item.href ? 'page' : undefined}
      >
        <Icon className={styles.navIcon} size={19} aria-hidden="true" />
        <span className={styles.navLabel}>{item.label}</span>
      </Link>
    );
  }
  return (
    <aside className={styles.sidebar}>
      <div className={styles.brand}>
        <Crown className={styles.brandMark} size={24} aria-hidden="true" />
        <span className={styles.brandName}>{brand.shortName} Admin</span>
      </div>
      <nav className={styles.nav} aria-label="Administration">
        <div className={styles.primaryNav}>{ADMIN_MAIN_NAV.map(itemLink)}</div>
        {ADMIN_NAV_GROUPS.map((group) => {
          const Icon = group.icon;
          return (
            <details
              className={styles.navGroup}
              key={group.title}
              open={group.items.some((item) => item.href === active)}
            >
              <summary>
                <Icon size={19} aria-hidden="true" />
                <span>{group.title}</span>
                <ChevronDown className={styles.chevron} size={15} aria-hidden="true" />
              </summary>
              <div className={styles.subnav}>{group.items.map(itemLink)}</div>
            </details>
          );
        })}
      </nav>
      <div className={styles.footer}>
        {user && (
          <div className={styles.account}>
            <UserRound size={24} aria-hidden="true" />
            <span className={styles.authText} title={user.email ?? undefined}>
              {user.displayName ?? user.email ?? 'Administratör'}
            </span>
            <button
              className={styles.signOutButton}
              type="button"
              onClick={() => void signOut()}
              aria-label="Logga ut"
            >
              <LogOut size={18} aria-hidden="true" />
            </button>
          </div>
        )}
      </div>
    </aside>
  );
}
