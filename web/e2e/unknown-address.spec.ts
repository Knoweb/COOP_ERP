// An address no module owns gets a sentence in the user's language inside the frame, never a
// blank page and never the start page. It is also a second reading of the deep-link fix: the
// address that survives the login is the wrong one the user typed, not "/".

import { expect, test } from "@playwright/test";
import { CASHIER, openSignedIn, textOf } from "./support/stack";

test("an unknown address shows the translated page-not-found screen", async ({ page }) => {
  await openSignedIn(page, CASHIER, "/no-such-page");

  await expect(page.getByRole("heading", { level: 1, name: textOf(CASHIER, "shell.not_found.title") })).toBeVisible();
  await expect(page.getByText(textOf(CASHIER, "shell.not_found.text"))).toBeVisible();
  // The frame is still there: a user who lands here can navigate away.
  await expect(page.getByRole("navigation", { name: textOf(CASHIER, "shell.nav.label") })).toBeVisible();
});
