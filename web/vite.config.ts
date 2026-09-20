/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
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
  },
})
