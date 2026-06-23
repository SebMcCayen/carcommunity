import { useCallback, useEffect, useRef, useState } from 'react';

import type {
  CreateVehicleRequest,
  UpdateVehicleRequest,
  VehicleDetail,
  VehicleSummary,
} from '@carcommunity/shared/garage';

import {
  createVehicle as createVehicleApi,
  deleteVehicle as deleteVehicleApi,
  getVehicle as getVehicleApi,
  listVehicles as listVehiclesApi,
  updateVehicle as updateVehicleApi,
} from '../api/garage';
import { loadSessionToken } from '../storage/tokenStorage';

export interface UseGarageListResult {
  vehicles: VehicleSummary[];
  isLoading: boolean;
  error: string | null;
  hasNext: boolean;
  page: number;
  loadMore: () => Promise<void>;
  refresh: () => Promise<void>;
  deleteVehicle: (vehicleId: string) => Promise<void>;
}

export interface UseGarageDetailResult {
  vehicle: VehicleDetail | null;
  isLoading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

export interface UseGarageFormResult {
  isSubmitting: boolean;
  error: string | null;
  createVehicle: (body: CreateVehicleRequest) => Promise<VehicleDetail | null>;
  updateVehicle: (vehicleId: string, body: UpdateVehicleRequest) => Promise<VehicleDetail | null>;
}

/**
 * Hook for the authenticated user's vehicle list.
 * Supports pagination and deletion.
 * Clears state on unmount (e.g. logout or entitlement loss) to protect private data.
 */
export function useGarageList(): UseGarageListResult {
  const [vehicles, setVehicles] = useState<VehicleSummary[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [hasNext, setHasNext] = useState(false);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      // Clear private garage data on unmount (e.g. logout or entitlement loss).
      setVehicles([]);
    };
  }, []);

  const loadPage = useCallback(async (pageNum: number, replace: boolean) => {
    setIsLoading(true);
    setError(null);
    try {
      const auth = await loadSessionToken().catch(() => null);
      const res = await listVehiclesApi(pageNum, 20, auth?.token ?? undefined);
      if (!mountedRef.current) return;
      setVehicles((prev) => (replace ? res.data.vehicles : [...prev, ...res.data.vehicles]));
      setPage(pageNum);
      setHasNext(res.meta.hasNext);
    } catch {
      if (!mountedRef.current) return;
      setError('garage.error');
    } finally {
      if (mountedRef.current) setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- triggers async fetch; state updates happen in async callbacks
    void loadPage(1, true);
  }, [loadPage]);

  const loadMore = useCallback(async () => {
    if (isLoading || !hasNext) return;
    await loadPage(page + 1, false);
  }, [isLoading, hasNext, page, loadPage]);

  const refresh = useCallback(async () => {
    await loadPage(1, true);
  }, [loadPage]);

  const deleteVehicle = useCallback(async (vehicleId: string) => {
    try {
      const auth = await loadSessionToken().catch(() => null);
      await deleteVehicleApi(vehicleId, auth?.token ?? undefined);
      if (!mountedRef.current) return;
      setVehicles((prev) => prev.filter((v) => v.id !== vehicleId));
    } catch {
      if (!mountedRef.current) return;
      setError('garage.deleteError');
    }
  }, []);

  return { vehicles, isLoading, error, hasNext, page, loadMore, refresh, deleteVehicle };
}

/**
 * Hook for a single vehicle detail.
 * Clears state on unmount to protect private vehicle data.
 */
export function useGarageDetail(vehicleId: string): UseGarageDetailResult {
  const [vehicle, setVehicle] = useState<VehicleDetail | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      // Clear private vehicle data on unmount.
      setVehicle(null);
    };
  }, []);

  const load = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const auth = await loadSessionToken().catch(() => null);
      const res = await getVehicleApi(vehicleId, auth?.token ?? undefined);
      if (!mountedRef.current) return;
      setVehicle(res.data.vehicle);
    } catch {
      if (!mountedRef.current) return;
      setError('garage.errorDetail');
    } finally {
      if (mountedRef.current) setIsLoading(false);
    }
  }, [vehicleId]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- triggers async fetch; state updates happen in async callbacks
    void load();
  }, [load]);

  return { vehicle, isLoading, error, refresh: load };
}

/**
 * Hook for create and update form submissions.
 * Prevents duplicate submissions with an isSubmitting guard.
 * Does not auto-fetch — call createVehicle or updateVehicle explicitly on form submit.
 */
export function useGarageForm(): UseGarageFormResult {
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const createVehicle = useCallback(async (body: CreateVehicleRequest): Promise<VehicleDetail | null> => {
    if (isSubmitting) return null;
    setIsSubmitting(true);
    setError(null);
    try {
      const auth = await loadSessionToken().catch(() => null);
      const res = await createVehicleApi(body, auth?.token ?? undefined);
      return res.data.vehicle;
    } catch {
      if (mountedRef.current) setError('garage.saveError');
      return null;
    } finally {
      if (mountedRef.current) setIsSubmitting(false);
    }
  }, [isSubmitting]);

  const updateVehicle = useCallback(async (
    vehicleId: string,
    body: UpdateVehicleRequest,
  ): Promise<VehicleDetail | null> => {
    if (isSubmitting) return null;
    setIsSubmitting(true);
    setError(null);
    try {
      const auth = await loadSessionToken().catch(() => null);
      const res = await updateVehicleApi(vehicleId, body, auth?.token ?? undefined);
      return res.data.vehicle;
    } catch {
      if (mountedRef.current) setError('garage.saveError');
      return null;
    } finally {
      if (mountedRef.current) setIsSubmitting(false);
    }
  }, [isSubmitting]);

  return { isSubmitting, error, createVehicle, updateVehicle };
}
