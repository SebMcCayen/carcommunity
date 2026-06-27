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
  | 'partner_already_created';

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
