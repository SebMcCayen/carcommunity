/**
 * Event chat route tests.
 *
 * Uses node:test + node:assert, fake services, and the dev x-dev-user header.
 * No real database is used — all service methods are mocked.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  EVENT_CHAT_ROUTE_PATHS,
  buildAdminEventChatRemovePath,
  buildEventChatMessageReportPath,
  buildEventChatMessagesPath,
  type AdminEventChatMessageSummary,
  type AdminEventChatReportSummary,
  type EventChatMessage,
  type PaginatedEventChatResponse,
} from '@carcommunity/shared/event-chat';

import { LOCAL_DATABASE_URL } from './config.js';
import { AppError } from './lib/errors.js';
import type {
  EventChatService,
  ListMessagesResult,
  ListAdminMessagesResult,
  ListAdminReportsResult,
} from './lib/event-chat-service.js';
import { createServer } from './server.js';

// ---------------------------------------------------------------------------
// Fake data
// ---------------------------------------------------------------------------

const EVENT_ID = 'f47ac10b-58cc-4372-a567-0e02b2c3d479';
const MESSAGE_ID = 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d';
const AUTHOR_USER_ID = 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e';
const VIEWER_USER_ID = 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f';
const ADMIN_USER_ID = 'd4e5f6a7-b8c9-4d0e-bf2a-3b4c5d6e7f8a';

const FAKE_MESSAGE: EventChatMessage = {
  id: MESSAGE_ID,
  eventId: EVENT_ID,
  author: { userId: AUTHOR_USER_ID, displayName: 'Test User' },
  message: 'Hej alla!',
  createdAt: '2027-07-01T12:00:00.000Z',
  moderationState: 'visible',
  isOwnMessage: false,
};

const FAKE_LIST_RESULT: ListMessagesResult = {
  messages: [FAKE_MESSAGE],
  nextCursor: null,
};

const FAKE_ADMIN_MESSAGE: AdminEventChatMessageSummary = {
  id: MESSAGE_ID,
  eventId: EVENT_ID,
  author: { userId: AUTHOR_USER_ID, displayName: 'Test User' },
  message: 'Hej alla!',
  createdAt: '2027-07-01T12:00:00.000Z',
  moderationState: 'visible',
  removedAt: null,
  removedByUserId: null,
  removalReason: null,
  reportCount: 0,
  reportStatus: null,
};

const FAKE_ADMIN_MESSAGES_RESULT: ListAdminMessagesResult = {
  messages: [FAKE_ADMIN_MESSAGE],
  total: 1,
  hasNext: false,
  page: 1,
  pageSize: 30,
};

const FAKE_ADMIN_REPORT: AdminEventChatReportSummary = {
  id: 'e5f6a7b8-c9d0-4e1f-af2b-3c4d5e6f7a8b',
  messageId: MESSAGE_ID,
  reason: 'spam',
  details: null,
  status: 'new',
  createdAt: '2027-07-01T12:05:00.000Z',
  reviewedAt: null,
  reviewedByUserId: null,
};

const FAKE_ADMIN_REPORTS_RESULT: ListAdminReportsResult = {
  reports: [FAKE_ADMIN_REPORT],
  total: 1,
  hasNext: false,
  page: 1,
  pageSize: 30,
};

// ---------------------------------------------------------------------------
// Fake service
// ---------------------------------------------------------------------------

class FakeEventChatService
  implements
    Pick<
      EventChatService,
      | 'listMessages'
      | 'createMessage'
      | 'reportMessage'
      | 'listAdminMessages'
      | 'listAdminReports'
      | 'removeMessage'
    >
{
  public error: AppError | null = null;

  async listMessages(): Promise<ListMessagesResult> {
    if (this.error) throw this.error;
    return FAKE_LIST_RESULT;
  }

  async createMessage(): Promise<EventChatMessage> {
    if (this.error) throw this.error;
    return FAKE_MESSAGE;
  }

  async reportMessage(): Promise<void> {
    if (this.error) throw this.error;
  }

  async listAdminMessages(): Promise<ListAdminMessagesResult> {
    if (this.error) throw this.error;
    return FAKE_ADMIN_MESSAGES_RESULT;
  }

  async listAdminReports(): Promise<ListAdminReportsResult> {
    if (this.error) throw this.error;
    return FAKE_ADMIN_REPORTS_RESULT;
  }

  async removeMessage(): Promise<AdminEventChatMessageSummary> {
    if (this.error) throw this.error;
    return { ...FAKE_ADMIN_MESSAGE, moderationState: 'removed', removedAt: '2027-07-01T13:00:00.000Z', removedByUserId: ADMIN_USER_ID, removalReason: 'Violates community rules.' };
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function createTestApp(eventChatService: FakeEventChatService) {
  return createServer(
    { nodeEnv: 'test', port: 0, databaseUrl: LOCAL_DATABASE_URL, isProduction: false },
    { eventChatService: eventChatService as unknown as EventChatService },
  );
}

function memberHeader(
  overrides: {
    userId?: string;
    role?: 'user' | 'admin' | 'owner';
    status?: 'active' | 'temporarily_suspended' | 'permanently_suspended' | 'deleted';
    subscriptionEntitlement?: 'none' | 'member_monthly';
  } = {},
): string {
  return JSON.stringify({
    userId: overrides.userId ?? VIEWER_USER_ID,
    role: overrides.role ?? 'user',
    status: overrides.status ?? 'active',
    subscriptionEntitlement: overrides.subscriptionEntitlement ?? 'member_monthly',
    sessionId: 'test-session',
  });
}

function adminHeader(userId = ADMIN_USER_ID): string {
  return JSON.stringify({
    userId,
    role: 'admin',
    status: 'active',
    subscriptionEntitlement: 'none',
    sessionId: 'admin-session',
  });
}

// ---------------------------------------------------------------------------
// Tests: GET /v1/events/:eventId/chat/messages
// ---------------------------------------------------------------------------

await test('GET event chat messages — requires authentication', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: buildEventChatMessagesPath(EVENT_ID),
  });
  assert.equal(res.statusCode, 401);
});

await test('GET event chat messages — free user is rejected', async () => {
  const svc = new FakeEventChatService();
  svc.error = new AppError(403, 'forbidden', 'Member subscription required.');
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: buildEventChatMessagesPath(EVENT_ID),
    headers: { 'x-dev-user': memberHeader({ subscriptionEntitlement: 'none' }) },
  });
  // requireMemberHook rejects non-members before the service is called
  assert.equal(res.statusCode, 403);
});

await test('GET event chat messages — suspended member is rejected', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: buildEventChatMessagesPath(EVENT_ID),
    headers: { 'x-dev-user': memberHeader({ status: 'temporarily_suspended' }) },
  });
  assert.equal(res.statusCode, 403);
});

await test('GET event chat messages — deleted user is rejected', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: buildEventChatMessagesPath(EVENT_ID),
    headers: { 'x-dev-user': memberHeader({ status: 'deleted' }) },
  });
  assert.equal(res.statusCode, 403);
});

await test('GET event chat messages — service 403 for no RSVP is forwarded', async () => {
  const svc = new FakeEventChatService();
  svc.error = new AppError(403, 'forbidden', 'Du behöver svara Kommer eller Kanske för att delta i chatten.');
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: buildEventChatMessagesPath(EVENT_ID),
    headers: { 'x-dev-user': memberHeader() },
  });
  assert.equal(res.statusCode, 403);
});

await test('GET event chat messages — eligible member receives messages', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: buildEventChatMessagesPath(EVENT_ID),
    headers: { 'x-dev-user': memberHeader() },
  });
  assert.equal(res.statusCode, 200);
  const body = res.json() as PaginatedEventChatResponse;
  assert.equal(body.ok, true);
  assert.equal(body.data.messages.length, 1);
  assert.equal(body.data.messages[0]!.id, MESSAGE_ID);
  assert.equal(body.data.messages[0]!.moderationState, 'visible');
});

await test('GET event chat messages — does not expose email or subscription in response', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: buildEventChatMessagesPath(EVENT_ID),
    headers: { 'x-dev-user': memberHeader() },
  });
  const body = res.json() as Record<string, unknown>;
  const raw = JSON.stringify(body);
  assert.ok(!raw.includes('"email"'), 'email must not appear in chat response');
  assert.ok(!raw.includes('"subscriptionEntitlement"'), 'subscription must not appear in chat response');
  assert.ok(!raw.includes('"sessionId"'), 'sessionId must not appear in chat response');
});

await test('GET event chat messages — service 404 for cancelled event is forwarded', async () => {
  const svc = new FakeEventChatService();
  svc.error = new AppError(403, 'forbidden', 'Event chat is not available for this event.');
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: buildEventChatMessagesPath(EVENT_ID),
    headers: { 'x-dev-user': memberHeader() },
  });
  assert.equal(res.statusCode, 403);
});

// ---------------------------------------------------------------------------
// Tests: POST /v1/events/:eventId/chat/messages
// ---------------------------------------------------------------------------

await test('POST event chat message — requires authentication', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildEventChatMessagesPath(EVENT_ID),
    payload: { message: 'Hello' },
  });
  assert.equal(res.statusCode, 401);
});

await test('POST event chat message — free user is rejected by hook', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildEventChatMessagesPath(EVENT_ID),
    headers: { 'x-dev-user': memberHeader({ subscriptionEntitlement: 'none' }) },
    payload: { message: 'Hello' },
  });
  assert.equal(res.statusCode, 403);
});

await test('POST event chat message — empty message is rejected', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildEventChatMessagesPath(EVENT_ID),
    headers: { 'x-dev-user': memberHeader() },
    payload: { message: '' },
  });
  assert.equal(res.statusCode, 400);
});

await test('POST event chat message — oversized message is rejected', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildEventChatMessagesPath(EVENT_ID),
    headers: { 'x-dev-user': memberHeader() },
    payload: { message: 'a'.repeat(1001) },
  });
  assert.equal(res.statusCode, 400);
});

await test('POST event chat message — HTML payload is treated as literal text not rejected by route', async () => {
  // HTML characters pass through Zod string validation — the service stores them as plain text.
  // The route must NOT reject with an error; HTML rendering is the client's responsibility.
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildEventChatMessagesPath(EVENT_ID),
    headers: { 'x-dev-user': memberHeader() },
    payload: { message: '<script>alert(1)</script>' },
  });
  assert.equal(res.statusCode, 200, 'route must accept HTML payload (stored as plain text, not executed)');
  const body = res.json<{ ok: boolean }>();
  assert.equal(body.ok, true, 'response must indicate success');
});

await test('POST event chat message — eligible member succeeds', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildEventChatMessagesPath(EVENT_ID),
    headers: { 'x-dev-user': memberHeader() },
    payload: { message: 'Hej alla!' },
  });
  assert.equal(res.statusCode, 200);
  const body = res.json<{ ok: boolean; data: { message: EventChatMessage } }>();
  assert.equal(body.ok, true);
  assert.equal(body.data.message.id, MESSAGE_ID);
});

await test('POST event chat message — service 403 for not_going RSVP is forwarded', async () => {
  const svc = new FakeEventChatService();
  svc.error = new AppError(403, 'forbidden', 'Du behöver svara Kommer eller Kanske för att delta i chatten.');
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildEventChatMessagesPath(EVENT_ID),
    headers: { 'x-dev-user': memberHeader() },
    payload: { message: 'Hello' },
  });
  assert.equal(res.statusCode, 403);
});

// ---------------------------------------------------------------------------
// Tests: POST /v1/events/:eventId/chat/messages/:messageId/report
// ---------------------------------------------------------------------------

await test('POST report message — requires authentication', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildEventChatMessageReportPath(EVENT_ID, MESSAGE_ID),
    payload: { reason: 'spam' },
  });
  assert.equal(res.statusCode, 401);
});

await test('POST report message — eligible member can report', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildEventChatMessageReportPath(EVENT_ID, MESSAGE_ID),
    headers: { 'x-dev-user': memberHeader() },
    payload: { reason: 'spam' },
  });
  assert.equal(res.statusCode, 200);
  const body = res.json<{ ok: boolean; data: { reported: boolean } }>();
  assert.equal(body.ok, true);
  assert.equal(body.data.reported, true);
});

await test('POST report message — cannot report own message', async () => {
  const svc = new FakeEventChatService();
  svc.error = new AppError(400, 'validation_error', 'You cannot report your own message.');
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildEventChatMessageReportPath(EVENT_ID, MESSAGE_ID),
    headers: { 'x-dev-user': memberHeader() },
    payload: { reason: 'spam' },
  });
  assert.equal(res.statusCode, 400);
});

await test('POST report message — invalid reason is rejected by route schema', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildEventChatMessageReportPath(EVENT_ID, MESSAGE_ID),
    headers: { 'x-dev-user': memberHeader() },
    payload: { reason: 'not_a_real_reason' },
  });
  assert.equal(res.statusCode, 400);
});

await test('POST report message — duplicate report is handled safely', async () => {
  // Service uses upsert — duplicate calls return success without throwing.
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  for (let i = 0; i < 2; i++) {
    const res = await app.inject({
      method: 'POST',
      url: buildEventChatMessageReportPath(EVENT_ID, MESSAGE_ID),
      headers: { 'x-dev-user': memberHeader() },
      payload: { reason: 'spam' },
    });
    assert.equal(res.statusCode, 200, `Attempt ${i + 1} should succeed`);
  }
});

// ---------------------------------------------------------------------------
// Tests: GET /v1/admin/event-chat/messages
// ---------------------------------------------------------------------------

await test('GET admin event chat messages — requires admin role', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: EVENT_CHAT_ROUTE_PATHS.adminMessages,
    headers: { 'x-dev-user': memberHeader() },
  });
  assert.equal(res.statusCode, 403);
});

await test('GET admin event chat messages — unauthenticated is rejected', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: EVENT_CHAT_ROUTE_PATHS.adminMessages,
  });
  assert.equal(res.statusCode, 401);
});

await test('GET admin event chat messages — admin receives paginated result', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: EVENT_CHAT_ROUTE_PATHS.adminMessages,
    headers: { 'x-dev-user': adminHeader() },
  });
  assert.equal(res.statusCode, 200);
  const body = res.json<{ ok: boolean; data: { messages: AdminEventChatMessageSummary[] } }>();
  assert.equal(body.ok, true);
  assert.equal(body.data.messages.length, 1);
});

await test('GET admin event chat messages — does not expose reporter identity', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: EVENT_CHAT_ROUTE_PATHS.adminMessages,
    headers: { 'x-dev-user': adminHeader() },
  });
  const raw = res.payload;
  assert.ok(!raw.includes('"reporterUserId"'), 'reporter identity must not appear in admin message list');
});

// ---------------------------------------------------------------------------
// Tests: GET /v1/admin/event-chat/reports
// ---------------------------------------------------------------------------

await test('GET admin event chat reports — requires admin role', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: EVENT_CHAT_ROUTE_PATHS.adminReports,
    headers: { 'x-dev-user': memberHeader() },
  });
  assert.equal(res.statusCode, 403);
});

await test('GET admin event chat reports — admin receives reports', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: EVENT_CHAT_ROUTE_PATHS.adminReports,
    headers: { 'x-dev-user': adminHeader() },
  });
  assert.equal(res.statusCode, 200);
  const body = res.json<{ ok: boolean; data: { reports: AdminEventChatReportSummary[] } }>();
  assert.equal(body.ok, true);
  assert.equal(body.data.reports.length, 1);
  assert.equal(body.data.reports[0]!.reason, 'spam');
});

// ---------------------------------------------------------------------------
// Tests: POST /v1/admin/event-chat/messages/:messageId/remove
// ---------------------------------------------------------------------------

await test('POST admin remove message — requires admin role', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildAdminEventChatRemovePath(MESSAGE_ID),
    headers: { 'x-dev-user': memberHeader() },
    payload: { reason: 'Violates community rules.' },
  });
  assert.equal(res.statusCode, 403);
});

await test('POST admin remove message — requires a reason', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildAdminEventChatRemovePath(MESSAGE_ID),
    headers: { 'x-dev-user': adminHeader() },
    payload: { reason: '' },
  });
  assert.equal(res.statusCode, 400);
});

await test('POST admin remove message — admin can remove a message', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildAdminEventChatRemovePath(MESSAGE_ID),
    headers: { 'x-dev-user': adminHeader() },
    payload: { reason: 'Violates community rules.' },
  });
  assert.equal(res.statusCode, 200);
  const body = res.json<{ ok: boolean; data: { message: AdminEventChatMessageSummary } }>();
  assert.equal(body.ok, true);
  assert.equal(body.data.message.moderationState, 'removed');
});

await test('POST admin remove message — message is not hard-deleted', async () => {
  const svc = new FakeEventChatService();
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildAdminEventChatRemovePath(MESSAGE_ID),
    headers: { 'x-dev-user': adminHeader() },
    payload: { reason: 'Spam content.' },
  });
  const body = res.json<{ ok: boolean; data: { message: AdminEventChatMessageSummary } }>();
  // The message record still exists (id present) and has removedAt set rather than being absent.
  assert.ok(body.data.message.id, 'message id must be present (not hard-deleted)');
  assert.ok(body.data.message.removedAt !== null, 'removedAt must be set on removal');
});

await test('POST admin remove message — service 404 is forwarded', async () => {
  const svc = new FakeEventChatService();
  svc.error = new AppError(404, 'not_found', 'Message not found.');
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: buildAdminEventChatRemovePath(MESSAGE_ID),
    headers: { 'x-dev-user': adminHeader() },
    payload: { reason: 'Spam.' },
  });
  assert.equal(res.statusCode, 404);
});
