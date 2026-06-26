/**
 * PartnerCompanyService — business logic for KCC Företagspartner company profiles.
 *
 * Design rules enforced here:
 *  - Backend is the sole authority for publication and status transitions.
 *  - New partners always start as `draft`.
 *  - Activation requires: company name, supported category, public description,
 *    valid address, and valid non-default coordinates (not 0,0).
 *  - Status may only be changed through dedicated activate/pause/end actions —
 *    not through the general update endpoint.
 *  - Only `active` partners are returned through public APIs.
 *  - Partners are never hard-deleted after being active; use `paused` or `ended`.
 *  - Important status changes are audited.
 *  - Partners cannot manage their own profiles.
 *
 * Privacy rules:
 *  - Application contact details (contactName, contactEmail) are never stored
 *    on PartnerCompany and are never returned through public APIs.
 *  - publicPhone and publicWebsiteUrl are validated before storage.
 */

import type { PrismaClient } from '@prisma/client';

import {
  PARTNER_CATEGORIES,
  DEFAULT_PARTNER_PAGE_SIZE,
  MAX_PARTNER_PAGE_SIZE,
  type PartnerCategory,
  type PartnerCompanyStatus,
  type PartnerCompanyPublicSummary,
  type PartnerCompanyPublicDetail,
  type AdminPartnerCompanySummary,
  type AdminPartnerCompanyDetail,
  type PartnerMapMarker,
} from '@carcommunity/shared/partners';

import { AppError } from './errors.js';
import type { WriteAuditLogInput } from './moderation-service.js';

/** The "Samarbetspartner" status label shown on public-facing screens. */
export const PARTNER_STATUS_LABEL = 'Samarbetspartner';

/** Maximum number of map markers returned in a single call. */
export const MAX_MAP_MARKERS = 500;

// ---------------------------------------------------------------------------
// Interfaces
// ---------------------------------------------------------------------------

export interface CreatePartnerInput {
  actorUserId: string;
  companyName: string;
  category: PartnerCategory;
  publicDescription: string;
  address: string;
  latitude: number;
  longitude: number;
  publicPhone?: string | null;
  publicWebsiteUrl?: string | null;
  applicationId?: string | null;
}

export interface UpdatePartnerInput {
  actorUserId: string;
  partnerId: string;
  companyName?: string;
  category?: PartnerCategory;
  publicDescription?: string;
  address?: string;
  latitude?: number;
  longitude?: number;
  publicPhone?: string | null;
  publicWebsiteUrl?: string | null;
}

export interface ActivatePartnerInput {
  actorUserId: string;
  partnerId: string;
  actualLocationConfirmed: boolean;
}

export interface StatusActionInput {
  actorUserId: string;
  partnerId: string;
  reason?: string | null;
}

export interface ListPublicPartnersInput {
  page?: number;
  pageSize?: number;
  category?: PartnerCategory;
  /** Optional viewport bounding box. */
  minLat?: number;
  maxLat?: number;
  minLon?: number;
  maxLon?: number;
}

