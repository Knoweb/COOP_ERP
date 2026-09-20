/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import { configDefaults } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    // Components are tested in a simulated browser; plain functions do not mind.
    environment: 'jsdom',
    // Vitest hands a test an EMPTY text for a .css file unless told otherwise. The design tests
    // read tokens.css and shell.css as text ("?raw") to check contrast and the 8 px grid, so they
    // need the real content.
    css: true,
    // The Playwright specs in web/e2e are *.spec.ts, which Vitest's default pattern would also
    // pick up. They need a browser and a running stack, so Vitest would fail on the first
    // import. The other half of the rule is in playwright.config.ts, which takes *.spec.ts
    // only and therefore leaves the unit tests in src alone.
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
})
