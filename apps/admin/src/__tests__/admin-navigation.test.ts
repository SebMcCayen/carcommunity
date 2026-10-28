import { describe, it, expect } from 'vitest';
import {
  ADMIN_MAIN_NAV,
  ADMIN_NAV_ITEMS,
  activeAdminHref,
} from '@/components/layout/admin-navigation';
describe('admin navigation coverage', () => {
  it('keeps a single destination for each functional tool and excludes placeholder aliases', () => {
    const routes = ADMIN_NAV_ITEMS.map((item) => item.href);
    expect(new Set(routes).size).toBe(routes.length);
    for (const route of [
      '/users',
      '/kronjakt',
      '/moderation',
      '/events',
      '/announcements',
      '/notifications',
      '/partners',
      '/partners/applications',
      '/partners/offers',
      '/partners/drive-heat',
      '/billboards',
      '/badges',
      '/metrics',
      '/finance',
      '/subscription',
      '/support',
      '/moderation-reports',
      '/error-reports',
      '/account-deletions',
      '/audit-log',
      '/feature-flags',
      '/credentials',
    ])
      expect(routes).toContain(route);
    for (const route of ['/reports', '/settings', '/live-location', '/event-chat'])
      expect(routes).not.toContain(route);
    expect(ADMIN_MAIN_NAV.map((item) => item.href)).toEqual([
      '/',
      '/users',
      '/kronjakt',
      '/moderation',
    ]);
  });
  it('activates the most specific partner destination instead of two simultaneous links', () => {
    expect(activeAdminHref('/partners/offers/offer')).toBe('/partners/offers');
    expect(activeAdminHref('/partners/applications')).toBe('/partners/applications');
    expect(activeAdminHref('/partners/company/insights')).toBe('/partners');
    expect(activeAdminHref('/users/member')).toBe('/users');
    expect(activeAdminHref('/')).toBe('/');
    expect(activeAdminHref('/unimplemented')).toBeNull();
  });
});
