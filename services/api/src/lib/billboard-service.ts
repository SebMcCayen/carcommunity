/**
 * BillboardService — backend business logic for sponsored digital billboards.
 *
 * Design rules:
 *  - New billboards always start as draft — status is never accepted from the client.
 *  - Activation requires all 6 safety confirmations (all must be true) plus a non-empty approvalReason.
 *  - Public list/markers: only status='active', partner status='active', within availability window.
 *  - safetyNote and approvalReason are internal — never returned in public responses.
 *  - Do not hard-delete billboards; use pause/end instead.
 *  - All important admin actions write audit log entries.
 *  - Select only required fields — no N+1 queries.
 *
 * TODO: Add Mapbox road/parking-data validation for placement safety
 *       (admin confirmation is required for MVP).
 */

import { Prisma, type PrismaClient } from '@prisma/client';
import type {
  BillboardStatus,
  BillboardPlacementType,
  BillboardCtaType,
  AdminBillboardSummary,
  PublicBillboardDetail,
  PublicBillboardMapMarker,
  AdminCreateBillboardRequest,
  AdminUpdateBillboardRequest,
  AdminActivateBillboardRequest,
} from '@carcommunity/shared/digital-billboards';
import {
  BILLBOARD_PLACEMENT_TYPES,
  BILLBOARD_CTA_TYPES,
  MAX_BILLBOARD_HEADLINE_LENGTH,
  MAX_BILLBOARD_MESSAGE_LENGTH,
  MAX_BILLBOARD_SAFETY_NOTE_LENGTH,
  MAX_BILLBOARD_CTA_VALUE_LENGTH,
  DEFAULT_BILLBOARD_PAGE_SIZE,
  MAX_BILLBOARD_PAGE_SIZE,
} from '@carcommunity/shared/digital-billboards';

import { AppError } from './errors.js';

const SPONSOR_LABEL = 'Sponsrad placering';

export interface CreateDraftInput extends AdminCreateBillboardRequest {
  actorUserId: string;
}

export interface UpdateInput extends AdminUpdateBillboardRequest {
  actorUserId: string;
}

export type ActivateInput = AdminActivateBillboardRequest;

export interface ListPublicMarkersOpts {
  page?: number;
  pageSize?: number;
  now?: Date;
}

export interface ListPublicOpts {
  page?: number;
  pageSize?: number;
  now?: Date;
}

export interface AdminListOpts {
  page?: number;
  pageSize?: number;
}

async function writeAuditLog(
  prisma: PrismaClient,
  entry: {
    actorUserId: string | null;
    action: string;
    entityId: string;
    reason?: string | null;
    metadata?: Prisma.InputJsonObject;
  },
): Promise<void> {
  await prisma.auditLog.create({
    data: {
      actorUserId: entry.actorUserId,
      action: entry.action,
      entityType: 'sponsored_billboard',
      entityId: entry.entityId,
      reason: entry.reason ?? null,
      metadata: entry.metadata ?? Prisma.JsonNull,
    },
  });
}

type BillboardRow = {
  id: string;
  partnerCompanyId: string;
  headline: string;
  message: string;
  placementType: string;
  latitude: number;
  longitude: number;
  status: string;
  availableFrom: Date | null;
  availableUntil: Date | null;
  callToActionType: string | null;
  callToActionValue: string | null;
  safetyNote: string | null;
  approvalReason: string | null;
  approvedAt: Date | null;
  approvedByUserId: string | null;
  activatedAt: Date | null;
  pausedAt: Date | null;
  endedAt: Date | null;
  createdByUserId: string;
  updatedByUserId: string | null;
  createdAt: Date;
  updatedAt: Date;
  partnerCompany: { companyName: string; status?: string };
};

