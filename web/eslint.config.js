// ESLint for the web client (17A section 11, stage "verify": lint). Run with `pnpm lint`.
// Deliberately small: the recommended rule sets, plus the two React hooks rules that catch
// real bugs (a hook called conditionally; an effect with a stale value). Add a rule here
// only when it has caught a mistake somebody actually made.

import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import tseslint from "typescript-eslint";

export default tseslint.config(
  // Never linted: build output, and clients generated from the OpenAPI slices.
  { ignores: ["dist", "src/generated"] },
  {
    files: ["src/**/*.{ts,tsx}"],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser
    },
    plugins: { "react-hooks": reactHooks },
    rules: {
      "react-hooks/rules-of-hooks": "error",
      "react-hooks/exhaustive-deps": "error"
    }
  },
  // The Playwright tests are linted too, and not ignored: they are code the team will copy
  // when a module gets its own end-to-end tests, and a typo there costs a pipeline run to
  // find. They run in Node and not in a browser, so they get the Node globals (process,
  // crypto) instead; the React hooks rules do not apply and are simply not added here.
  {
    files: ["e2e/**/*.ts", "playwright.config.ts"],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2022,
      globals: { ...globals.node, crypto: "readonly" }
    }
  }
);
