// A stock count through the browser, on the demo data of phase 3 (docs/DEMO.md): the society's
// buyer counts red dhal at Kuliyapitiya stores and finds one unit short, which is within tolerance
// (2 units, and well under the Rs 1,000 value tolerance), so the count closes and posts at once; the item's stock card then shows the count
// adjustment as its latest movement. Then the society manager opens the demo's own count, whose
// shortfall beyond tolerance he approved (DemoStockOperations), and reads it as approved.
//
// Approving an adjustment asks for a fresh second factor, which the development realm does not
// have (as for the opening balance's signatures, stock.spec.ts): the approval itself is proved by
// StockCountPostgresIntegrationTest and StockControlHttpPostgresIntegrationTest, and the demo
// loader approves one with the manager's scope.

import { expect, test } from "@playwright/test";
import { DEMO, DEMO_LOCATIONS, openSignedIn, textOf } from "./support/stack";

test("a count within tolerance posts at once and the stock card shows the adjustment", async ({ page }) => {
  const buyer = DEMO.m101Buyer;
  await openSignedIn(page, buyer, "/inventory/counts");
  await page.getByLabel(textOf(buyer, "inventory.field.location")).selectOption(DEMO_LOCATIONS.m101Stores);
  await expect(page.getByRole("heading", { level: 2, name: textOf(buyer, "inventory.count.start_section") })).toBeVisible();

  // By items (the default): red dhal, which the demo holds at the stores (DemoStockOperations counts
  // it too). Since wave 2 (M5-14) a variance auto-posts only within the value tolerance as well
  // (Rs 1,000): one bag of red dhal (about Rs 315) is; one 5 kg bag of rice (Rs 1,210) waits.
  await page.getByRole("checkbox", { name: /රතු පරිප්පු 1 kg/ }).check();
  await page.getByRole("button", { name: textOf(buyer, "inventory.count.start"), exact: true }).click();
  await expect(page).toHaveURL(/\/inventory\/counts\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 1, name: textOf(buyer, "inventory.count.sheet_title") })).toBeVisible();

  // One unit short on the first lot, every other lot as the book says.
  const rows = page.locator("tbody tr");
  await expect(rows.first()).toBeVisible();
  const lots = await rows.count();
  for (let i = 0; i < lots; i++) {
    const row = rows.nth(i);
    const book = Number((await row.locator("td").nth(3).innerText()).trim());
    const counted = i === 0 ? Math.max(book - 1, 0) : book;
    await row.getByRole("textbox").first().fill(String(counted));
  }
  await page.getByRole("button", { name: textOf(buyer, "inventory.count.submit"), exact: true }).click();

  await expect(page.getByText(textOf(buyer, "inventory.count.status.CLOSED"), { exact: true })).toBeVisible();
  await expect(page.getByText(textOf(buyer, "inventory.count.within"), { exact: true }).first()).toBeVisible();

  // The item's stock card: the count adjustment is the latest movement.
  await page.locator("tbody tr").first().getByRole("link").click();
  await expect(page).toHaveURL(/\/inventory\/locations\/[0-9a-f-]{36}\/skus\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 1, name: textOf(buyer, "inventory.card.title") })).toBeVisible();
  await expect(page.locator("tbody tr").last()).toContainText(textOf(buyer, "inventory.movement.COUNT_ADJUST"));
});

test("the demo's count at the stores reads as approved by the manager", async ({ page }) => {
  const manager = DEMO.m101Manager;
  await openSignedIn(page, manager, "/inventory/counts");
  await page.getByLabel(textOf(manager, "inventory.field.location")).selectOption(DEMO_LOCATIONS.m101Stores);

  const approved = page.getByRole("row").filter({ hasText: textOf(manager, "inventory.count.outcome.APPROVED") });
  await expect(approved.first()).toBeVisible();
  await approved.first().getByRole("link", { name: textOf(manager, "inventory.count.open") }).click();

  await expect(page.getByText(textOf(manager, "inventory.count.beyond"), { exact: true }).first()).toBeVisible();
  await expect(page.getByText(textOf(manager, "inventory.count.review_value"), { exact: false })).toBeVisible();
});