function toAdminSummary(row: BillboardRow): AdminBillboardSummary {
  return {
    billboardId: row.id,
    partnerId: row.partnerCompanyId,
    partnerCompanyName: row.partnerCompany.companyName,
    headline: row.headline,
    message: row.message,
    placementType: row.placementType as BillboardPlacementType,
    latitude: row.latitude,
    longitude: row.longitude,
    status: row.status as BillboardStatus,
    availableFrom: row.availableFrom?.toISOString() ?? null,
    availableUntil: row.availableUntil?.toISOString() ?? null,
    callToActionType: (row.callToActionType as BillboardCtaType | null) ?? null,
    callToActionValue: row.callToActionValue,
    approvedAt: row.approvedAt?.toISOString() ?? null,
    approvedByUserId: row.approvedByUserId,
    activatedAt: row.activatedAt?.toISOString() ?? null,
    pausedAt: row.pausedAt?.toISOString() ?? null,
    endedAt: row.endedAt?.toISOString() ?? null,
    safetyNote: row.safetyNote,
    createdByUserId: row.createdByUserId,
    updatedByUserId: row.updatedByUserId,
    createdAt: row.createdAt.toISOString(),
    updatedAt: row.updatedAt.toISOString(),
  };
}

function toPublicDetail(row: BillboardRow): PublicBillboardDetail {
  return {
    billboardId: row.id,
    partnerId: row.partnerCompanyId,
    partnerCompanyName: row.partnerCompany.companyName,
    headline: row.headline,
    message: row.message,
    latitude: row.latitude,
    longitude: row.longitude,
    sponsorLabel: SPONSOR_LABEL,
    availableFrom: row.availableFrom?.toISOString() ?? null,
    availableUntil: row.availableUntil?.toISOString() ?? null,
    callToActionType: (row.callToActionType as BillboardCtaType | null) ?? null,
    callToActionValue: row.callToActionValue,
    placementType: row.placementType as BillboardPlacementType,
  };
}

function toPublicMarker(row: BillboardRow): PublicBillboardMapMarker {
  return {
    billboardId: row.id,
    partnerId: row.partnerCompanyId,
    partnerCompanyName: row.partnerCompany.companyName,
    headline: row.headline,
    message: row.message,
    latitude: row.latitude,
    longitude: row.longitude,
    sponsorLabel: SPONSOR_LABEL,
    availableUntil: row.availableUntil?.toISOString() ?? null,
    callToActionType: (row.callToActionType as BillboardCtaType | null) ?? null,
  };
}

const BILLBOARD_SELECT = {
  id: true,
  partnerCompanyId: true,
  headline: true,
  message: true,
  placementType: true,
  latitude: true,
  longitude: true,
  status: true,
  availableFrom: true,
  availableUntil: true,
  callToActionType: true,
  callToActionValue: true,
  safetyNote: true,
  approvalReason: true,
  approvedAt: true,
  approvedByUserId: true,
  activatedAt: true,
  pausedAt: true,
  endedAt: true,
  createdByUserId: true,
  updatedByUserId: true,
  createdAt: true,
  updatedAt: true,
  partnerCompany: {
    select: { companyName: true, status: true },
  },
} as const;

function buildActiveFilter(now: Date) {
  return {
    status: 'active' as const,
    partnerCompany: { is: { status: 'active' as const } },
    AND: [
      {
        OR: [{ availableFrom: null }, { availableFrom: { lte: now } }],
      },
      {
        OR: [{ availableUntil: null }, { availableUntil: { gte: now } }],
      },
    ],
  };
}

