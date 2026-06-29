import tseslint from 'typescript-eslint';

export default tseslint.config(...tseslint.configs.recommended, {
  ignores: ['lib/**', 'node_modules/**'],
  rules: {
    '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
  },
});
