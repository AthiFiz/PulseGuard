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
    // Only collected when asked for, via `npm run test:coverage`. An ordinary
    // `vitest run` is unaffected and stays as fast as it was.
    coverage: {
      provider: 'v8',
      // lcov is what SonarQube reads; text prints a summary in the terminal so
      // the number is visible without opening a report.
      reporter: ['text', 'lcov'],
      reportsDirectory: './coverage',
      // Report on every source file, not only the ones a test happened to
      // import — otherwise an entirely untested module silently scores 100%
      // by being absent from the report.
      all: true,
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        // Bootstrap: mounts React and does nothing else worth asserting.
        'src/main.tsx',
        // Tests and their setup are not the subject of measurement.
        'src/**/*.test.{ts,tsx}',
        'src/test/**',
        // Pure type declarations — interfaces and unions that compile away to
        // nothing, so there is no runtime behaviour to cover.
        'src/types/**',
        'src/**/*.d.ts',
      ],
    },
  },
})
