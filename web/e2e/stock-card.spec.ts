// The stock card through the browser, on the demo data of phase 3 (docs/DEMO.md): the society
// manager opens the stock position of Kuliyapitiya stores, follows an item to its stock card, and
// reads its movements in ledger order, the first being the stores' opening balance.

import { expect, test } from "@playwright/test";
import { DEMO, DEMO_LOCATIONS, openSignedIn, textOf } from "./support/stack";

test("an item of the stock position opens its stock card with the movements in ledger order", async ({ page }) => {
  const user = DEMO.m101Manager;
  await openSignedIn(page, user, "/inventory");
  await page.getByLabel(textOf(user, "inventory.field.location")).selectOption(DEMO_LOCATIONS.m101Stores);
  await expect(page.getByRole("columnheader", { name: textOf(user, "inventory.column.on_hand") })).toBeVisible();

  await page.locator("tbody tr").first().getByRole("link").click();

  await expect(page).toHaveURL(/\/inventory\/locations\/[0-9a-f-]{36}\/skus\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 1, name: textOf(user, "inventory.card.title") })).toBeVisible();
  await expect(page.getByRole("columnheader", { name: textOf(user, "inventory.card.column.balance") })).toBeVisible();
  await expect(page.locator("tbody tr").first()).toContainText(textOf(user, "inventory.movement.OPENING_BALANCE"));
});
