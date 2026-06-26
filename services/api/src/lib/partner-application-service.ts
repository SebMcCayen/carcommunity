/**
 * PartnerApplicationService — business logic for KCC partner applications.
 *
 * Design rules enforced here:
 *  - Backend is the sole authority for application status and approval.
 *  - Applications may be submitted by authenticated users; unauthenticated
 *    website submissions are prepared for (submittedByUserId nullable).
 *  - Applications must not be hard-deleted; use rejected or withdrawn.
 *  - Approval creates a DRAFT partner company — public activation is a
 *    separate explicit admin action.
 *  - Rejection requires a reason, stored internally only.
 *  - Spam detection: duplicate active applications from the same email
 *    or userId are rejected with a friendly message.
 *  - Audit logs are written for review, approval, and rejection.
 *
 * Privacy rules:
 *  - Application contact details (contactName, contactEmail) are internal.
 *    They are never copied to the public PartnerCompany record.
 *  - reviewReason is internal; never returned to the applicant.
 */

import type { PrismaClient } from '@prisma/client';

import {
  PARTNER_CATEGORIES,
  DEFAULT_PARTNER_PAGE_SIZE,
  MAX_PARTNER_PAGE_SIZE,
  type PartnerApplicationStatus,
  type PartnerCategory,
  type AdminPartnerApplicationSummary,
  type AdminPartnerApplicationDetail,
} from '@carcommunity/shared/partners';
import { canAccessAdminFeatures, type UserRole, type UserStatus } from '@carcommunity/shared/users';

import { AppError } from './errors.js';
import type { WriteAuditLogInput } from './moderation-service.js';

// ---------------------------------------------------------------------------
// Interfaces
// ---------------------------------------------------------------------------

export interface ApplicationActor {
  userId: string;
  role: UserRole;
  status: UserStatus;
}

export interface SubmitApplicationInput {
  companyName: string;
  organizationNumber?: string | null;
  category: PartnerCategory;
  contactName: string;
  contactEmail: string;
  contactPhone?: string | null;
  websiteUrl?: string | null;
  proposedDescription?: string | null;
  proposedAddress?: string | null;
  message?: string | null;
  /** Authenticated user who submitted; null for future website submissions. */
  submittedByUserId: string | null;
}

export interface ListApplicationsInput {
  page?: number;
  pageSize?: number;
  status?: PartnerApplicationStatus;
}

export interface ReviewActionInput {
  actorUserId: string;
  applicationId: string;
}

export interface ApproveApplicationInput extends ReviewActionInput {}

