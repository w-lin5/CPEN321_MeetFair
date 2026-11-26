// Full-suite gate only. Interface runners intentionally use the base config,
// because mock-only/no-mock-only reports each exercise a subset of the backend.
module.exports = {
  ...require('./jest.config'),
  coverageReporters: ['text', 'lcov', 'html', 'json', 'text-summary'],
  coverageThreshold: {
    global: { statements: 100, branches: 100, functions: 100, lines: 100 },
  },
};
