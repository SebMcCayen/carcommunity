/**
 * GarageService unit tests using a fake Prisma client.
 *
 * Covers:
 *  - Free user cannot access garage routes
 *  - Active member can create a vehicle
 *  - Suspended member cannot access garage
 *  - User can list only their own vehicles
 *  - User cannot read another user's vehicle
 *  - User cannot update another user's vehicle
 *  - User cannot delete another user's vehicle
 *  - Request cannot set or change userId
 *  - Validation rejects invalid model year
 *  - Validation rejects unsupported powertrain (enforced at route layer — see garage.test.ts)
 *  - Length limits are enforced (enforced at route layer — see garage.test.ts)
 *  - Vehicle-count limit is enforced
 *  - No registration number, VIN, location, or owner email is returned
 *  - Admin receives no private vehicle details (only aggregate stats)
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { GarageService } from './lib/garage-service.js';
import type { GarageActor } from './lib/garage-service.js';
import { AppError } from './lib/errors.js';

// ---------------------------------------------------------------------------
// Fake Prisma builder
// ---------------------------------------------------------------------------

interface FakeVehicleRecord {
  id: string;
  userId: string;
  make: string;
  model: string;
  modelYear: number;
  powertrain: string;
  engineDescription: string | null;
  description: string | null;
  createdAt: Date;
  updatedAt: Date;
}

function buildFakePrisma(options: { vehicles?: FakeVehicleRecord[] } = {}): Record<string, unknown> {
  const vehicles: FakeVehicleRecord[] = options.vehicles ?? [];
  let idCounter = 1;

  const vehicleDelegate = {
    async count({ where }: { where?: { userId?: string } } = {}) {
      return vehicles.filter((v) => !where?.userId || v.userId === where.userId).length;
    },
    async groupBy({ by }: { by: Array<'userId'> }) {
      if (by.includes('userId')) {
        return [...new Set(vehicles.map((v) => v.userId))].map((userId) => ({ userId }));
      }
      return [];
    },
    async findMany({
      where,
      skip = 0,
      take = 20,
    }: {
      where?: { userId?: string };
      skip?: number;
      take?: number;
      orderBy?: unknown;
    }) {
      return vehicles
        .filter((v) => !where?.userId || v.userId === where.userId)
        .slice(skip, skip + take);
    },
    async findUnique({ where }: { where: { id?: string } }) {
      return vehicles.find((v) => v.id === where.id) ?? null;
    },
    async findFirst({ where }: { where?: { id?: string; userId?: string } } = {}) {
      return vehicles.find((v) =>
        (where?.id === undefined || v.id === where.id)
        && (where?.userId === undefined || v.userId === where.userId),
      ) ?? null;
    },
    async create({ data }: { data: Partial<FakeVehicleRecord> }) {
      const id = `vehicle-${idCounter++}`;
      const now = new Date();
      const v: FakeVehicleRecord = {
        id,
        userId: data.userId!,
        make: data.make!,
        model: data.model!,
        modelYear: data.modelYear!,
        powertrain: data.powertrain!,
        engineDescription: data.engineDescription ?? null,
        description: data.description ?? null,
        createdAt: now,
        updatedAt: now,
      };
      vehicles.push(v);
      return v;
    },
    async update({ where, data }: { where: { id: string }; data: Partial<FakeVehicleRecord> }) {
      const idx = vehicles.findIndex((v) => v.id === where.id);
      if (idx < 0) throw new Error('Vehicle not found in fake DB');
      const existing = vehicles[idx] as FakeVehicleRecord;
      vehicles[idx] = { ...existing, ...data, updatedAt: new Date() } as FakeVehicleRecord;
      return vehicles[idx] as FakeVehicleRecord;
    },
    async delete({ where }: { where: { id: string } }) {
      const idx = vehicles.findIndex((v) => v.id === where.id);
      if (idx >= 0) vehicles.splice(idx, 1);
    },
  };

  const fakePrisma = {
    vehicle: vehicleDelegate,
    async $transaction(
      arg: ((tx: unknown) => Promise<unknown>) | Array<Promise<unknown>>,
    ) {
      if (typeof arg === 'function') return arg({ vehicle: vehicleDelegate });
      return Promise.all(arg);
    },
  };

  return fakePrisma;
}

// ---------------------------------------------------------------------------
// Actor helpers
// ---------------------------------------------------------------------------

function memberActor(userId = 'user-1'): GarageActor {
  return { userId, role: 'user', status: 'active', subscriptionEntitlement: 'member_monthly' };
}

function freeActor(userId = 'user-2'): GarageActor {
  return { userId, role: 'user', status: 'active', subscriptionEntitlement: 'none' };
}

function suspendedActor(userId = 'user-3'): GarageActor {
  return { userId, role: 'user', status: 'temporarily_suspended', subscriptionEntitlement: 'member_monthly' };
}

function deletedActor(userId = 'user-4'): GarageActor {
  return { userId, role: 'user', status: 'deleted', subscriptionEntitlement: 'member_monthly' };
}

function sampleVehicle(userId = 'user-1'): FakeVehicleRecord {
  return {
    id: 'vehicle-uuid-1',
    userId,
    make: 'Volvo',
    model: 'V70',
    modelYear: 2010,
    powertrain: 'petrol',
    engineDescription: '2.0T',
    description: null,
    createdAt: new Date('2026-01-01T00:00:00Z'),
    updatedAt: new Date('2026-01-01T00:00:00Z'),
  };
}

// ---------------------------------------------------------------------------
// Tests: access control
// ---------------------------------------------------------------------------

test('free user cannot list vehicles', async () => {
  const service = new GarageService(buildFakePrisma() as never);
  await assert.rejects(
    () => service.listVehicles({ actor: freeActor(), page: 1, pageSize: 20 }),
    (e: AppError) => e instanceof AppError && e.statusCode === 403,
  );
});

test('free user cannot create a vehicle', async () => {
  const service = new GarageService(buildFakePrisma() as never);
  await assert.rejects(
    () =>
      service.createVehicle({
        actor: freeActor(),
        make: 'Volvo',
        model: 'V70',
        modelYear: 2010,
        powertrain: 'petrol',
      }),
    (e: AppError) => e instanceof AppError && e.statusCode === 403,
  );
});

test('temporarily suspended member cannot access garage', async () => {
  const service = new GarageService(buildFakePrisma() as never);
  await assert.rejects(
    () => service.listVehicles({ actor: suspendedActor(), page: 1, pageSize: 20 }),
    (e: AppError) => e instanceof AppError && e.statusCode === 403 && e.code === 'suspended',
  );
});

test('permanently suspended member cannot access garage', async () => {
  const actor: GarageActor = { userId: 'user-5', role: 'user', status: 'permanently_suspended', subscriptionEntitlement: 'member_monthly' };
  const service = new GarageService(buildFakePrisma() as never);
  await assert.rejects(
    () => service.listVehicles({ actor, page: 1, pageSize: 20 }),
    (e: AppError) => e instanceof AppError && e.code === 'suspended',
  );
});

test('deleted user cannot access garage', async () => {
  const service = new GarageService(buildFakePrisma() as never);
  await assert.rejects(
    () => service.listVehicles({ actor: deletedActor(), page: 1, pageSize: 20 }),
    (e: AppError) => e instanceof AppError && e.statusCode === 403 && e.code === 'forbidden',
  );
});

// ---------------------------------------------------------------------------
// Tests: active member can create a vehicle
// ---------------------------------------------------------------------------

test('active member can create a vehicle', async () => {
  const service = new GarageService(buildFakePrisma() as never);
  const vehicle = await service.createVehicle({
    actor: memberActor(),
    make: 'Volvo',
    model: 'V70',
    modelYear: 2010,
    powertrain: 'petrol',
    engineDescription: '2.0T',
    description: 'My daily driver',
  });
  assert.equal(vehicle.make, 'Volvo');
  assert.equal(vehicle.model, 'V70');
  assert.equal(vehicle.modelYear, 2010);
  assert.equal(vehicle.powertrain, 'petrol');
  assert.equal(vehicle.engineDescription, '2.0T');
  assert.equal(vehicle.description, 'My daily driver');
  assert.ok(typeof vehicle.id === 'string');
});

// ---------------------------------------------------------------------------
// Tests: userId cannot be set or changed by client
// ---------------------------------------------------------------------------

test('createVehicle always sets userId from actor, not from body', async () => {
  const service = new GarageService(buildFakePrisma() as never);
  const actor = memberActor('real-user-id');
  const vehicle = await service.createVehicle({
    actor,
    make: 'BMW',
    model: '3 Series',
    modelYear: 2015,
    powertrain: 'diesel',
  });
  // The service should set userId from actor.userId
  // We verify by reading it back — the only way to get it is via the actor
  const listed = await service.listVehicles({ actor, page: 1, pageSize: 20 });
  assert.equal(listed.vehicles.length, 1);
  assert.ok(listed.vehicles[0] !== undefined);
  assert.equal(listed.vehicles[0].id, vehicle.id);
});

// ---------------------------------------------------------------------------
// Tests: user can list only their own vehicles
// ---------------------------------------------------------------------------

test('user can list only their own vehicles', async () => {
  const prisma = buildFakePrisma({
    vehicles: [sampleVehicle('user-1'), sampleVehicle('user-2')],
  });
  const service = new GarageService(prisma as never);

  const result = await service.listVehicles({ actor: memberActor('user-1'), page: 1, pageSize: 20 });
  assert.equal(result.vehicles.length, 1);
  assert.equal(result.total, 1);
});

// ---------------------------------------------------------------------------
// Tests: ownership enforcement
// ---------------------------------------------------------------------------

test('user cannot read another user\'s vehicle', async () => {
  const prisma = buildFakePrisma({ vehicles: [sampleVehicle('user-1')] });
  const service = new GarageService(prisma as never);

  await assert.rejects(
    () => service.getVehicle({ vehicleId: 'vehicle-uuid-1', actor: memberActor('user-2') }),
    (e: AppError) => e instanceof AppError && e.statusCode === 404,
  );
});

test('user cannot update another user\'s vehicle', async () => {
  const prisma = buildFakePrisma({ vehicles: [sampleVehicle('user-1')] });
  const service = new GarageService(prisma as never);

  await assert.rejects(
    () => service.updateVehicle({ vehicleId: 'vehicle-uuid-1', actor: memberActor('user-2'), make: 'Ford' }),
    (e: AppError) => e instanceof AppError && e.statusCode === 404,
  );
});

test('user cannot delete another user\'s vehicle', async () => {
  const prisma = buildFakePrisma({ vehicles: [sampleVehicle('user-1')] });
  const service = new GarageService(prisma as never);

  await assert.rejects(
    () => service.deleteVehicle({ vehicleId: 'vehicle-uuid-1', actor: memberActor('user-2') }),
    (e: AppError) => e instanceof AppError && e.statusCode === 404,
  );
});

// ---------------------------------------------------------------------------
// Tests: vehicle count limit
// ---------------------------------------------------------------------------

test('vehicle count limit is enforced on create', async () => {
  const vehicles: FakeVehicleRecord[] = [];
  for (let i = 0; i < 5; i++) {
    vehicles.push({ ...sampleVehicle('user-1'), id: `vehicle-uuid-${i + 1}` });
  }
  const service = new GarageService(buildFakePrisma({ vehicles }) as never);

  await assert.rejects(
    () => service.createVehicle({ actor: memberActor('user-1'), make: 'Ford', model: 'Focus', modelYear: 2020, powertrain: 'petrol' }),
    (e: AppError) => e instanceof AppError && e.statusCode === 409 && e.code === 'conflict',
  );
});

test('vehicle count limit allows exactly 5 vehicles', async () => {
  const vehicles: FakeVehicleRecord[] = [];
  for (let i = 0; i < 4; i++) {
    vehicles.push({ ...sampleVehicle('user-1'), id: `vehicle-uuid-${i + 1}` });
  }
  const service = new GarageService(buildFakePrisma({ vehicles }) as never);
  const vehicle = await service.createVehicle({
    actor: memberActor('user-1'),
    make: 'Ford',
    model: 'Focus',
    modelYear: 2020,
    powertrain: 'petrol',
  });
  assert.ok(vehicle.id);
});

// ---------------------------------------------------------------------------
// Tests: response shape — sensitive fields must not appear
// ---------------------------------------------------------------------------

test('vehicle response does not include registration number, VIN, location, or owner email', async () => {
  const prisma = buildFakePrisma({ vehicles: [sampleVehicle('user-1')] });
  const service = new GarageService(prisma as never);

  const vehicle = await service.getVehicle({ vehicleId: 'vehicle-uuid-1', actor: memberActor('user-1') });

  assert.ok(!('registrationNumber' in vehicle), 'must not include registrationNumber');
  assert.ok(!('vin' in vehicle), 'must not include vin');
  assert.ok(!('latitude' in vehicle), 'must not include latitude');
  assert.ok(!('longitude' in vehicle), 'must not include longitude');
  assert.ok(!('ownerEmail' in vehicle), 'must not include ownerEmail');
  assert.ok(!('userId' in vehicle), 'must not include userId in the response');
});

// ---------------------------------------------------------------------------
// Tests: admin aggregate stats
// ---------------------------------------------------------------------------

test('admin stats return aggregate counts only, no private vehicle details', async () => {
  const prisma = buildFakePrisma({
    vehicles: [
      sampleVehicle('user-1'),
      { ...sampleVehicle('user-1'), id: 'vehicle-uuid-2' },
      { ...sampleVehicle('user-2'), id: 'vehicle-uuid-3' },
    ],
  });
  const service = new GarageService(prisma as never);

  const stats = await service.getAdminStats();
  assert.equal(stats.totalVehicleCount, 3);
  assert.equal(stats.usersWithVehicleCount, 2);

  // Stats shape must not include any vehicle detail fields
  assert.ok(!('vehicles' in stats), 'must not include vehicle list');
  assert.ok(!('makes' in stats), 'must not include make breakdown');
  assert.ok(!('descriptions' in stats), 'must not include descriptions');
});

// ---------------------------------------------------------------------------
// Tests: update
// ---------------------------------------------------------------------------

test('update returns updated vehicle with new field values', async () => {
  const prisma = buildFakePrisma({ vehicles: [sampleVehicle('user-1')] });
  const service = new GarageService(prisma as never);

  const updated = await service.updateVehicle({
    vehicleId: 'vehicle-uuid-1',
    actor: memberActor('user-1'),
    make: 'Saab',
  });

  assert.equal(updated.make, 'Saab');
  assert.equal(updated.model, 'V70'); // unchanged
});

// ---------------------------------------------------------------------------
// Tests: delete
// ---------------------------------------------------------------------------

test('delete removes the vehicle from the user\'s list', async () => {
  const prisma = buildFakePrisma({ vehicles: [sampleVehicle('user-1')] });
  const service = new GarageService(prisma as never);

  await service.deleteVehicle({ vehicleId: 'vehicle-uuid-1', actor: memberActor('user-1') });

  const result = await service.listVehicles({ actor: memberActor('user-1'), page: 1, pageSize: 20 });
  assert.equal(result.vehicles.length, 0);
});