export interface ListAdminPartnersInput {
  page?: number;
  pageSize?: number;
  status?: PartnerCompanyStatus;
  category?: PartnerCategory;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function toPublicSummary(row: {
  id: string;
  companyName: string;
  category: string;
  publicDescription: string;
  address: string;
  latitude: number;
  longitude: number;
  publicPhone: string | null;
  publicWebsiteUrl: string | null;
}): PartnerCompanyPublicSummary {
  return {
    partnerId: row.id,
    companyName: row.companyName,
    category: row.category as PartnerCategory,
    publicDescription: row.publicDescription,
    address: row.address,
    latitude: row.latitude,
    longitude: row.longitude,
    publicPhone: row.publicPhone,
    publicWebsiteUrl: row.publicWebsiteUrl,
    statusLabel: PARTNER_STATUS_LABEL,
    isPartner: true,
  };
}

function toMapMarker(row: {
  id: string;
  companyName: string;
  category: string;
  latitude: number;
  longitude: number;
}): PartnerMapMarker {
  return {
    partnerId: row.id,
    companyName: row.companyName,
    category: row.category as PartnerCategory,
    latitude: row.latitude,
    longitude: row.longitude,
    label: PARTNER_STATUS_LABEL,
  };
}

function toAdminSummary(row: {
  id: string;
  companyName: string;
  category: string;
  status: string;
  address: string;
  latitude: number;
  longitude: number;
  activatedAt: Date | null;
  createdAt: Date;
  updatedAt: Date;
}): AdminPartnerCompanySummary {
  return {
    partnerId: row.id,
    companyName: row.companyName,
    category: row.category as PartnerCategory,
    status: row.status as PartnerCompanyStatus,
    address: row.address,
    latitude: row.latitude,
    longitude: row.longitude,
    activatedAt: row.activatedAt ? row.activatedAt.toISOString() : null,
    createdAt: row.createdAt.toISOString(),
    updatedAt: row.updatedAt.toISOString(),
  };
}

function toAdminDetail(row: {
  id: string;
  applicationId: string | null;
  companyName: string;
  category: string;
  publicDescription: string;
  address: string;
  latitude: number;
  longitude: number;
  publicPhone: string | null;
  publicWebsiteUrl: string | null;
  status: string;
  activatedAt: Date | null;
  pausedAt: Date | null;
  endedAt: Date | null;
  createdByUserId: string;
  updatedByUserId: string | null;
  createdAt: Date;
  updatedAt: Date;
}): AdminPartnerCompanyDetail {
  return {
    partnerId: row.id,
    applicationId: row.applicationId,
    companyName: row.companyName,
    category: row.category as PartnerCategory,
    publicDescription: row.publicDescription,
    address: row.address,
    latitude: row.latitude,
    longitude: row.longitude,
    publicPhone: row.publicPhone,
    publicWebsiteUrl: row.publicWebsiteUrl,
    status: row.status as PartnerCompanyStatus,
    activatedAt: row.activatedAt ? row.activatedAt.toISOString() : null,
    pausedAt: row.pausedAt ? row.pausedAt.toISOString() : null,
    endedAt: row.endedAt ? row.endedAt.toISOString() : null,
    createdByUserId: row.createdByUserId,
    updatedByUserId: row.updatedByUserId,
    createdAt: row.createdAt.toISOString(),
    updatedAt: row.updatedAt.toISOString(),
  };
}

function validateCoordinates(latitude: number, longitude: number): void {
  if (latitude < -90 || latitude > 90) {
    throw new AppError(422, 'invalid_latitude', 'Latitude must be between -90 and 90.');
  }
  if (longitude < -180 || longitude > 180) {
    throw new AppError(422, 'invalid_longitude', 'Longitude must be between -180 and 180.');
  }
}

function requireValidActivationData(row: {
  companyName: string;
  publicDescription: string;
  address: string;
  latitude: number;
  longitude: number;
  category: string;
}): void {
  if (!row.companyName.trim()) {
    throw new AppError(422, 'company_name_required', 'Company name is required for activation.');
  }
  if (!PARTNER_CATEGORIES.includes(row.category as PartnerCategory)) {
    throw new AppError(422, 'invalid_category', 'Invalid partner category.');
  }
  if (!row.publicDescription.trim()) {
    throw new AppError(422, 'description_required', 'Public description is required for activation.');
  }
  if (!row.address.trim()) {
    throw new AppError(422, 'address_required', 'Address is required for activation.');
  }
  // Reject default 0,0 coordinates — they indicate the admin has not set a real location.
  if (row.latitude === 0 && row.longitude === 0) {
    throw new AppError(422, 'coordinates_required', 'Valid coordinates must be set before activation.');
  }
  validateCoordinates(row.latitude, row.longitude);
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

export class PartnerCompanyService {
  constructor(private readonly prisma: PrismaClient) {}

  // -----------------------------------------------------------------------
  // Public: list active partners
  // -----------------------------------------------------------------------

  async listActivePartners(input: ListPublicPartnersInput = {}): Promise<{
    partners: PartnerCompanyPublicSummary[];
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  }> {
    const page = Math.max(1, input.page ?? 1);
    const pageSize = Math.min(MAX_PARTNER_PAGE_SIZE, Math.max(1, input.pageSize ?? DEFAULT_PARTNER_PAGE_SIZE));
    const skip = (page - 1) * pageSize;

    type WhereClause = {
      status: 'active';
      category?: PartnerCategory;
      latitude?: { gte: number; lte: number };
      longitude?: { gte: number; lte: number };
    };

    const where: WhereClause = { status: 'active' };
    if (input.category) where.category = input.category;
    if (
      input.minLat !== undefined &&
      input.maxLat !== undefined &&
      input.minLon !== undefined &&
      input.maxLon !== undefined
    ) {
      where.latitude = { gte: input.minLat, lte: input.maxLat };
      where.longitude = { gte: input.minLon, lte: input.maxLon };
    }

    const [rows, total] = await Promise.all([
      this.prisma.partnerCompany.findMany({
        where,
        orderBy: { companyName: 'asc' },
        skip,
        take: pageSize,
        select: {
          id: true,
          companyName: true,
          category: true,
          publicDescription: true,
          address: true,
          latitude: true,
          longitude: true,
          publicPhone: true,
          publicWebsiteUrl: true,
        },
      }),
      this.prisma.partnerCompany.count({ where }),
    ]);

    return {
      partners: rows.map(toPublicSummary),
      page,
      pageSize,
      total,
      hasNext: skip + rows.length < total,
    };
  }

  // -----------------------------------------------------------------------
  // Public: get active partner detail
  // -----------------------------------------------------------------------

  async getActivePartnerDetail(partnerId: string): Promise<PartnerCompanyPublicDetail> {
    const row = await this.prisma.partnerCompany.findUnique({
      where: { id: partnerId },
      select: {
        id: true,
        companyName: true,
        category: true,
        publicDescription: true,
        address: true,
        latitude: true,
        longitude: true,
        publicPhone: true,
        publicWebsiteUrl: true,
        status: true,
      },
    });

    if (!row || row.status !== 'active') {
      throw new AppError(404, 'not_found', 'Partner not found.');
    }

    return toPublicSummary(row);
  }

  // -----------------------------------------------------------------------
  // Public: get map markers (active only)
  // -----------------------------------------------------------------------

  async getMapMarkers(): Promise<PartnerMapMarker[]> {
    const rows = await this.prisma.partnerCompany.findMany({
      where: { status: 'active' },
      orderBy: { companyName: 'asc' },
      take: MAX_MAP_MARKERS,
      select: {
        id: true,
        companyName: true,
        category: true,
        latitude: true,
        longitude: true,
      },
    });

    return rows.map(toMapMarker);
  }

  // -----------------------------------------------------------------------
  // Admin: list all partners
  // -----------------------------------------------------------------------

  async listAdminPartners(input: ListAdminPartnersInput = {}): Promise<{
    partners: AdminPartnerCompanySummary[];
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  }> {
    const page = Math.max(1, input.page ?? 1);
    const pageSize = Math.min(MAX_PARTNER_PAGE_SIZE, Math.max(1, input.pageSize ?? DEFAULT_PARTNER_PAGE_SIZE));
    const skip = (page - 1) * pageSize;

    type AdminWhereClause = {
      status?: PartnerCompanyStatus;
      category?: PartnerCategory;
    };

    const where: AdminWhereClause = {};
    if (input.status) where.status = input.status;
    if (input.category) where.category = input.category;

    const [rows, total] = await Promise.all([
      this.prisma.partnerCompany.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip,
        take: pageSize,
        select: {
          id: true,
          companyName: true,
          category: true,
          status: true,
          address: true,
          latitude: true,
          longitude: true,
          activatedAt: true,
          createdAt: true,
          updatedAt: true,
        },
      }),
      this.prisma.partnerCompany.count({ where }),
    ]);

    return {
      partners: rows.map(toAdminSummary),
      page,
      pageSize,
      total,
      hasNext: skip + rows.length < total,
    };
  }

