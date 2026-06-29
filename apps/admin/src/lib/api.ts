/**
 * Admin portal API client.
 *
 * Connects to the backend API using a configurable base URL.
 * In development, a dev auth token can be injected via VITE_DEV_AUTH_TOKEN
 * to populate the x-dev-user header (non-production only).
 *
 * SECURITY NOTES:
 * - Never log auth tokens.
 * - Never embed credentials in source code.
 * - Backend role validation is always required — client-side auth is not a security boundary.
 */

const API_BASE_URL = (import.meta.env.VITE_API_URL as string | undefined) ?? 'http://localhost:4000';

/**
 * Dev-only: raw value of the x-dev-user header to send in non-production.
 * This must match the JSON format expected by the backend dev auth middleware.
 * Never set this in production.
 */
const DEV_AUTH_HEADER_VALUE: string | undefined =
  !import.meta.env.PROD
    ? (import.meta.env.VITE_DEV_AUTH_TOKEN as string | undefined)
    : undefined;

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

export interface ApiRequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** Firebase ID token for authenticating the request. */
  token?: string;
}

async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const { method = 'GET', body, token } = options;

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  };

  if (token) {
    headers['Authorization'] = 'Bearer ' + token;
  } else if (DEV_AUTH_HEADER_VALUE) {
    // Development-only: inject dev auth header when no real token is present.
    headers['x-dev-user'] = DEV_AUTH_HEADER_VALUE;
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (!response.ok) {
    interface ErrorPayload {
      ok: false;
      error: { code: string; message: string; details?: unknown };
    }
    let errorPayload: ErrorPayload | null = null;
    try {
      errorPayload = (await response.json()) as ErrorPayload;
    } catch {
      // response body may not be JSON
    }
    throw new ApiError(
      response.status,
      errorPayload?.error?.code ?? 'unknown_error',
      errorPayload?.error?.message ?? `HTTP ${response.status}`,
      errorPayload?.error?.details,
    );
  }

  return response.json() as Promise<T>;
}

export { apiRequest };
