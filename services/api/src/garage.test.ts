/**
 * Garage API route tests.
 *
 * All service calls use a fake service to avoid database dependencies.
 *
 * Covers:
 *  - Free user cannot access garage routes (403)
 *  - Unauthenticated request is rejected (401)
 *  - Suspended member cannot access garage (403)
 *  - Active member can list, create, get, update, delete vehicles
 *  - User cannot read another user's vehicle (service returns 404)
 *  - User cannot update another user's vehicle (service returns 404)
 *  - User cannot delete another user's vehicle (service returns 404)
 *  - Request body cannot set or change userId
 *  - Validation rejects invalid model year
 *  - Validation rejects unsupported powertrain
 *  - Length limits are enforced (make, model, engineDescription, description)
 *  - Unknown request fields are rejected (strict schema)
 *  - Vehicle-count limit returns 409
 *  - No registration number, VIN, location, or owner email is returned
 *  - Admin receives no private vehicle details
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  GARAGE_ROUTE_PATHS,
  type CreateVehicleResponse,
  type DeleteVehicleResponse,
  type PaginatedGarageResponse,
  type UpdateVehicleResponse,
  type VehicleDetail,
  type VehicleDetailResponse,
} from '@carcommunity/shared/garage';

import { LOCAL_DATABASE_URL } from './config.js';
import { AppError } from './lib/errors.js';
import type { GarageService, ListVehiclesResult } from './lib/garage-service.js';
import { createServer } from './server.js';

// ---------------------------------------------------------------------------
// Test UUIDs — all routes validate UUID format
// ---------------------------------------------------------------------------

const VEHICLE_UUID = 'a1b2c3d4-e5f6-4a7b-8c9d-000000000010';

// ---------------------------------------------------------------------------
// Sample data
// ---------------------------------------------------------------------------

const SAMPLE_VEHICLE: VehicleDetail = {
  id: VEHICLE_UUID,
  make: 'Volvo',
  model: 'V70',
  modelYear: 2010,
  powertrain: 'petrol',
  engineDescription: '2.0T',
  description: 'My daily driver',
  createdAt: '2026-06-23T10:00:00.000Z',
  updatedAt: '2026-06-23T10:00:00.000Z',
};

const SAMPLE_LIST_RESULT: ListVehiclesResult = {
  vehicles: [SAMPLE_VEHICLE],
  total: 1,
  hasNext: false,
};

// ---------------------------------------------------------------------------
// Fake service
// ---------------------------------------------------------------------------

class FakeGarageService {
  public vehicle: VehicleDetail = SAMPLE_VEHICLE;
  public listResult: ListVehiclesResult = SAMPLE_LIST_RESULT;
  public failWith: AppError | null = null;
  public lastCreateParams: Record<string, unknown> | null = null;

  async listVehicles(): Promise<ListVehiclesResult> {
    if (this.failWith) throw this.failWith;
    return this.listResult;
  }

  async getVehicle(): Promise<VehicleDetail> {
    if (this.failWith) throw this.failWith;
    return this.vehicle;
  }

  async createVehicle(params: Record<string, unknown>): Promise<VehicleDetail> {
    if (this.failWith) throw this.failWith;
    this.lastCreateParams = params;
    return this.vehicle;
  }

  async updateVehicle(): Promise<VehicleDetail> {
    if (this.failWith) throw this.failWith;
    return this.vehicle;
  }

  async deleteVehicle(): Promise<void> {
    if (this.failWith) throw this.failWith;
  }
}

// ---------------------------------------------------------------------------
// Auth helpers
// ---------------------------------------------------------------------------

function createDevAuthHeader(input: {
  userId: string;
  role: 'user' | 'admin' | 'owner';
  status: 'active' | 'warned' | 'temporarily_suspended' | 'permanently_suspended' | 'deleted';
  subscriptionEntitlement: 'none' | 'member_monthly';
}): string {
  return JSON.stringify({ ...input, sessionId: 'dev-session-id' });
}

const MEMBER_AUTH = createDevAuthHeader({
  userId: 'user-1',
  role: 'user',
  status: 'active',
  subscriptionEntitlement: 'member_monthly',
});

const FREE_AUTH = createDevAuthHeader({
  userId: 'user-2',
  role: 'user',
  status: 'active',
  subscriptionEntitlement: 'none',
});

const SUSPENDED_AUTH = createDevAuthHeader({
  userId: 'user-3',
  role: 'user',
  status: 'temporarily_suspended',
  subscriptionEntitlement: 'member_monthly',
});

const ADMIN_AUTH = createDevAuthHeader({
  userId: 'admin-1',
  role: 'admin',
  status: 'active',
  subscriptionEntitlement: 'none',
});

async function createTestApp(service?: FakeGarageService) {
  return createServer(
    {
      nodeEnv: 'test',
      port: 0,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
    },
    {
      garageService: service as unknown as GarageService,
    },
  );
}

// ---------------------------------------------------------------------------
// Tests: unauthenticated
// ---------------------------------------------------------------------------

test('unauthenticated request to list vehicles returns 401', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({ method: 'GET', url: GARAGE_ROUTE_PATHS.list });
  assert.equal(res.statusCode, 401);
  await app.close();
});

test('unauthenticated request to create vehicle returns 401', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'POST',
    url: GARAGE_ROUTE_PATHS.list,
    payload: { make: 'Volvo', model: 'V70', modelYear: 2010, powertrain: 'petrol' },
  });
  assert.equal(res.statusCode, 401);
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: free user cannot access garage
// ---------------------------------------------------------------------------

test('free user cannot list vehicles (403)', async () => {
  const svc = new FakeGarageService();
  svc.failWith = new AppError(403, 'forbidden', 'Member subscription required to access garage features.');
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': FREE_AUTH },
  });
  assert.equal(res.statusCode, 403);
  await app.close();
});

test('free user cannot create a vehicle (403)', async () => {
  const svc = new FakeGarageService();
  svc.failWith = new AppError(403, 'forbidden', 'Member subscription required to access garage features.');
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': FREE_AUTH, 'content-type': 'application/json' },
    payload: { make: 'Volvo', model: 'V70', modelYear: 2010, powertrain: 'petrol' },
  });
  assert.equal(res.statusCode, 403);
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: suspended member
// ---------------------------------------------------------------------------

test('suspended member cannot list vehicles (403 suspended)', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'GET',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': SUSPENDED_AUTH },
  });
  assert.equal(res.statusCode, 403);
  const body = res.json<{ ok: false; error: { code: string } }>();
  assert.equal(body.error.code, 'suspended');
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: active member can use garage
// ---------------------------------------------------------------------------

test('active member can list vehicles', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'GET',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': MEMBER_AUTH },
  });
  assert.equal(res.statusCode, 200);
  const body = res.json<PaginatedGarageResponse>();
  assert.equal(body.ok, true);
  assert.equal(body.data.vehicles.length, 1);
  assert.ok(body.data.vehicles[0] !== undefined);
  assert.equal(body.data.vehicles[0].make, 'Volvo');
  await app.close();
});

test('active member can get vehicle detail', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'GET',
    url: GARAGE_ROUTE_PATHS.detail(VEHICLE_UUID),
    headers: { 'x-dev-user': MEMBER_AUTH },
  });
  assert.equal(res.statusCode, 200);
  const body = res.json<VehicleDetailResponse>();
  assert.equal(body.data.vehicle.id, VEHICLE_UUID);
  await app.close();
});

test('active member can create a vehicle', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'POST',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': MEMBER_AUTH, 'content-type': 'application/json' },
    payload: { make: 'Volvo', model: 'V70', modelYear: 2010, powertrain: 'petrol' },
  });
  assert.equal(res.statusCode, 201);
  const body = res.json<CreateVehicleResponse>();
  assert.equal(body.ok, true);
  assert.equal(body.data.vehicle.make, 'Volvo');
  await app.close();
});

test('active member can update a vehicle', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'PATCH',
    url: GARAGE_ROUTE_PATHS.detail(VEHICLE_UUID),
    headers: { 'x-dev-user': MEMBER_AUTH, 'content-type': 'application/json' },
    payload: { make: 'Saab' },
  });
  assert.equal(res.statusCode, 200);
  const body = res.json<UpdateVehicleResponse>();
  assert.equal(body.ok, true);
  await app.close();
});

test('active member can delete a vehicle', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'DELETE',
    url: GARAGE_ROUTE_PATHS.detail(VEHICLE_UUID),
    headers: { 'x-dev-user': MEMBER_AUTH },
  });
  assert.equal(res.statusCode, 200);
  const body = res.json<DeleteVehicleResponse>();
  assert.equal(body.ok, true);
  assert.equal(body.data.deleted, true);
  assert.equal(body.data.vehicleId, VEHICLE_UUID);
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: ownership failures return 404 (not 403, to avoid leaking existence)
// ---------------------------------------------------------------------------

test('get another user\'s vehicle returns 404', async () => {
  const svc = new FakeGarageService();
  svc.failWith = new AppError(404, 'not_found', 'Vehicle not found.');
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'GET',
    url: GARAGE_ROUTE_PATHS.detail(VEHICLE_UUID),
    headers: { 'x-dev-user': MEMBER_AUTH },
  });
  assert.equal(res.statusCode, 404);
  await app.close();
});

test('update another user\'s vehicle returns 404', async () => {
  const svc = new FakeGarageService();
  svc.failWith = new AppError(404, 'not_found', 'Vehicle not found.');
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'PATCH',
    url: GARAGE_ROUTE_PATHS.detail(VEHICLE_UUID),
    headers: { 'x-dev-user': MEMBER_AUTH, 'content-type': 'application/json' },
    payload: { make: 'Ford' },
  });
  assert.equal(res.statusCode, 404);
  await app.close();
});

test('delete another user\'s vehicle returns 404', async () => {
  const svc = new FakeGarageService();
  svc.failWith = new AppError(404, 'not_found', 'Vehicle not found.');
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'DELETE',
    url: GARAGE_ROUTE_PATHS.detail(VEHICLE_UUID),
    headers: { 'x-dev-user': MEMBER_AUTH },
  });
  assert.equal(res.statusCode, 404);
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: userId cannot be set in request body
// ---------------------------------------------------------------------------

test('create request with userId in body is rejected (unknown field)', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'POST',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': MEMBER_AUTH, 'content-type': 'application/json' },
    payload: { make: 'Volvo', model: 'V70', modelYear: 2010, powertrain: 'petrol', userId: 'attacker-id' },
  });
  assert.equal(res.statusCode, 400, 'unknown field userId must be rejected');
  await app.close();
});

test('update request with userId in body is rejected (unknown field)', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'PATCH',
    url: GARAGE_ROUTE_PATHS.detail(VEHICLE_UUID),
    headers: { 'x-dev-user': MEMBER_AUTH, 'content-type': 'application/json' },
    payload: { userId: 'attacker-id' },
  });
  assert.equal(res.statusCode, 400, 'unknown field userId must be rejected');
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: validation
// ---------------------------------------------------------------------------

test('create with invalid model year (too low) returns 400', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'POST',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': MEMBER_AUTH, 'content-type': 'application/json' },
    payload: { make: 'Volvo', model: 'V70', modelYear: 1800, powertrain: 'petrol' },
  });
  assert.equal(res.statusCode, 400);
  await app.close();
});

test('create with invalid model year (too far future) returns 400', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'POST',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': MEMBER_AUTH, 'content-type': 'application/json' },
    payload: { make: 'Volvo', model: 'V70', modelYear: 9999, powertrain: 'petrol' },
  });
  assert.equal(res.statusCode, 400);
  await app.close();
});

test('create with unsupported powertrain returns 400', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'POST',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': MEMBER_AUTH, 'content-type': 'application/json' },
    payload: { make: 'Volvo', model: 'V70', modelYear: 2010, powertrain: 'steam' },
  });
  assert.equal(res.statusCode, 400);
  await app.close();
});

test('create with make exceeding 80 characters returns 400', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'POST',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': MEMBER_AUTH, 'content-type': 'application/json' },
    payload: { make: 'V'.repeat(81), model: 'V70', modelYear: 2010, powertrain: 'petrol' },
  });
  assert.equal(res.statusCode, 400);
  await app.close();
});

test('create with model exceeding 80 characters returns 400', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'POST',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': MEMBER_AUTH, 'content-type': 'application/json' },
    payload: { make: 'Volvo', model: 'V'.repeat(81), modelYear: 2010, powertrain: 'petrol' },
  });
  assert.equal(res.statusCode, 400);
  await app.close();
});

test('create with engineDescription exceeding 120 characters returns 400', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'POST',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': MEMBER_AUTH, 'content-type': 'application/json' },
    payload: { make: 'Volvo', model: 'V70', modelYear: 2010, powertrain: 'petrol', engineDescription: 'X'.repeat(121) },
  });
  assert.equal(res.statusCode, 400);
  await app.close();
});

test('create with description exceeding 500 characters returns 400', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'POST',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': MEMBER_AUTH, 'content-type': 'application/json' },
    payload: { make: 'Volvo', model: 'V70', modelYear: 2010, powertrain: 'petrol', description: 'X'.repeat(501) },
  });
  assert.equal(res.statusCode, 400);
  await app.close();
});

test('create with unknown field returns 400 (strict schema)', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'POST',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': MEMBER_AUTH, 'content-type': 'application/json' },
    payload: { make: 'Volvo', model: 'V70', modelYear: 2010, powertrain: 'petrol', vin: 'ABC123' },
  });
  assert.equal(res.statusCode, 400);
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: vehicle count limit
// ---------------------------------------------------------------------------

test('vehicle count limit exceeded returns 409', async () => {
  const svc = new FakeGarageService();
  svc.failWith = new AppError(409, 'conflict', 'Vehicle limit reached.');
  const app = await createTestApp(svc);
  const res = await app.inject({
    method: 'POST',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': MEMBER_AUTH, 'content-type': 'application/json' },
    payload: { make: 'Volvo', model: 'V70', modelYear: 2010, powertrain: 'petrol' },
  });
  assert.equal(res.statusCode, 409);
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: response shape — sensitive fields must not be present
// ---------------------------------------------------------------------------

test('vehicle response does not include registrationNumber, VIN, location, or owner email', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'GET',
    url: GARAGE_ROUTE_PATHS.detail(VEHICLE_UUID),
    headers: { 'x-dev-user': MEMBER_AUTH },
  });
  assert.equal(res.statusCode, 200);
  const body = res.json<VehicleDetailResponse>();
  const v = body.data.vehicle;
  assert.ok(!('registrationNumber' in v), 'must not include registrationNumber');
  assert.ok(!('vin' in v), 'must not include vin');
  assert.ok(!('latitude' in v), 'must not include latitude');
  assert.ok(!('longitude' in v), 'must not include longitude');
  assert.ok(!('ownerEmail' in v), 'must not include ownerEmail');
  assert.ok(!('userId' in v), 'must not include userId');
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: admin does not receive private vehicle details
// ---------------------------------------------------------------------------

test('admin user cannot access garage routes (not a member)', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'GET',
    url: GARAGE_ROUTE_PATHS.list,
    headers: { 'x-dev-user': ADMIN_AUTH },
  });
  // Admin does not have member_monthly — requireMemberHook blocks them
  assert.equal(res.statusCode, 403);
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: vehicleId param must be a valid UUID
// ---------------------------------------------------------------------------

test('get vehicle with non-UUID id returns 400', async () => {
  const app = await createTestApp(new FakeGarageService());
  const res = await app.inject({
    method: 'GET',
    url: GARAGE_ROUTE_PATHS.detail('not-a-uuid'),
    headers: { 'x-dev-user': MEMBER_AUTH },
  });
  assert.equal(res.statusCode, 400);
  await app.close();
});