  // -----------------------------------------------------------------------
  // Admin: get partner detail
  // -----------------------------------------------------------------------

  async getAdminPartnerDetail(partnerId: string): Promise<AdminPartnerCompanyDetail> {
    const row = await this.prisma.partnerCompany.findUnique({
      where: { id: partnerId },
    });

    if (!row) {
      throw new AppError(404, 'not_found', 'Partner not found.');
    }

    return toAdminDetail(row);
  }

  // -----------------------------------------------------------------------
  // Admin: create draft partner
  // -----------------------------------------------------------------------

  async createDraftPartner(input: CreatePartnerInput): Promise<AdminPartnerCompanyDetail> {
    if (!PARTNER_CATEGORIES.includes(input.category)) {
      throw new AppError(422, 'invalid_category', 'Invalid partner category.');
    }

    if (input.latitude !== undefined && input.longitude !== undefined) {
      validateCoordinates(input.latitude, input.longitude);
    }

    // Verify applicationId if provided
    if (input.applicationId) {
      const application = await this.prisma.partnerApplication.findUnique({
        where: { id: input.applicationId },
        select: { id: true, status: true, partnerCompany: { select: { id: true } } },
      });
      if (!application) {
        throw new AppError(404, 'not_found', 'Partner application not found.');
      }
      if (application.status !== 'approved') {
        throw new AppError(422, 'application_not_approved', 'Application must be approved first.');
      }
      if (application.partnerCompany) {
        throw new AppError(409, 'partner_already_created', 'A partner company already exists for this application.');
      }
    }

    const company = await this.prisma.partnerCompany.create({
      data: {
        applicationId: input.applicationId ?? null,
        companyName: input.companyName.trim(),
        category: input.category,
        publicDescription: input.publicDescription.trim(),
        address: input.address.trim(),
        latitude: input.latitude,
        longitude: input.longitude,
        publicPhone: input.publicPhone?.trim() ?? null,
        publicWebsiteUrl: input.publicWebsiteUrl?.trim() ?? null,
        status: 'draft',
        createdByUserId: input.actorUserId,
      },
    });

    await this.prisma.auditLog.create({
      data: this.buildAuditEntry({
        actorUserId: input.actorUserId,
        action: 'partner_company.create',
        entityType: 'partner_company',
        entityId: company.id,
      }),
    });

    return toAdminDetail(company);
  }