export interface RejectApplicationInput extends ReviewActionInput {
  reason: string;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function toAdminSummary(row: {
  id: string;
  companyName: string;
  category: string;
  contactName: string;
  contactEmail: string;
  contactPhone: string | null;
  status: string;
  createdAt: Date;
  reviewedAt: Date | null;
}): AdminPartnerApplicationSummary {
  return {
    applicationId: row.id,
    companyName: row.companyName,
    category: row.category as PartnerCategory,
    contactName: row.contactName,
    contactEmail: row.contactEmail,
    contactPhone: row.contactPhone,
    status: row.status as PartnerApplicationStatus,
    submittedAt: row.createdAt.toISOString(),
    reviewedAt: row.reviewedAt ? row.reviewedAt.toISOString() : null,
  };
}

function toAdminDetail(
  row: {
    id: string;
    companyName: string;
    organizationNumber: string | null;
    category: string;
    contactName: string;
    contactEmail: string;
    contactPhone: string | null;
    websiteUrl: string | null;
    proposedDescription: string | null;
    proposedAddress: string | null;
    message: string | null;
    status: string;
    submittedByUserId: string | null;
    reviewedByUserId: string | null;
    reviewedAt: Date | null;
    reviewReason: string | null;
    createdAt: Date;
    updatedAt: Date;
  },
  partnerCompanyId: string | null,
): AdminPartnerApplicationDetail {
  return {
    applicationId: row.id,
    companyName: row.companyName,
    organizationNumber: row.organizationNumber,
    category: row.category as PartnerCategory,
    contactName: row.contactName,
    contactEmail: row.contactEmail,
    contactPhone: row.contactPhone,
    websiteUrl: row.websiteUrl,
    proposedDescription: row.proposedDescription,
    proposedAddress: row.proposedAddress,
    message: row.message,
    status: row.status as PartnerApplicationStatus,
    submittedByUserId: row.submittedByUserId,
    reviewedByUserId: row.reviewedByUserId,
    reviewedAt: row.reviewedAt ? row.reviewedAt.toISOString() : null,
    reviewReason: row.reviewReason,
    createdAt: row.createdAt.toISOString(),
    updatedAt: row.updatedAt.toISOString(),
    partnerCompanyId,
  };
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

export class PartnerApplicationService {
  constructor(private readonly prisma: PrismaClient) {}

  // -----------------------------------------------------------------------
  // Submit application
  // -----------------------------------------------------------------------

  async submitApplication(input: SubmitApplicationInput): Promise<{ applicationId: string; submittedAt: string }> {
    if (!PARTNER_CATEGORIES.includes(input.category)) {
      throw new AppError(422, 'invalid_category', 'Invalid partner category.');
    }

    // Duplicate-spam guard: block a new submission if the same userId or
    // contactEmail already has an active (submitted / under_review) application.
    const ACTIVE_STATUSES: PartnerApplicationStatus[] = ['submitted', 'under_review'];

    if (input.submittedByUserId) {
      const existing = await this.prisma.partnerApplication.findFirst({
        where: {
          submittedByUserId: input.submittedByUserId,
          status: { in: ACTIVE_STATUSES },
        },
        select: { id: true },
      });
      if (existing) {
        throw new AppError(
          409,
          'duplicate_application',
          'Du har redan en aktiv ansökan. Vänta på att den behandlas.',
        );
      }
    }

    const existingEmail = await this.prisma.partnerApplication.findFirst({
      where: {
        contactEmail: input.contactEmail.toLowerCase().trim(),
        status: { in: ACTIVE_STATUSES },
      },
      select: { id: true },
    });
    if (existingEmail) {
      throw new AppError(
        409,
        'duplicate_application',
        'En aktiv ansökan med denna e-postadress finns redan.',
      );
    }

    const application = await this.prisma.partnerApplication.create({
      data: {
        companyName: input.companyName.trim(),
        organizationNumber: input.organizationNumber?.trim() ?? null,
        category: input.category,
        contactName: input.contactName.trim(),
        contactEmail: input.contactEmail.toLowerCase().trim(),
        contactPhone: input.contactPhone?.trim() ?? null,
        websiteUrl: input.websiteUrl?.trim() ?? null,
        proposedDescription: input.proposedDescription?.trim() ?? null,
        proposedAddress: input.proposedAddress?.trim() ?? null,
        message: input.message?.trim() ?? null,
        submittedByUserId: input.submittedByUserId,
        status: 'submitted',
      },
      select: { id: true, createdAt: true },
    });

    return { applicationId: application.id, submittedAt: application.createdAt.toISOString() };
  }

  // -----------------------------------------------------------------------
  // List applications (admin)
  // -----------------------------------------------------------------------

  async listApplications(input: ListApplicationsInput = {}): Promise<{
    applications: AdminPartnerApplicationSummary[];
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  }> {
    const page = Math.max(1, input.page ?? 1);
    const pageSize = Math.min(MAX_PARTNER_PAGE_SIZE, Math.max(1, input.pageSize ?? DEFAULT_PARTNER_PAGE_SIZE));
    const skip = (page - 1) * pageSize;

    const where = input.status ? { status: input.status } : {};

    const [rows, total] = await Promise.all([
      this.prisma.partnerApplication.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip,
        take: pageSize,
        select: {
          id: true,
          companyName: true,
          category: true,
          contactName: true,
          contactEmail: true,
          contactPhone: true,
          status: true,
          createdAt: true,
          reviewedAt: true,
        },
      }),
      this.prisma.partnerApplication.count({ where }),
    ]);

    return {
      applications: rows.map(toAdminSummary),
      page,
      pageSize,
      total,
      hasNext: skip + rows.length < total,
    };
  }

  // -----------------------------------------------------------------------
  // Get application detail (admin)
  // -----------------------------------------------------------------------

  async getApplicationDetail(applicationId: string): Promise<AdminPartnerApplicationDetail> {
    const row = await this.prisma.partnerApplication.findUnique({
      where: { id: applicationId },
      include: { partnerCompany: { select: { id: true } } },
    });

    if (!row) {
      throw new AppError(404, 'not_found', 'Partner application not found.');
    }

    return toAdminDetail(row, row.partnerCompany?.id ?? null);
  }

  // -----------------------------------------------------------------------
  // Start review (admin)
  // -----------------------------------------------------------------------

