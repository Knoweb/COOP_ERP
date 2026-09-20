// Playwright: the end-to-end tests of the back office (17A section 7 and 11). Run them with
// `make e2e` from the repository root, against a stack that is already running (`make up` or
// `make up-2`), exactly as `make smoke` is run. Playwright starts no server of its own: the
// web client, the backend, the database and the identity server all come from docker compose,
// and a test that started its own server would prove less than the one that does not.
//
// The addresses and the development users are in e2e/support/stack.ts, read from the
// environment with the defaults of the local stack.

import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",

  // Playwright takes *.spec.ts, Vitest takes src/**/*.test.ts(x) (see vite.config.ts). Two
  // runners in one folder tree need a rule each, or both try to run the other's tests and fail
  // with something that reads like a broken import.
  testMatch: /.*\.spec\.ts$/,

  // A login goes through three redirects and a container that may still be warming up, so the
  // budgets are generous; a test that is merely slow must not be reported as broken.
  timeout: 90_000,
  expect: { timeout: 20_000 },

  fullyParallel: true,

  // Two at a time. More browsers do not make the stack answer faster, and the build machines
  // this runs on have little free memory (docs/PROGRESS.md: heavy jobs one at a time).
  workers: 2,

  // No retries, on purpose. Every test here starts in a fresh browser context and registers a
  // text nobody has registered before, so a failure is a fault and not luck; a retry would hide
  // exactly the kind of bug this step exists to catch.
  retries: 0,

  // A `test.only` left in a spec would silently run one test and report green.
  forbidOnly: !!process.env.CI,

  reporter: [["list"], ["html", { outputFolder: "playwright-report", open: "never" }]],

  use: {
    baseURL: process.env.E2E_WEB ?? "http://localhost:5173",

    // What to look at when something failed, and nothing when it did not: a trace is a few
    // megabytes and the pipeline uploads it as an artefact.
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",

    // The browser is English DELIBERATELY: the identity server's own sign-in page then always
    // speaks the language the helpers in support/stack.ts look for, while the language of the
    // back office comes from the user's token. That is the point of the language tests: the
    // screen is Sinhala for the MPCS administrator although the browser asked for English.
    locale: "en-US",
    timezoneId: "Asia/Colombo"
  },

  // Chromium only. One browser keeps the pipeline short, and the back office is used on managed
  // machines in society offices with the browser the deployment prescribes, not on whatever a
  // visitor brings. The till is a native Android application and is tested by its own project.
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }]
});