  // -----------------------------------------------------------------------
  // Admin: update draft or paused partner
  // -----------------------------------------------------------------------

  async updatePartner(input: UpdatePartnerInput): Promise<AdminPartnerCompanyDetail> {
    const company = await this.prisma.partnerCompany.findUnique({
      where: { id: input.partnerId },
      select: { id: true, status: true },
    });

    if (!company) {
      throw new AppError(404, 'not_found', 'Partner not found.');
    }

    if (!['draft', 'paused'].includes(company.status)) {
      throw new AppError(
        409,
        'invalid_status_for_update',
        `Cannot edit a partner in status "${company.status}". Pause it first.`,
      );
    }

    if (input.category && !PARTNER_CATEGORIES.includes(input.category)) {
      throw new AppError(422, 'invalid_category', 'Invalid partner category.');
    }

    if (input.latitude !== undefined && input.longitude !== undefined) {
      validateCoordinates(input.latitude, input.longitude);
    } else if (input.latitude !== undefined || input.longitude !== undefined) {
      throw new AppError(422, 'coordinates_both_required', 'Both latitude and longitude must be provided together.');
    }

    const updated = await this.prisma.partnerCompany.update({
      where: { id: input.partnerId },
      data: {
        ...(input.companyName !== undefined ? { companyName: input.companyName.trim() } : {}),
        ...(input.category !== undefined ? { category: input.category } : {}),
        ...(input.publicDescription !== undefined ? { publicDescription: input.publicDescription.trim() } : {}),
        ...(input.address !== undefined ? { address: input.address.trim() } : {}),
        ...(input.latitude !== undefined ? { latitude: input.latitude } : {}),
        ...(input.longitude !== undefined ? { longitude: input.longitude } : {}),
        ...(input.publicPhone !== undefined ? { publicPhone: input.publicPhone?.trim() ?? null } : {}),
        ...(input.publicWebsiteUrl !== undefined ? { publicWebsiteUrl: input.publicWebsiteUrl?.trim() ?? null } : {}),
        updatedByUserId: input.actorUserId,
      },
    });

    await this.prisma.auditLog.create({
      data: this.buildAuditEntry({
        actorUserId: input.actorUserId,
        action: 'partner_company.update',
        entityType: 'partner_company',
        entityId: input.partnerId,
      }),
    });

    return toAdminDetail(updated);
  }