function validateBillboardFields(data: {
  headline?: string;
  message?: string;
  latitude?: number;
  longitude?: number;
  availableFrom?: string | null;
  availableUntil?: string | null;
  callToActionType?: BillboardCtaType | null;
  callToActionValue?: string | null;
  safetyNote?: string | null;
  placementType?: BillboardPlacementType;
}) {
  if (data.headline !== undefined) {
    if (!data.headline.trim()) {
      throw new AppError(400, 'billboard_headline_required', 'Headline is required.');
    }
    if (data.headline.length > MAX_BILLBOARD_HEADLINE_LENGTH) {
      throw new AppError(400, 'validation_error', `Headline must be at most ${MAX_BILLBOARD_HEADLINE_LENGTH} characters.`);
    }
  }
  if (data.message !== undefined) {
    if (!data.message.trim()) {
      throw new AppError(400, 'billboard_message_required', 'Message is required.');
    }
    if (data.message.length > MAX_BILLBOARD_MESSAGE_LENGTH) {
      throw new AppError(400, 'validation_error', `Message must be at most ${MAX_BILLBOARD_MESSAGE_LENGTH} characters.`);
    }
  }
  if (data.latitude !== undefined || data.longitude !== undefined) {
    const lat = data.latitude ?? 0;
    const lon = data.longitude ?? 0;
    if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
      throw new AppError(400, 'billboard_invalid_coordinates', 'Invalid coordinates.');
    }
  }
  if (data.safetyNote !== undefined && data.safetyNote !== null) {
    if (data.safetyNote.length > MAX_BILLBOARD_SAFETY_NOTE_LENGTH) {
      throw new AppError(400, 'validation_error', `Safety note must be at most ${MAX_BILLBOARD_SAFETY_NOTE_LENGTH} characters.`);
    }
  }
  if (data.callToActionType && !BILLBOARD_CTA_TYPES.includes(data.callToActionType as BillboardCtaType)) {
    throw new AppError(400, 'billboard_invalid_cta', 'Invalid call-to-action type.');
  }
  if (data.callToActionValue !== undefined && data.callToActionValue !== null) {
    if (data.callToActionValue.length > MAX_BILLBOARD_CTA_VALUE_LENGTH) {
      throw new AppError(400, 'validation_error', `CTA value must be at most ${MAX_BILLBOARD_CTA_VALUE_LENGTH} characters.`);
    }
  }
  if (data.placementType && !BILLBOARD_PLACEMENT_TYPES.includes(data.placementType as BillboardPlacementType)) {
    throw new AppError(400, 'validation_error', 'Invalid placement type.');
  }
  if (data.availableFrom && data.availableUntil) {
    const from = new Date(data.availableFrom);
    const until = new Date(data.availableUntil);
    if (Number.isNaN(from.getTime()) || Number.isNaN(until.getTime()) || from >= until) {
      throw new AppError(400, 'billboard_invalid_date_range', 'availableFrom must be before availableUntil.');
    }
  }

  const callToActionType = data.callToActionType ?? null;
  const callToActionValue = data.callToActionValue?.trim() ?? null;
  if ((callToActionType === 'phone' || callToActionType === 'website') && !callToActionValue) {
    throw new AppError(400, 'billboard_invalid_cta', 'CTA value is required for phone and website call-to-action types.');
  }
  if (callToActionType && !['phone', 'website'].includes(callToActionType) && callToActionValue) {
    throw new AppError(400, 'billboard_invalid_cta', 'CTA value is only allowed for phone or website call-to-action types.');
  }
}

export class BillboardService {
  constructor(private readonly prisma: PrismaClient) {}

  async listPublicMarkers(opts: ListPublicMarkersOpts = {}): Promise<{
    markers: PublicBillboardMapMarker[];
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
    generatedAt: string;
  }> {
    const now = opts.now ?? new Date();
    const page = Math.max(1, opts.page ?? 1);
    const pageSize = Math.min(MAX_BILLBOARD_PAGE_SIZE, Math.max(1, opts.pageSize ?? DEFAULT_BILLBOARD_PAGE_SIZE));
    const skip = (page - 1) * pageSize;

    const where = buildActiveFilter(now);

    const [rows, total] = await Promise.all([
      this.prisma.sponsoredBillboard.findMany({
        where,
        select: BILLBOARD_SELECT,
        skip,
        take: pageSize,
        orderBy: { createdAt: 'asc' },
      }),
      this.prisma.sponsoredBillboard.count({ where }),
    ]);

    return {
      markers: (rows as BillboardRow[]).map(toPublicMarker),
      page,
      pageSize,
      total,
      hasNext: skip + pageSize < total,
      generatedAt: now.toISOString(),
    };
  }

  async listPublic(opts: ListPublicOpts = {}): Promise<{
    billboards: PublicBillboardDetail[];
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  }> {
    const now = opts.now ?? new Date();
    const page = Math.max(1, opts.page ?? 1);
    const pageSize = Math.min(MAX_BILLBOARD_PAGE_SIZE, Math.max(1, opts.pageSize ?? DEFAULT_BILLBOARD_PAGE_SIZE));
    const skip = (page - 1) * pageSize;

    const where = buildActiveFilter(now);

    const [rows, total] = await Promise.all([
      this.prisma.sponsoredBillboard.findMany({
        where,
        select: BILLBOARD_SELECT,
        skip,
        take: pageSize,
        orderBy: { createdAt: 'asc' },
      }),
      this.prisma.sponsoredBillboard.count({ where }),
    ]);

    return {
      billboards: (rows as BillboardRow[]).map(toPublicDetail),
      page,
      pageSize,
      total,
      hasNext: skip + pageSize < total,
    };
  }

