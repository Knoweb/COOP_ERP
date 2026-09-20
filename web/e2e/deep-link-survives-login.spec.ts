// The regression this whole step exists for.
//
// Until step 4 the router was a constant created when its file was loaded, that is while the
// browser was still on "/?code=...&state=..." after the identity server had sent it back. The
// login callback then put the address the user had asked for back with history.replaceState(),
// which no router hears, so a user who had asked for /_design was signed in and shown the START
// PAGE. Every unit test passed: none of them performs a real login, because a login needs a real
// browser and a real password. The architect found it in his browser.
//
// So: a test that signs in for real and asks for a page that is not the start page. It fails
// when the router is created at module load again (web/src/router.tsx, createAppRouter).

import { expect, test } from "@playwright/test";
import { FED_ADMIN, expectSignInPage, openSignedIn, signIn, textOf, usernameBox } from "./support/stack";

test("a deep link survives a fresh login: after signing in the design page is shown, not the start page", async ({
  page
}) => {
  // First a real session, so that the sign-out below ends a session that existed.
  await openSignedIn(page, FED_ADMIN, "/hello");
  await page.getByRole("button", { name: textOf(FED_ADMIN, "shell.auth.sign_out") }).click();
  await expectSignInPage(page);

  // Now the deep link, as a user follows one from a message: a page of the shell that is in no
  // navigation, so it can only be reached by its address.
  await page.goto("/_design");
  await expectSignInPage(page);
  await signIn(page, FED_ADMIN);

  await expect(page).toHaveURL(/\/_design$/);
  await expect(page.getByRole("heading", { level: 1, name: textOf(FED_ADMIN, "shell.design.title") })).toBeVisible();
  // The start page would have this heading instead; say so, so that a failure reads clearly.
  await expect(page.getByRole("heading", { name: textOf(FED_ADMIN, "shell.home.title") })).toHaveCount(0);
});

test("a reload keeps the user signed in without showing the sign-in form again", async ({ page }) => {
  await openSignedIn(page, FED_ADMIN, "/hello");

  // The tokens live in memory only (doc 30 section 3), so the reload forgets them. The identity
  // server's own session cookie brings them back: a redirect that comes straight back, no form.
  await page.reload();

  await expect(page).toHaveURL(/\/hello$/);
  await expect(page.getByRole("heading", { level: 1, name: textOf(FED_ADMIN, "hello.title") })).toBeVisible();
  await expect(usernameBox(page)).toHaveCount(0);
});
