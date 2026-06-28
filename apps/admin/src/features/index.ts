/**
 * Feature modules for the admin portal.
 *
 * Each sub-directory under features/ holds domain-specific API client functions,
 * types, and helpers for a given admin feature area. Pages in src/app/ import
 * from here rather than inlining domain logic in route components.
 */

export * as digitalBillboards from './digital-billboards';