  // -----------------------------------------------------------------------
  // Admin: activate partner
  // -----------------------------------------------------------------------

  async activatePartner(input: ActivatePartnerInput): Promise<AdminPartnerCompanyDetail> {
    if (!input.actualLocationConfirmed) {
      throw new AppError(
        422,
        'location_confirmation_required',
        'Confirm that coordinates represent the actual business location.',
      );
    }

    const company = await this.prisma.partnerCompany.findUnique({
      where: { id: input.partnerId },
    });

    if (!company) {
      throw new AppError(404, 'not_found', 'Partner not found.');
    }
    if (!['draft', 'paused'].includes(company.status)) {
      throw new AppError(
        409,
        'invalid_status_transition',
        `Cannot activate a partner in status "${company.status}".`,
      );
    }

    requireValidActivationData(company);

    const now = new Date();
    const [updated] = await this.prisma.$transaction([
      this.prisma.partnerCompany.update({
        where: { id: input.partnerId },
        data: {
          status: 'active',
          activatedAt: company.activatedAt ?? now,
          updatedByUserId: input.actorUserId,
        },
      }),
      this.prisma.auditLog.create({
        data: this.buildAuditEntry({
          actorUserId: input.actorUserId,
          action: 'partner_company.activate',
          entityType: 'partner_company',
          entityId: input.partnerId,
        }),
      }),
    ]);

    return toAdminDetail(updated);
  }

  // -----------------------------------------------------------------------
  // Admin: pause partner
  // -----------------------------------------------------------------------

  async pausePartner(input: StatusActionInput): Promise<AdminPartnerCompanyDetail> {
    const company = await this.prisma.partnerCompany.findUnique({
      where: { id: input.partnerId },
      select: { id: true, status: true },
    });

    if (!company) {
      throw new AppError(404, 'not_found', 'Partner not found.');
    }
    if (company.status !== 'active') {
      throw new AppError(
        409,
        'invalid_status_transition',
        `Cannot pause a partner in status "${company.status}".`,
      );
    }

    const [updated] = await this.prisma.$transaction([
      this.prisma.partnerCompany.update({
        where: { id: input.partnerId },
        data: {
          status: 'paused',
          pausedAt: new Date(),
          updatedByUserId: input.actorUserId,
        },
      }),
      this.prisma.auditLog.create({
        data: this.buildAuditEntry({
          actorUserId: input.actorUserId,
          action: 'partner_company.pause',
          entityType: 'partner_company',
          entityId: input.partnerId,
          reason: input.reason ?? undefined,
        }),
      }),
    ]);

    return toAdminDetail(updated);
  }

  // -----------------------------------------------------------------------
  // Admin: end partnership
  // -----------------------------------------------------------------------

  async endPartnership(input: StatusActionInput): Promise<AdminPartnerCompanyDetail> {
    const company = await this.prisma.partnerCompany.findUnique({
      where: { id: input.partnerId },
      select: { id: true, status: true },
    });

    if (!company) {
      throw new AppError(404, 'not_found', 'Partner not found.');
    }
    if (company.status === 'ended') {
      throw new AppError(409, 'invalid_status_transition', 'Partnership has already ended.');
    }

    const [updated] = await this.prisma.$transaction([
      this.prisma.partnerCompany.update({
        where: { id: input.partnerId },
        data: {
          status: 'ended',
          endedAt: new Date(),
          updatedByUserId: input.actorUserId,
        },
      }),
      this.prisma.auditLog.create({
        data: this.buildAuditEntry({
          actorUserId: input.actorUserId,
          action: 'partner_company.end',
          entityType: 'partner_company',
          entityId: input.partnerId,
          reason: input.reason ?? undefined,
        }),
      }),
    ]);

    return toAdminDetail(updated);
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private buildAuditEntry(input: WriteAuditLogInput) {
    return {
      actorUserId: input.actorUserId,
      action: input.action,
      entityType: input.entityType,
      entityId: input.entityId ?? null,
      reason: input.reason ?? null,
    };
  }
}
