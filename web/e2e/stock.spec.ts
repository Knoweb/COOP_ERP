// The stock screens through the browser, as the society's administrator (M5-12, demo scope):
// put a new item in use, prepare the opening stock of the development warehouse with it, land on
// the opening balance in its prepared state, and read the stock position of the warehouse.
//
// The stack is not reset between runs and a location takes one opening balance (flow 6.9): on a
// stack that already has one for the warehouse, the server's own refusal is the expected answer.
// Signing and countersigning ask for a fresh second factor and two different people of the
// society; the development realm has neither, so they are proved by the integration tests.

import { expect, test } from "@playwright/test";
import backendSinhala from "../../backend/app/src/main/resources/i18n/si.json" with { type: "json" };
import { MPCS_ADMIN, openSignedIn, textOf } from "./support/stack";

const WAREHOUSE = "0190f000-0000-7000-8000-000000000102";

test("the opening stock of a location is prepared from the screen, and the stock position reads it", async ({ page }) => {
  const mark = Date.now().toString(36).toUpperCase();
  const name = `E2E dhal ${mark}`;

  // An item of the society, in use.
  await openSignedIn(page, MPCS_ADMIN, "/catalogue/skus/new");
  await page.getByLabel(textOf(MPCS_ADMIN, "catalogue.field.name_en")).fill(name);
  const tax = page.getByLabel(textOf(MPCS_ADMIN, "catalogue.field.tax_category"));
  await expect(tax.locator("option")).not.toHaveCount(1);
  await tax.selectOption({ index: 1 });
  await page.getByRole("button", { name: textOf(MPCS_ADMIN, "catalogue.new.submit") }).click();
  await page.getByRole("button", { name: textOf(MPCS_ADMIN, "catalogue.activate.local") }).click();
  await expect(page.getByText(textOf(MPCS_ADMIN, "catalogue.status.LOCAL"), { exact: true })).toBeVisible();

  // The opening stock of the warehouse.
  await page.goto("/inventory/opening/new");
  await page.getByLabel(textOf(MPCS_ADMIN, "inventory.field.location")).selectOption(WAREHOUSE);
  await page.getByLabel(textOf(MPCS_ADMIN, "inventory.field.find_item")).fill(name);
  await page.getByRole("button", { name: textOf(MPCS_ADMIN, "inventory.find"), exact: true }).click();
  await page.getByRole("button", { name: new RegExp(name) }).click();
  await page.getByLabel(textOf(MPCS_ADMIN, "inventory.column.qty")).fill("24");
  await page.getByLabel(textOf(MPCS_ADMIN, "inventory.column.unit_cost")).fill("310.5");
  await page.getByRole("button", { name: textOf(MPCS_ADMIN, "inventory.opening.prepare") }).click();

  const prepared = page.getByText(textOf(MPCS_ADMIN, "inventory.opening.status.DRAFT"), { exact: true });
  const refused = page
    .getByRole("alert")
    .filter({ hasText: new RegExp(`${backendSinhala["m5.opening.already_open"]}|${backendSinhala["m5.opening.location_has_stock"]}`) });
  await expect(prepared.or(refused)).toBeVisible();
  if (await prepared.isVisible()) {
    await expect(page).toHaveURL(/\/inventory\/opening\/[0-9a-f-]{36}$/);
    await expect(page.getByRole("button", { name: textOf(MPCS_ADMIN, "inventory.opening.sign") })).toBeVisible();
  }

  // The stock position of the warehouse answers (empty until an opening balance is posted).
  await page.goto("/inventory");
  await expect(page.getByRole("heading", { level: 1, name: textOf(MPCS_ADMIN, "inventory.title") })).toBeVisible();
  await page.getByLabel(textOf(MPCS_ADMIN, "inventory.field.location")).selectOption(WAREHOUSE);
  await expect(
    page.getByText(textOf(MPCS_ADMIN, "inventory.balances.empty"), { exact: true }).or(page.getByRole("columnheader", { name: textOf(MPCS_ADMIN, "inventory.column.on_hand") }))
  ).toBeVisible();
});
