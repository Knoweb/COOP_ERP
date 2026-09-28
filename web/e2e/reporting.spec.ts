// The dashboard and the exception queue over the demo's eight weeks of history (docs/DEMO.md,
// `make demo-data`; M8-05): the Federation's accounts see their tiles and the cheque D101 paid
// with that bounced; D102's accounts see M103 past the exposure warning. The figures themselves
// are proved by the integration tests; here, that the screen shows them.

import { expect, test } from "@playwright/test";
import { DEMO, openSignedIn, textOf } from "./support/stack";

test("the Federation's dashboard shows its tiles and the bounced cheque in the exception queue", async ({ page }) => {
  const user = DEMO.fedAccounts;
  await openSignedIn(page, user, "/reporting");

  await expect(page.getByRole("heading", { name: textOf(user, "reporting.dashboard.title"), exact: true })).toBeVisible();
  for (const tile of ["m8.tile.sales", "m8.tile.receivables", "m8.tile.exceptions"]) {
    await expect(page.getByRole("listitem", { name: textOf(user, tile), exact: true })).toBeVisible();
  }
  await expect(
    page.getByRole("listitem", { name: textOf(user, "m8.tile.sales"), exact: true }).getByRole("img", {
      name: textOf(user, "reporting.trend.label"),
      exact: true
    })
  ).toBeVisible();

  const queue = page.getByRole("table", { name: textOf(user, "reporting.exceptions.caption"), exact: true });
  await expect(
    queue.getByRole("cell", { name: textOf(user, "reporting.exception.kind.CHEQUE_BOUNCED"), exact: true }).first()
  ).toBeVisible();
});

test("the distributor's exception queue lists M103 past its exposure warning", async ({ page }) => {
  const user = DEMO.d102Accounts;
  await openSignedIn(page, user, "/reporting/exceptions");

  await expect(page.getByRole("heading", { name: textOf(user, "reporting.exceptions.title"), exact: true })).toBeVisible();
  const queue = page.getByRole("table", { name: textOf(user, "reporting.exceptions.caption"), exact: true });
  await expect(
    queue.getByRole("cell", { name: textOf(user, "reporting.exception.kind.EXPOSURE_WARNING"), exact: true }).first()
  ).toBeVisible();
});