  async getPublicDetail(billboardId: string, now: Date = new Date()): Promise<PublicBillboardDetail> {
    const row = await this.prisma.sponsoredBillboard.findUnique({
      where: { id: billboardId },
      select: BILLBOARD_SELECT,
    });

    if (!row) {
      throw new AppError(404, 'not_found', 'Billboard not found.');
    }

    const b = row as BillboardRow;

    if (b.status !== 'active' || b.partnerCompany.status !== 'active') {
      throw new AppError(404, 'not_found', 'Billboard not found.');
    }

    if (b.availableFrom && b.availableFrom > now) {
      throw new AppError(404, 'not_found', 'Billboard not found.');
    }
    if (b.availableUntil && b.availableUntil < now) {
      throw new AppError(404, 'not_found', 'Billboard not found.');
    }

    return toPublicDetail(b);
  }

  async adminList(opts: AdminListOpts = {}): Promise<{
    billboards: AdminBillboardSummary[];
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  }> {
    const page = Math.max(1, opts.page ?? 1);
    const pageSize = Math.min(MAX_BILLBOARD_PAGE_SIZE, Math.max(1, opts.pageSize ?? DEFAULT_BILLBOARD_PAGE_SIZE));
    const skip = (page - 1) * pageSize;

    const [rows, total] = await Promise.all([
      this.prisma.sponsoredBillboard.findMany({
        select: BILLBOARD_SELECT,
        skip,
        take: pageSize,
        orderBy: { createdAt: 'desc' },
      }),
      this.prisma.sponsoredBillboard.count(),
    ]);

    return {
      billboards: (rows as BillboardRow[]).map(toAdminSummary),
      page,
      pageSize,
      total,
      hasNext: skip + pageSize < total,
    };
  }

  async adminGetDetail(billboardId: string): Promise<AdminBillboardSummary> {
    const row = await this.prisma.sponsoredBillboard.findUnique({
      where: { id: billboardId },
      select: BILLBOARD_SELECT,
    });

    if (!row) {
      throw new AppError(404, 'not_found', 'Billboard not found.');
    }

    return toAdminSummary(row as BillboardRow);
  }

  async createDraft(input: CreateDraftInput): Promise<AdminBillboardSummary> {
    const { actorUserId, partnerCompanyId, ...fields } = input;

    validateBillboardFields({
      headline: fields.headline,
      message: fields.message,
      latitude: fields.latitude,
      longitude: fields.longitude,
      availableFrom: fields.availableFrom ?? null,
      availableUntil: fields.availableUntil ?? null,
      callToActionType: (fields.callToActionType as BillboardCtaType | null | undefined) ?? null,
      callToActionValue: fields.callToActionValue ?? null,
      safetyNote: fields.safetyNote ?? null,
      placementType: fields.placementType as BillboardPlacementType,
    });

    const partner = await this.prisma.partnerCompany.findUnique({
      where: { id: partnerCompanyId },
      select: { id: true, status: true },
    });

    if (!partner) {
      throw new AppError(404, 'billboard_partner_not_found', 'Partner company not found.');
    }

    const row = await this.prisma.sponsoredBillboard.create({
      data: {
        partnerCompanyId,
        headline: fields.headline.trim(),
        message: fields.message.trim(),
        placementType: fields.placementType as BillboardPlacementType,
        latitude: fields.latitude,
        longitude: fields.longitude,
        availableFrom: fields.availableFrom ? new Date(fields.availableFrom) : null,
        availableUntil: fields.availableUntil ? new Date(fields.availableUntil) : null,
        callToActionType: (fields.callToActionType as BillboardCtaType | null | undefined) ?? null,
        callToActionValue: fields.callToActionValue?.trim() ?? null,
        safetyNote: fields.safetyNote?.trim() ?? null,
        createdByUserId: actorUserId,
      },
      select: BILLBOARD_SELECT,
    });

    await writeAuditLog(this.prisma, {
      actorUserId,
      action: 'sponsored_billboard.created',
      entityId: row.id,
      metadata: { billboardId: row.id, partnerId: partnerCompanyId, statusTo: 'draft' },
    });

    return toAdminSummary(row as BillboardRow);
  }

