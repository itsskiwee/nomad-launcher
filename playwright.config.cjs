const { defineConfig } = require('@playwright/test');
module.exports = defineConfig({
  testDir: './tests', testMatch: '**/*.spec.cjs', workers: 1,
  use: {
    baseURL: 'http://127.0.0.1:4173', viewport: { width: 854, height: 394 },
    launchOptions: process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {},
  },
  webServer: { command: 'python3 -m http.server 4173 --bind 127.0.0.1 --directory app/assets', url: 'http://127.0.0.1:4173', reuseExistingServer: !process.env.CI },
});
