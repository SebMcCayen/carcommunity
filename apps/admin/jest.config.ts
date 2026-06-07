import nextJest from 'next/jest.js';

const createJestConfig = nextJest({ dir: './' });

const customConfig = {
  testEnvironment: 'jsdom',
};

export default createJestConfig(customConfig);
