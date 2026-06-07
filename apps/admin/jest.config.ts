import nextJest from 'next/jest.js';
import type { Config } from 'jest';

const createJestConfig = nextJest({ dir: './' });

const customConfig: Config = {
  testEnvironment: 'jsdom',
};

export default createJestConfig(customConfig);
