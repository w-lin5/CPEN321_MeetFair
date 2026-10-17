module.exports = {
  preset: 'ts-jest',
  // testEnvironment: 'node',
  // Emit source maps so coverage ignores TypeScript's generated import helpers (and reports real line numbers)
  transform: {
    '^.+\\.ts$': [
      'ts-jest',
      { tsconfig: { ...require('./tsconfig.json').compilerOptions, sourceMap: true } },
    ],
  },
  roots: ['<rootDir>/src', '<rootDir>/tests'],
  collectCoverageFrom: ['src/**/*.ts'],
  collectCoverage: true,
  coverageDirectory: 'coverage',
  coverageReporters: ['text', 'lcov', 'html', 'text-summary'],
  coveragePathIgnorePatterns: [
    '/node_modules/',
    '/dist/',
    '/coverage/'
  ],
  globalSetup: '@shelf/jest-mongodb/lib/setup',
  globalTeardown: '@shelf/jest-mongodb/lib/teardown',
  testEnvironment: '@shelf/jest-mongodb/lib/environment',
};