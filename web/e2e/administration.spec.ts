// Administration through the browser (phase 4 of the demo; 21A M1-07 and M1-08): the Federation
// officer creates a user, issues the first password, gives the user the demo's accounts role
// at the Federation warehouse only, and the new user signs in with that password and sees what
// the role allows and nothing else.
//
// How the login is provisioned: CreateUser makes the login at the identity provider through
// IdentityProviderClient (KeycloakAdminClient, the realm's service account coop-erp-backend),
// with the attributes the token mappers read (uid, ent, cls, locale) and the required action
// UPDATE_PASSWORD. reset-credential PASSWORD sets a temporary password and answers it once
// (delivery RETURNED). So the new user's first sign-in goes through the provider's own
// "update password" page, which this spec answers like a person would.
//
// Needs the demo data (`make demo-data`): the role "Demo: accounts" and the Federation
// warehouse are the demo's (seed/m1security/demo-users.demo.sql, seed/m1party/demo-parties.demo.sql).

import { expect, test } from "@playwright/test";
import { FED_ADMIN, FED_OFFICER, WEB, openSignedIn, textOf, usernameBox, type DevUser } from "./support/stack";

const FEDERATION_WAREHOUSE = "0190f0de-0000-7000-8000-000000000101";

// Users are never deleted and the stack is not reset between runs: a new name every run.
const uniqueUsername = () => `e2e-${Date.now().toString(36)}`;

test("an administrator creates a user, gives a role at a location, and the user sees only what it allows", async ({ browser }) => {
  const admin = FED_OFFICER;
  const username = uniqueUsername();
  const page = await (await browser.newContext()).newPage();

  // 1. The user, in English, of the officer's own entity (the Federation).
  await openSignedIn(page, admin, "/party/users/new");
  await page.getByRole("textbox", { name: textOf(admin, "party.user.username"), exact: true }).fill(username);
  await page.getByRole("textbox", { name: textOf(admin, "party.user.display_name"), exact: true }).fill(`E2E clerk ${username}`);
  await page.getByRole("button", { name: textOf(admin, "party.users.new.submit") }).click();
  await expect(page).toHaveURL(/\/party\/users\/[0-9a-f-]{36}$/);
  await expect(page.getByText(textOf(admin, "party.user.status.PENDING"), { exact: true })).toBeVisible();

  // 2. The first password, shown once.
  await page.getByRole("button", { name: textOf(admin, "party.user.password") }).click();
  const password = page.locator("code").and(page.getByLabel(textOf(admin, "party.user.password.value")));
  await expect(password).toBeVisible();
  const temporaryPassword = (await password.textContent())?.trim() ?? "";
  expect(temporaryPassword).not.toBe("");
  await expect(page.getByText(textOf(admin, "party.user.status.ACTIVE"), { exact: true })).toBeVisible();

  // 3. The accounts role, at the Federation warehouse only.
  await page.getByRole("combobox", { name: textOf(admin, "party.roles.role"), exact: true }).selectOption({ label: "Demo: accounts" });
  await page.getByRole("combobox", { name: textOf(admin, "party.roles.where"), exact: true }).selectOption(FEDERATION_WAREHOUSE);
  await page.getByRole("button", { name: textOf(admin, "party.roles.assign") }).click();
  await expect(page.getByText(textOf(admin, "party.roles.assigned"))).toBeVisible();
  await expect(page.getByRole("cell", { name: "Demo: accounts" })).toBeVisible();
  await page.context().close();

  // 4. The new user signs in with the temporary password and chooses a new one.
  const clerk: DevUser = { username, displayName: `E2E clerk ${username}`, locale: "en", policyClass: "OWN" };
  const own = await (await browser.newContext()).newPage();
  await own.goto("/trading");
  await expect(usernameBox(own)).toBeVisible();
  await usernameBox(own).fill(username);
  await own.getByRole("textbox", { name: /^password$/i }).fill(temporaryPassword);
  await own.getByRole("button", { name: /sign in/i }).click();
  const chosen = `Clerk-${Date.now().toString(36)}-pw`;
  await own.getByRole("textbox", { name: /^new password$/i }).fill(chosen);
  await own.getByRole("textbox", { name: /^confirm password$/i }).fill(chosen);
  await own.getByRole("button", { name: /submit/i }).click();
  await own.waitForURL((url) => url.origin === new URL(WEB).origin);

  // 5. What the role allows is in the navigation; what it does not is not there at all.
  const nav = own.getByRole("navigation", { name: textOf(clerk, "shell.nav.label") });
  await expect(nav.getByRole("link", { name: textOf(clerk, "trading.nav") })).toBeVisible();
  await expect(nav.getByRole("link", { name: textOf(clerk, "reporting.nav") })).toBeVisible();
  await expect(nav.getByRole("link", { name: textOf(clerk, "pricing.nav") })).toHaveCount(0);
  await expect(nav.getByRole("link", { name: textOf(clerk, "party.nav.users") })).toHaveCount(0);
  await expect(nav.getByRole("link", { name: textOf(clerk, "party.nav.roles") })).toHaveCount(0);

  // And an address typed by hand is refused by the route guard, not opened.
  await own.goto("/party/users");
  await expect(own.getByRole("heading", { level: 1, name: textOf(clerk, "shell.not_allowed.title") })).toBeVisible();
  await own.context().close();
});

test("the Federation view is shown no administration entry", async ({ page }) => {
  // fed-admin's class resolves to reads only; the user list is a read (gov.user.view), the role
  // catalogue and the grants are not, so only "Users" of the administration entries shows.
  await openSignedIn(page, FED_ADMIN, "/party/societies");
  const nav = page.getByRole("navigation", { name: textOf(FED_ADMIN, "shell.nav.label") });
  await expect(nav.getByRole("link", { name: textOf(FED_ADMIN, "party.nav") })).toBeVisible();
  await expect(nav.getByRole("link", { name: textOf(FED_ADMIN, "party.nav.roles") })).toHaveCount(0);
  await expect(nav.getByRole("link", { name: textOf(FED_ADMIN, "party.nav.grants") })).toHaveCount(0);
});

