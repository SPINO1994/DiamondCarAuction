import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 150_000,
  expect: { timeout: 10_000 },
  workers: 1,
  use: {
    baseURL: process.env.APP_URL || 'http://localhost:8080',
    channel: process.env.PLAYWRIGHT_CHANNEL || undefined,
    trace: 'retain-on-failure',
  },
});
