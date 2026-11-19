/** Isolated UI fixture transport. Never imported by the production entrypoint. */
import logo from '../../android/app/src/main/res/drawable-nodpi/kcc_logo_dark_bg.png';
export const application = {
  applicationId: 'qa-application',
  companyName: 'Exempelpartner Bilvård',
  organizationNumber: '000000-0000',
  category: 'car_care',
  contactName: 'Testperson',
  contactEmail: 'qa@example.test',
  contactPhone: '070-000 00 00',
  websiteUrl: 'https://example.test',
  proposedDescription:
    'Professionell bilvård med fokus på kvalitet, omsorg och långvarigt resultat.',
  proposedAddress: 'Exempelgatan 14, Kungsbacka',
  message: null,
  status: 'submitted',
  revision: 1,
  submittedByUserId: 'qa-applicant',
  reviewedByUserId: null,
  reviewedAt: null,
  reviewReason: null,
  createdAt: '2026-11-17T10:00:00Z',
  updatedAt: '2026-11-17T10:00:00Z',
  partnerCompanyId: null as string | null,
  selfServiceRequested: true,
  billboardRequested: true,
  billboardPlacementRequest: 'Längs infarten mot centrum. Önskemål granskas separat.',
  logoPath: 'fixture/logo',
  logoPreviewBase64: null,
  publicPhone: '0300-000 00',
};
export const readLogo = async () => {
  const response = await fetch(logo);
  return response.blob();
};
export const reviewApplication = async (
  _id: string,
  decision: string,
  options: Record<string, unknown>,
) => {
  if (decision === 'publish') {
    application.status = 'approved';
    application.partnerCompanyId = 'qa-company';
  } else {
    application.status = 'changes_requested';
    application.reviewReason = String(options.reason);
  }
  return { status: application.status, companyId: application.partnerCompanyId };
};
export const adminRejectApplication = async () => {
  application.status = 'rejected';
};
export const listApplications = async () => ({ items: [application], nextCursor: null });
export const getApplication = async () => application;
