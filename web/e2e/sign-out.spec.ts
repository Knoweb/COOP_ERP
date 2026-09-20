// Signing out really ends the session: the browser is back on the identity server's sign-in
// page, and the back office cannot be opened again without typing the password. A "sign out"
// that only clears the screen would leave the next person at the same machine signed in.

import { expect, test } from "@playwright/test";
import { FED_ADMIN, expectSignInPage, openSignedIn, textOf } from "./support/stack";

test("signing out returns to the sign-in page and the back office is not reachable afterwards", async ({ page }) => {
  await openSignedIn(page, FED_ADMIN, "/hello");

  await page.getByRole("button", { name: textOf(FED_ADMIN, "shell.auth.sign_out") }).click();
  await expectSignInPage(page);

  // A second attempt at a page of the back office: the sign-in page again, not the page.
  await page.goto("/hello");
  await expectSignInPage(page);
  await expect(page.getByRole("heading", { level: 1, name: textOf(FED_ADMIN, "hello.title") })).toHaveCount(0);
});
