// The shelf prices through the browser (M3-06; doc 23 flow 6.1): Kuliyapitiya MPCS's manager
// drafts the next version of the society's shelf price list, prices Nadu rice above its gazetted
// control price (Rs 1,100, gazette 2492/29) and is refused with the ceiling shown, lowers it,
// publishes, and checks the price the town shop charges. Needs the demo data (`make demo-data`;
// the stack smoke loads it before the e2e run).
//
// The stack is not reset between runs: each run drafts and publishes one more version, or picks
// up the draft a failed run left behind. Dates are Colombo dates, the list applies from tomorrow,
// so a run near midnight or on a runner in another time zone still passes.

import { expect, test } from "@playwright/test";
import { DEMO, DEMO_LOCATIONS, openSignedIn, textOf } from "./support/stack";

const RICE = /නාඩු සහල් 5 kg/;

function colomboTomorrow(): string {
  const today = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo" }).format(new Date());
  const next = new Date(`${today}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return next.toISOString().substring(0, 10);
}

test("a shelf price above the control price is refused, and the published price resolves under it", async ({ page }) => {
  test.setTimeout(120_000);
  const manager = DEMO.m101Manager;
  const text = (id: string) => textOf(manager, id);
  await openSignedIn(page, manager, "/pricing/shelf");

  // The draft a failed run left, or a new version of the published list.
  const draftRow = page.getByRole("row").filter({ hasText: text("pricing.status.draft") });
  if ((await draftRow.count()) > 0) {
    await draftRow.first().getByRole("link").click();
  } else {
    await page.getByRole("row").filter({ hasText: text("pricing.status.published") }).first().getByRole("link").click();
    await page.getByRole("button", { name: text("pricing.next_version"), exact: true }).click();
  }
  await expect(page.getByText(text("pricing.status.draft"), { exact: true })).toBeVisible();

  // Above the ceiling: refused, with the control price named.
  const rice = page.getByRole("row", { name: RICE });
  const price = rice.getByLabel(text("pricing.column.shelf_price"), { exact: true });
  await price.fill("1200");
  await page.getByRole("button", { name: text("pricing.save"), exact: true }).click();
  await expect(rice.getByText(text("pricing.reason.above_control_price"), { exact: true })).toBeVisible();
  await expect(rice.getByText(/2492\/29/)).toBeVisible();
  await expect(page.getByRole("button", { name: text("pricing.publish"), exact: true })).toBeDisabled();

  // At the ceiling: saved, and published from tomorrow.
  await price.fill("1100");
  await page.getByRole("button", { name: text("pricing.save"), exact: true }).click();
  await expect(page.getByText(text("pricing.saved"), { exact: true })).toBeVisible();
  const tomorrow = colomboTomorrow();
  await page.getByLabel(text("pricing.field.apply_from"), { exact: true }).fill(tomorrow);
  await page.getByRole("button", { name: text("pricing.publish"), exact: true }).click();
  await expect(page.getByText(text("pricing.status.published"), { exact: true })).toBeVisible();

  // The town shop's price for tomorrow, as the engine resolves it: never above the control price.
  await page.getByLabel(text("pricing.check.shop"), { exact: true }).selectOption(DEMO_LOCATIONS.m101TownShop);
  const item = page.getByLabel(text("pricing.column.item"), { exact: true });
  const riceValue = await item.locator("option", { hasText: RICE }).getAttribute("value");
  await item.selectOption(riceValue ?? "");
  await page.getByLabel(text("pricing.check.date"), { exact: true }).fill(tomorrow);
  await page.getByRole("button", { name: text("pricing.check.run"), exact: true }).click();
  const answer = page.getByRole("definition");
  await expect(answer.first()).toContainText("1,100.00");
  await expect(answer.filter({ hasText: "1,100.00" })).toHaveCount(3); // price, list price, control price
});
