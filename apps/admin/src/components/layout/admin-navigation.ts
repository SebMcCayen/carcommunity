import {
  House,
  Users,
  Trophy,
  ShieldAlert,
  FileText,
  Handshake,
  ChartNoAxesCombined,
  Settings,
  CalendarDays,
  Megaphone,
  Bell,
  PanelsTopLeft,
  MapPin,
  ClipboardList,
  CreditCard,
  Wallet,
  Inbox,
  FileWarning,
  UserX,
  ScrollText,
  Flag,
  KeyRound,
  Award,
  type LucideIcon,
} from 'lucide-react';
export type AdminNavItem = { label: string; href: string; icon: LucideIcon };
export type AdminNavGroup = { title: string; icon: LucideIcon; items: AdminNavItem[] };
export const ADMIN_MAIN_NAV: AdminNavItem[] = [
  { label: 'Översikt', href: '/', icon: House },
  { label: 'Medlemmar', href: '/users', icon: Users },
  { label: 'Kronjakten', href: '/kronjakt', icon: Trophy },
  { label: 'Moderering', href: '/moderation', icon: ShieldAlert },
];
export const ADMIN_NAV_GROUPS: AdminNavGroup[] = [
  {
    title: 'Innehåll',
    icon: FileText,
    items: [
      { label: 'Event', href: '/events', icon: CalendarDays },
      { label: 'Anslag', href: '/announcements', icon: Megaphone },
      { label: 'Notiser', href: '/notifications', icon: Bell },
      { label: 'Digitala skyltar', href: '/billboards', icon: PanelsTopLeft },
      { label: 'Utmärkelser', href: '/badges', icon: Award },
    ],
  },
  {
    title: 'Partners',
    icon: Handshake,
    items: [
      { label: 'Partnerföretag', href: '/partners', icon: Handshake },
      { label: 'Ansökningar', href: '/partners/applications', icon: ClipboardList },
      { label: 'Erbjudanden', href: '/partners/offers', icon: CreditCard },
      { label: 'Anonymiserad körstatistik', href: '/partners/drive-heat', icon: MapPin },
    ],
  },
  {
    title: 'Analys och ekonomi',
    icon: ChartNoAxesCombined,
    items: [
      { label: 'Användningsstatistik', href: '/metrics', icon: ChartNoAxesCombined },
      { label: 'Ekonomi och kostnader', href: '/finance', icon: Wallet },
      { label: 'Medlemskap', href: '/subscription', icon: CreditCard },
    ],
  },
  {
    title: 'Drift',
    icon: Settings,
    items: [
      { label: 'Support', href: '/support', icon: Inbox },
      { label: 'Övriga anmälningar', href: '/moderation-reports', icon: ClipboardList },
      { label: 'Felrapporter', href: '/error-reports', icon: FileWarning },
      { label: 'Kontoborttagningar', href: '/account-deletions', icon: UserX },
      { label: 'Åtgärdslogg', href: '/audit-log', icon: ScrollText },
      { label: 'Funktionsflaggor', href: '/feature-flags', icon: Flag },
      { label: 'Behörighetsförnyelser', href: '/credentials', icon: KeyRound },
    ],
  },
];
export const ADMIN_NAV_ITEMS = [
  ...ADMIN_MAIN_NAV,
  ...ADMIN_NAV_GROUPS.flatMap((group) => group.items),
];
/** One active destination, including member/event details and partner subroutes. */
export function activeAdminHref(pathname: string): string | null {
  return (
    ADMIN_NAV_ITEMS.filter((item) =>
      item.href === '/'
        ? pathname === '/'
        : pathname === item.href || pathname.startsWith(item.href + '/'),
    ).sort((a, b) => b.href.length - a.href.length)[0]?.href ?? null
  );
}