  async updateDraftOrPaused(
    billboardId: string,
    actorUserId: string,
    input: AdminUpdateBillboardRequest,
  ): Promise<AdminBillboardSummary> {
    const existing = await this.prisma.sponsoredBillboard.findUnique({
      where: { id: billboardId },
      select: { id: true, status: true, partnerCompanyId: true },
    });

    if (!existing) {
      throw new AppError(404, 'not_found', 'Billboard not found.');
    }

    if (existing.status !== 'draft' && existing.status !== 'paused') {
      throw new AppError(
        409,
        'billboard_invalid_status_for_update',
        'Only draft or paused billboards can be updated.',
      );
    }

    validateBillboardFields({
      headline: input.headline,
      message: input.message,
      latitude: input.latitude,
      longitude: input.longitude,
      availableFrom: input.availableFrom,
      availableUntil: input.availableUntil,
      callToActionType: (input.callToActionType as BillboardCtaType | null | undefined) ?? null,
      callToActionValue: input.callToActionValue,
      safetyNote: input.safetyNote,
      placementType: input.placementType as BillboardPlacementType | undefined,
    });

    const updateData: Record<string, unknown> = { updatedByUserId: actorUserId };
    if (input.headline !== undefined) updateData['headline'] = input.headline.trim();
    if (input.message !== undefined) updateData['message'] = input.message.trim();
    if (input.placementType !== undefined) updateData['placementType'] = input.placementType;
    if (input.latitude !== undefined) updateData['latitude'] = input.latitude;
    if (input.longitude !== undefined) updateData['longitude'] = input.longitude;
    if ('availableFrom' in input) updateData['availableFrom'] = input.availableFrom ? new Date(input.availableFrom) : null;
    if ('availableUntil' in input) updateData['availableUntil'] = input.availableUntil ? new Date(input.availableUntil) : null;
    if ('callToActionType' in input) updateData['callToActionType'] = input.callToActionType ?? null;
    if ('callToActionValue' in input) updateData['callToActionValue'] = input.callToActionValue?.trim() ?? null;
    if ('safetyNote' in input) updateData['safetyNote'] = input.safetyNote?.trim() ?? null;

    const row = await this.prisma.sponsoredBillboard.update({
      where: { id: billboardId },
      data: updateData,
      select: BILLBOARD_SELECT,
    });

    const changedKeys = Object.keys(input).filter((key) => key !== 'status');
    if (changedKeys.length > 0) {
      await writeAuditLog(this.prisma, {
        actorUserId,
        action: 'sponsored_billboard.updated',
        entityId: billboardId,
        metadata: { billboardId, partnerId: existing.partnerCompanyId, changedFields: changedKeys },
      });
    }

    return toAdminSummary(row as BillboardRow);
  }

