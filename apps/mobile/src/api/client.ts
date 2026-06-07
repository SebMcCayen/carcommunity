import { publicEnv } from '../config/env';

const buildUrl = (path: string) => `${publicEnv.apiBaseUrl}${path.startsWith('/') ? path : `/${path}`}`;

export const apiClient = {
  async health() {
    const response = await fetch(buildUrl('/health'));

    if (!response.ok) {
      throw new Error(`Health request failed with status ${response.status}`);
    }

    return response.json() as Promise<{ status: string }>;
  }
};
