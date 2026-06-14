import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  // Produce a minimal standalone server for container deployments.
  output: 'standalone',
};

export default nextConfig;
