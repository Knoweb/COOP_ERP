// Everything the end-to-end tests need to know about the running stack: the three development
// users, where the web client and the identity server are, and how a test signs in.
//
// One file, so that no address, user name or password is scattered over the specs. Every value
// is read from the environment with the default of the local stack, so the same tests run
// against `make up`, against `make up-2` (the addresses are the same) and against another
// deployment by setting E2E_WEB and E2E_OIDC_AUTHORITY.
//
// About the password: `dev` is the development fixture committed in infra/compose/realm-dev.json
// and printed by `make up`. It is not a secret; the realm is imported again on every start and
// exists only on a developer machine and on the pipeline runner. Test code is the one place in
// this repository that may type it, and this is that place.

import { expect, type Page } from "@playwright/test";
import { messages, type Locale } from "../../src/shell/i18n/messages";

export const WEB = process.env.E2E_WEB ?? "http://localhost:5173";
export const OIDC_AUTHORITY = process.env.E2E_OIDC_AUTHORITY ?? "http://localhost:8085/realms/coop";
const PASSWORD = process.env.E2E_PASSWORD ?? "dev";

/** A development user of the realm, with the facts its token carries (claims ent, cls, lang). */
export type DevUser = {
  username: string;
  /** First and last name of the realm user: what the scope banner shows. */
  displayName: string;
  /** The language the screen must be in, from the `lang` claim. */
  locale: Locale;
  /** The policy class the scope banner must name, from the `cls` claim. */
  policyClass: "OWN" | "FEDERATION_VIEW";
};

export const FED_ADMIN: DevUser = {
  username: "fed-admin",
  displayName: "Federation Admin",
  locale: "en",
  policyClass: "FEDERATION_VIEW"
};

export const MPCS_ADMIN: DevUser = {
  username: "mpcs-admin",
  displayName: "MPCS Admin",
  locale: "si",
  policyClass: "OWN"
};

export const CASHIER: DevUser = {
  username: "cashier",
  displayName: "Shop Cashier",
  locale: "ta",
  policyClass: "OWN"
};

export const ALL_DEV_USERS = [FED_ADMIN, MPCS_ADMIN, CASHIER];

/**
 * The real text of a message id in the language of this user, taken from the catalogues the
 * application itself uses (web/src/shell/i18n/messages.ts). The tests assert on this and never
 * on a Sinhala or Tamil string typed into a spec: a text that changes in the catalogue must
 * change the assertion with it, and a text that is missing must fail here and not silently.
 *
 * Only messages without placeholders go through this helper; a message with {values} is
 * formatted by react-intl at run time and is asserted on in parts.
 */
export function textOf(user: DevUser, id: string): string {
  const text = messages[user.locale][id];
  if (text === undefined) {
    throw new Error(`The catalogue has no message "${id}" in ${user.locale}`);
  }
  return text;
}

/** The greeting rows of backend/app/src/main/resources/seed/hello/hello.dev.sql. */
export const SEED = {
  federationEn: "Welcome from the Federation",
  federationSi: "සම්මේලනයෙන් සාදරයෙන් පිළිගනිමු",
  societyEn: "Welcome from the society",
  societySi: "සමිතියෙන් සාදරයෙන් පිළිගනිමු",
  societyTa: "சங்கத்திலிருந்து வரவேற்கிறோம்"
};

/** Is the browser on the identity server's own sign-in page? */
export async function expectSignInPage(page: Page): Promise<void> {
  await page.waitForURL((url) => url.href.startsWith(OIDC_AUTHORITY));
  await expect(usernameBox(page)).toBeVisible();
}

/**
 * Type the development password on the identity server's page and wait until the browser is
 * back on the back office. The form is the identity server's, not ours, so it is found by the
 * roles and labels Keycloak gives it; the browser locale is fixed to English in
 * playwright.config.ts so that this page is always in English (the language of the back office
 * itself comes from the token and is what the tests check).
 */
export async function signIn(page: Page, user: DevUser): Promise<void> {
  await usernameBox(page).fill(user.username);
  // The name is anchored: beside the field the page also has a "Show password" button, and a
  // loose /password/ would match both.
  await page.getByRole("textbox", { name: /^password$/i }).fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => url.origin === new URL(WEB).origin);
}

/**
 * The whole way in: ask for a page of the back office as an anonymous visitor, land on the
 * sign-in page, sign in, and come back to the page that was asked for. Every test starts in a
 * fresh browser context, so there is never a session left over from another test.
 */
export async function openSignedIn(page: Page, user: DevUser, path: string): Promise<void> {
  await page.goto(path);
  await expectSignInPage(page);
  await signIn(page, user);
  await expect(page).toHaveURL(WEB + path);
}

/** The user-name field of the identity server's sign-in page: there, or nowhere. */
export function usernameBox(page: Page) {
  return page.getByRole("textbox", { name: /^username$/i });
}
