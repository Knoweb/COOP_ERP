// What each role sees on the hello screen, and what each scope sees of the data.
//
// Two different rules are checked here and they must not be confused:
//   - the ROLE decides what the screen offers (shell/auth/permissions.ts, temporary until
//     19A K-03). A cashier may read greetings and not register them, so there is no form;
//   - the SCOPE decides which rows the server returns (row-level security). The Federation
//     view sees every entity, an MPCS user sees its own society only.

import { expect, test } from "@playwright/test";
import { CASHIER, FED_ADMIN, MPCS_ADMIN, SEED, openSignedIn, textOf } from "./support/stack";

test("the cashier reads the greetings and is told why there is no form", async ({ page }) => {
  await openSignedIn(page, CASHIER, "/hello");

  await expect(page.getByRole("heading", { level: 2, name: textOf(CASHIER, "hello.list.title") })).toBeVisible();
  await expect(page.getByText(SEED.societyTa)).toBeVisible();

  // Doc 30: an action the user may not take is not offered, and the screen says why.
  await expect(page.getByRole("note")).toHaveText(textOf(CASHIER, "hello.read_only"));
  await expect(page.getByRole("button", { name: textOf(CASHIER, "hello.register") })).toHaveCount(0);
});

test("the MPCS administrator is offered the register form", async ({ page }) => {
  await openSignedIn(page, MPCS_ADMIN, "/hello");

  await expect(page.getByLabel(textOf(MPCS_ADMIN, "hello.field.text_en"))).toBeVisible();
  await expect(page.getByRole("button", { name: textOf(MPCS_ADMIN, "hello.register") })).toBeVisible();
  await expect(page.getByRole("note")).toHaveCount(0);
});

test("the Federation view sees the greetings of both entities and the society only its own", async ({ browser }) => {
  // Two separate contexts, because two users cannot be signed in in one browser profile.
  const federation = await browser.newPage();
  await openSignedIn(federation, FED_ADMIN, "/hello");
  await expect(federation.getByText(SEED.federationEn)).toBeVisible();
  await expect(federation.getByText(SEED.societyEn)).toBeVisible();
  await federation.close();

  const society = await browser.newPage();
  await openSignedIn(society, MPCS_ADMIN, "/hello");
  // The society's own row, in the Sinhala the seed carries for it.
  await expect(society.getByText(SEED.societySi)).toBeVisible();
  // And nothing of the Federation's, in either language it exists in.
  await expect(society.getByText(SEED.federationSi)).toHaveCount(0);
  await expect(society.getByText(SEED.federationEn)).toHaveCount(0);
  await society.close();
});
