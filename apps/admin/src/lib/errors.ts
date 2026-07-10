/**
 * Shared error type for the admin portal.
 *
 * `ApiError` is the normalized error shape every feature module and page
 * handles. Callable Cloud Function failures are mapped into it by
 * `callAdmin` in ./callables.ts (Firebase error codes → HTTP-ish status
 * codes), and feature modules throw it directly for client-side
 * validation failures.
 */

export class ApiError extends Error {
  public readonly statusCode: number;
  public readonly code: string;
  public readonly details?: unknown;

  constructor(statusCode: number, code: string, message: string, details?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.statusCode = statusCode;
    this.code = code;
    this.details = details;
  }
}