  async startReview(input: ReviewActionInput): Promise<void> {
    const application = await this.prisma.partnerApplication.findUnique({
      where: { id: input.applicationId },
      select: { id: true, status: true },
    });

    if (!application) {
      throw new AppError(404, 'not_found', 'Partner application not found.');
    }
    if (application.status !== 'submitted') {
      throw new AppError(
        409,
        'invalid_status_transition',
        `Cannot start review for application in status "${application.status}".`,
      );
    }

    await this.prisma.$transaction([
      this.prisma.partnerApplication.update({
        where: { id: input.applicationId },
        data: {
          status: 'under_review',
          reviewedByUserId: input.actorUserId,
          reviewedAt: new Date(),
        },
      }),
      this.prisma.auditLog.create({
        data: this.buildAuditEntry({
          actorUserId: input.actorUserId,
          action: 'partner_application.start_review',
          entityType: 'partner_application',
          entityId: input.applicationId,
        }),
      }),
    ]);
  }

  // -----------------------------------------------------------------------
  // Approve application (admin)
  // -----------------------------------------------------------------------

  /**
   * Approves the application and creates a DRAFT partner company.
   * Public activation is a separate explicit admin action.
   */
  async approveApplication(input: ApproveApplicationInput): Promise<{ partnerCompanyId: string }> {
    const application = await this.prisma.partnerApplication.findUnique({
      where: { id: input.applicationId },
      select: {
        id: true,
        status: true,
        companyName: true,
        category: true,
        proposedDescription: true,
        proposedAddress: true,
        websiteUrl: true,
        partnerCompany: { select: { id: true } },
      },
    });

    if (!application) {
      throw new AppError(404, 'not_found', 'Partner application not found.');
    }

    // Idempotent: if a company already exists for this application, return it.
    if (application.partnerCompany) {
      return { partnerCompanyId: application.partnerCompany.id };
    }

    if (!['submitted', 'under_review'].includes(application.status)) {
      throw new AppError(
        409,
        'invalid_status_transition',
        `Cannot approve application in status "${application.status}".`,
      );
    }

    const [, company] = await this.prisma.$transaction([
      this.prisma.partnerApplication.update({
        where: { id: input.applicationId },
        data: {
          status: 'approved',
          reviewedByUserId: input.actorUserId,
          reviewedAt: new Date(),
        },
      }),
      this.prisma.partnerCompany.create({
        data: {
          applicationId: input.applicationId,
          companyName: application.companyName,
          category: application.category,
          // Provide placeholder values; admin must fill before activation.
          publicDescription: application.proposedDescription ?? '',
          address: application.proposedAddress ?? '',
          // Coordinates default to 0,0 — admin must set before activation.
          latitude: 0,
          longitude: 0,
          publicWebsiteUrl: application.websiteUrl,
          createdByUserId: input.actorUserId,
          status: 'draft',
        },
        select: { id: true },
      }),
      this.prisma.auditLog.create({
        data: this.buildAuditEntry({
          actorUserId: input.actorUserId,
          action: 'partner_application.approve',
          entityType: 'partner_application',
          entityId: input.applicationId,
        }),
      }),
    ]);

    return { partnerCompanyId: company.id };
  }

  // -----------------------------------------------------------------------
  // Reject application (admin)
  // -----------------------------------------------------------------------

  async rejectApplication(input: RejectApplicationInput): Promise<void> {
    if (!input.reason || input.reason.trim().length === 0) {
      throw new AppError(422, 'reason_required', 'A reason is required for rejection.');
    }

    const application = await this.prisma.partnerApplication.findUnique({
      where: { id: input.applicationId },
      select: { id: true, status: true },
    });

    if (!application) {
      throw new AppError(404, 'not_found', 'Partner application not found.');
    }
    if (!['submitted', 'under_review'].includes(application.status)) {
      throw new AppError(
        409,
        'invalid_status_transition',
        `Cannot reject application in status "${application.status}".`,
      );
    }

    await this.prisma.$transaction([
      this.prisma.partnerApplication.update({
        where: { id: input.applicationId },
        data: {
          status: 'rejected',
          reviewedByUserId: input.actorUserId,
          reviewedAt: new Date(),
          reviewReason: input.reason.trim(),
        },
      }),
      this.prisma.auditLog.create({
        data: this.buildAuditEntry({
          actorUserId: input.actorUserId,
          action: 'partner_application.reject',
          entityType: 'partner_application',
          entityId: input.applicationId,
        }),
      }),
    ]);
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private buildAuditEntry(input: Omit<WriteAuditLogInput, 'reason' | 'metadata'>) {
    return {
      actorUserId: input.actorUserId,
      action: input.action,
      entityType: input.entityType,
      entityId: input.entityId ?? null,
    };
  }
}
