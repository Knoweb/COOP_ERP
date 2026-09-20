/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    // Components are tested in a simulated browser; plain functions do not mind.
    environment: 'jsdom',
  },
})
