/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
  },
  test: {
    // jsdom gives the component tests a DOM to render into. Nothing here talks
    // to a real backend — fetch is stubbed in every test that needs it.
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // Must stay comfortably above the Testing Library asyncUtilTimeout set in
    // the setup file. If the two are equal, a query that never resolves is
    // killed by the runner first and reports a bare "test timed out" instead
    // of Testing Library's far more useful "unable to find role=…". This is
    // headroom for a slow machine, not permission for a slow test: everything
    // here resolves in milliseconds when the CPU is free.
    testTimeout: 15000,
  },
})