  async activate(
    billboardId: string,
    actorUserId: string,
    input: ActivateInput,
  ): Promise<AdminBillboardSummary> {
    const confirmations = [
      input.notBusinessLocationConfirmed,
      input.notRoadLaneConfirmed,
      input.notRoadSignConfirmed,
      input.notObstructingMapConfirmed,
      input.markedAsAdvertisingConfirmed,
      input.suitableForMapConfirmed,
    ];
    if (confirmations.some((confirmation) => confirmation !== true)) {
      throw new AppError(
        400,
        'billboard_safety_confirmation_required',
        'All safety confirmations must be accepted.',
      );
    }
    if (!input.approvalReason || !input.approvalReason.trim()) {
      throw new AppError(400, 'billboard_reason_required', 'Approval reason is required.');
    }

    const existing = await this.prisma.sponsoredBillboard.findUnique({
      where: { id: billboardId },
      select: {
        id: true,
        status: true,
        partnerCompanyId: true,
        latitude: true,
        longitude: true,
        availableFrom: true,
        availableUntil: true,
      },
    });

    if (!existing) {
      throw new AppError(404, 'not_found', 'Billboard not found.');
    }

    if (existing.status !== 'draft' && existing.status !== 'paused') {
      throw new AppError(
        409,
        'billboard_invalid_status_transition',
        'Only draft or paused billboards can be activated.',
      );
    }

    const partner = await this.prisma.partnerCompany.findUnique({
      where: { id: existing.partnerCompanyId },
      select: { id: true, status: true },
    });

    if (!partner) {
      throw new AppError(404, 'billboard_partner_not_found', 'Partner company not found.');
    }

    if (partner.status !== 'active') {
      throw new AppError(
        403,
        'billboard_partner_not_active',
        'Partner company must be active to activate a billboard.',
      );
    }

    if (existing.latitude < -90 || existing.latitude > 90 || existing.longitude < -180 || existing.longitude > 180) {
      throw new AppError(400, 'billboard_invalid_coordinates', 'Billboard has invalid coordinates.');
    }

    if (existing.availableFrom && existing.availableUntil && existing.availableFrom >= existing.availableUntil) {
      throw new AppError(400, 'billboard_invalid_date_range', 'availableFrom must be before availableUntil.');
    }

    const now = new Date();
    const row = await this.prisma.sponsoredBillboard.update({
      where: { id: billboardId },
      data: {
        status: 'active',
        approvedAt: now,
        approvedByUserId: actorUserId,
        activatedAt: now,
        pausedAt: null,
        approvalReason: input.approvalReason.trim(),
        updatedByUserId: actorUserId,
      },
      select: BILLBOARD_SELECT,
    });

    await writeAuditLog(this.prisma, {
      actorUserId,
      action: 'sponsored_billboard.activated',
      entityId: billboardId,
      reason: input.approvalReason.trim(),
      metadata: {
        billboardId,
        partnerId: existing.partnerCompanyId,
        statusFrom: existing.status,
        statusTo: 'active',
      },
    });

    return toAdminSummary(row as BillboardRow);
  }

  async pause(
    billboardId: string,
    actorUserId: string,
    reason: string,
  ): Promise<AdminBillboardSummary> {
    if (!reason || !reason.trim()) {
      throw new AppError(400, 'billboard_reason_required', 'Pause reason is required.');
    }

    const existing = await this.prisma.sponsoredBillboard.findUnique({
      where: { id: billboardId },
      select: { id: true, status: true, partnerCompanyId: true },
    });

    if (!existing) {
      throw new AppError(404, 'not_found', 'Billboard not found.');
    }

    if (existing.status !== 'active') {
      throw new AppError(
        409,
        'billboard_invalid_status_transition',
        'Only active billboards can be paused.',
      );
    }

    const row = await this.prisma.sponsoredBillboard.update({
      where: { id: billboardId },
      data: {
        status: 'paused',
        pausedAt: new Date(),
        updatedByUserId: actorUserId,
      },
      select: BILLBOARD_SELECT,
    });

    await writeAuditLog(this.prisma, {
      actorUserId,
      action: 'sponsored_billboard.paused',
      entityId: billboardId,
      reason: reason.trim(),
      metadata: {
        billboardId,
        partnerId: existing.partnerCompanyId,
        statusFrom: 'active',
        statusTo: 'paused',
      },
    });

    return toAdminSummary(row as BillboardRow);
  }

  async end(
    billboardId: string,
    actorUserId: string,
    reason: string,
  ): Promise<AdminBillboardSummary> {
    if (!reason || !reason.trim()) {
      throw new AppError(400, 'billboard_reason_required', 'End reason is required.');
    }

    const existing = await this.prisma.sponsoredBillboard.findUnique({
      where: { id: billboardId },
      select: { id: true, status: true, partnerCompanyId: true },
    });

    if (!existing) {
      throw new AppError(404, 'not_found', 'Billboard not found.');
    }

    if (existing.status === 'ended') {
      throw new AppError(
        409,
        'billboard_invalid_status_transition',
        'Billboard is already ended.',
      );
    }

    const row = await this.prisma.sponsoredBillboard.update({
      where: { id: billboardId },
      data: {
        status: 'ended',
        endedAt: new Date(),
        updatedByUserId: actorUserId,
      },
      select: BILLBOARD_SELECT,
    });

    await writeAuditLog(this.prisma, {
      actorUserId,
      action: 'sponsored_billboard.ended',
      entityId: billboardId,
      reason: reason.trim(),
      metadata: {
        billboardId,
        partnerId: existing.partnerCompanyId,
        statusFrom: existing.status,
        statusTo: 'ended',
      },
    });

    return toAdminSummary(row as BillboardRow);
  }
}
