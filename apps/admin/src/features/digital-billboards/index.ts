/**
 * Digital Billboards feature module for the admin portal.
 *
 * Security notes:
 *  - Backend enforces all validation, auth, and audit logging.
 *  - New billboards start as draft — activation requires 6 explicit safety confirmations.
 *  - Do not hard-delete active or previously-active billboards.
 *  - Billboards must always be clearly labelled as advertising.
 *  - Content is never rendered as HTML; plain text only.
 */

import {
  DIGITAL_BILLBOARD_ROUTE_PATHS,
  buildAdminBillboardActivatePath,
  buildAdminBillboardEndPath,
  buildAdminBillboardPath,
  buildAdminBillboardPausePath,
  type AdminActivateBillboardRequest,
  type AdminActivateBillboardResponse,
  type AdminBillboardDetailResponse,
  type AdminBillboardSummary,
  type AdminCreateBillboardRequest,
  type AdminEndBillboardResponse,
  type AdminPauseBillboardResponse,
  type AdminUpdateBillboardRequest,
  type BillboardCtaType,
  type BillboardPlacementType,
  type BillboardStatus,
  type PaginatedAdminBillboardsResponse,
} from '@carcommunity/shared/digital-billboards';

import { ApiError, apiRequest } from '../../lib/api';

export type {
  AdminActivateBillboardRequest,
  AdminBillboardDetailResponse,
  AdminBillboardSummary,
  AdminCreateBillboardRequest,
  AdminUpdateBillboardRequest,
  BillboardCtaType,
  BillboardPlacementType,
  BillboardStatus,
  PaginatedAdminBillboardsResponse,
};
export { ApiError };

export async function adminListBillboards(
  page = 1,
  token?: string,
): Promise<PaginatedAdminBillboardsResponse> {
  return apiRequest<PaginatedAdminBillboardsResponse>(
    `${DIGITAL_BILLBOARD_ROUTE_PATHS.adminList}?page=${page}`,
    { token },
  );
}

export async function adminGetBillboard(
  billboardId: string,
  token?: string,
): Promise<AdminBillboardDetailResponse> {
  return apiRequest<AdminBillboardDetailResponse>(buildAdminBillboardPath(billboardId), { token });
}

export async function adminCreateBillboard(
  request: AdminCreateBillboardRequest,
  token?: string,
): Promise<AdminBillboardDetailResponse> {
  return apiRequest<AdminBillboardDetailResponse>(DIGITAL_BILLBOARD_ROUTE_PATHS.adminList, {
    method: 'POST',
    body: request,
    token,
  });
}

export async function adminUpdateBillboard(
  billboardId: string,
  request: AdminUpdateBillboardRequest,
  token?: string,
): Promise<AdminBillboardDetailResponse> {
  return apiRequest<AdminBillboardDetailResponse>(buildAdminBillboardPath(billboardId), {
    method: 'PATCH',
    body: request,
    token,
  });
}

export async function adminActivateBillboard(
  billboardId: string,
  request: AdminActivateBillboardRequest,
  token?: string,
): Promise<AdminActivateBillboardResponse> {
  return apiRequest<AdminActivateBillboardResponse>(buildAdminBillboardActivatePath(billboardId), {
    method: 'POST',
    body: request,
    token,
  });
}

export async function adminPauseBillboard(
  billboardId: string,
  reason: string,
  token?: string,
): Promise<AdminPauseBillboardResponse> {
  return apiRequest<AdminPauseBillboardResponse>(buildAdminBillboardPausePath(billboardId), {
    method: 'POST',
    body: { reason },
    token,
  });
}

export async function adminEndBillboard(
  billboardId: string,
  reason: string,
  token?: string,
): Promise<AdminEndBillboardResponse> {
  return apiRequest<AdminEndBillboardResponse>(buildAdminBillboardEndPath(billboardId), {
    method: 'POST',
    body: { reason },
    token,
  });
}
