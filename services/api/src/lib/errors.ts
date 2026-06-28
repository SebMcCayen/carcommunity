import { ZodError } from 'zod';

export type ErrorCode =
  | 'internal_error'
  | 'not_found'
  | 'validation_error'
  | 'invalid_identity_token'
  | 'invalid_identity_provider'
  | 'invalid_identity_audience'
  | 'unauthenticated'
  | 'forbidden'
  | 'suspended'
  | 'feature_disabled'
  | 'conflict'
  | 'self_block'
  // Partner application
  | 'duplicate_application'
  | 'invalid_status_transition'
  | 'reason_required'
  | 'invalid_category'
  // Partner company
  | 'invalid_latitude'
  | 'invalid_longitude'
  | 'coordinates_required'
  | 'coordinates_both_required'
  | 'company_name_required'
  | 'description_required'
  | 'address_required'
  | 'invalid_status_for_update'
  | 'location_confirmation_required'
  | 'application_not_approved'
  | 'partner_already_created'
  // Partner offers
  | 'offer_not_active'
  | 'offer_partner_not_active'
  | 'offer_description_required'
  | 'offer_teaser_required'
  | 'offer_activation_not_confirmed'
  | 'offer_invalid_status_for_update'
  | 'offer_invalid_status_transition'
  | 'offer_reason_required'
  | 'offer_invalid_percentage_discount'
  | 'offer_invalid_fixed_discount'
  | 'offer_currency_required'
  | 'offer_date_range_invalid'
  // Partner insights
  | 'interaction_type_unsupported'
  | 'interaction_partner_inactive'
  | 'interaction_offer_not_found'
  | 'interaction_offer_partner_mismatch'
  | 'insights_partner_not_found'
  // Digital billboards
  | 'billboard_headline_required'
  | 'billboard_message_required'
  | 'billboard_partner_not_found'
  | 'billboard_partner_not_active'
  | 'billboard_invalid_status_for_update'
  | 'billboard_invalid_status_transition'
  | 'billboard_reason_required'
  | 'billboard_safety_confirmation_required'
  | 'billboard_invalid_coordinates'
  | 'billboard_invalid_date_range'
  | 'billboard_invalid_cta'
  | 'billboard_feature_disabled'
  // Notifications
  | 'notification_not_found'
  | 'notification_invalid_category'
  | 'notification_invalid_audience'
  | 'notification_reason_required'
  | 'notification_confirmation_required'
  | 'notification_duplicate_idempotency_key'
  | 'notification_target_user_required'
  | 'notification_event_required'
  | 'notification_invalid_push_token'
  | 'notification_device_not_found'
  | 'notification_feature_disabled'
  | 'notification_essential_category_protected';

export class AppError extends Error {
  public readonly statusCode: number;
  public readonly code: ErrorCode;
  public readonly details?: unknown;

  constructor(statusCode: number, code: ErrorCode, message: string, details?: unknown) {
    super(message);
    this.name = 'AppError';
    this.statusCode = statusCode;
    this.code = code;
    this.details = details;
  }
}

export function fromUnknownError(error: unknown): AppError {
  if (error instanceof AppError) {
    return error;
  }

  if (error instanceof ZodError) {
    return new AppError(400, 'validation_error', 'Request validation failed.', {
      issues: error.issues.map((issue) => ({
        path: issue.path.join('.'),
        message: issue.message,
      })),
    });
  }

  return new AppError(500, 'internal_error', 'Internal server error.');
}
