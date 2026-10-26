// INV-10 browser proof against the idempotent M5 demo data. These tests run new write-off and
// repack commands through their real screens, open a real delivery's pick list, and exercise the
// read-only screens. The stack is not reset between runs, so the repack is reversed before its
// test ends and the write-off uses the smallest valid quantity.

import { expect, test, type Browser, type Page } from "@playwright/test";
import backendSinhala from "../../backend/app/src/main/resources/i18n/m5inventory/si.json" with { type: "json" };
import { DEMO, DEMO_LOCATIONS, openSignedIn, textOf, type DevUser } from "./support/stack";

async function openAs(browser: Browser, user: DevUser, path: string): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await openSignedIn(page, user, path);
  return page;
}

test("a write-off is requested, submitted, witnessed and approved by the required people", async ({ browser }) => {
  test.setTimeout(180_000);
  const buyer = DEMO.m101Buyer;
  let page = await openAs(browser, buyer, "/inventory/write-offs");
  await page.getByLabel(textOf(buyer, "inventory.field.location")).selectOption(DEMO_LOCATIONS.m101Stores);
  await page.getByLabel(textOf(buyer, "inventory.writeoff.category")).selectOption("OTHER");
  await expect(page.getByRole("button", { name: textOf(buyer, "inventory.writeoff.save") })).toBeDisabled();
  await page.locator('input[inputmode="decimal"]').first().fill("0.001");
  await page.getByRole("button", { name: textOf(buyer, "inventory.writeoff.save") }).click();
  await expect(page).toHaveURL(/\/inventory\/write-offs\/[0-9a-f-]{36}$/);
  const writeOffPath = new URL(page.url()).pathname;
  await page.getByRole("button", { name: textOf(buyer, "inventory.writeoff.submit") }).click();
  await expect(page.getByText(textOf(buyer, "inventory.writeoff.status.REQUESTED"), { exact: true })).toBeVisible();
  await page.context().close();

  const witness = DEMO.m101Office;
  page = await openAs(browser, witness, writeOffPath);
  await page.getByRole("button", { name: textOf(witness, "inventory.writeoff.witness") }).click();
  await expect(page.getByText(textOf(witness, "inventory.writeoff.status.WITNESSED"), { exact: true })).toBeVisible();
  await page.context().close();

  const manager = DEMO.m101Manager;
  page = await openAs(browser, manager, writeOffPath);
  await page.getByRole("button", { name: textOf(manager, "inventory.writeoff.approve") }).click();
  await expect(page.getByText(textOf(manager, "inventory.writeoff.status.POSTED"), { exact: true })).toBeVisible();
  await page.context().close();
});

test("a repack executes successfully after validation and is reversed to keep the demo repeatable", async ({ browser }) => {
  test.setTimeout(180_000);
  const buyer = DEMO.m101Buyer;
  let page = await openAs(browser, buyer, "/inventory/repacks");
  await page
    .getByLabel(textOf(buyer, "inventory.field.location"))
    .selectOption(DEMO_LOCATIONS.m101Stores);

  await expect(page.getByRole("button", { name: textOf(buyer, "inventory.repack.execute") })).toBeDisabled();
  await page.getByLabel(textOf(buyer, "inventory.repack.recipe"), { exact: true }).selectOption({ index: 1 });
  const lots = page.getByLabel(textOf(buyer, "inventory.repack.input_lot"), { exact: true });
  await expect(lots.locator("option")).not.toHaveCount(1);
  await lots.selectOption({ index: 1 });
  await page.getByLabel(textOf(buyer, "inventory.repack.input_qty"), { exact: true }).fill("1");
  await page.getByLabel(textOf(buyer, "inventory.repack.actual"), { exact: true }).fill("999");
  await page.getByRole("button", { name: textOf(buyer, "inventory.repack.execute") }).click();
  const invalidYieldMessage = backendSinhala["m5.repack.yield_out_of_range"]
    .replace("{actual}", "999")
    .replace("{expected}", "0.200");
  await expect(page.getByRole("alert")).toHaveText(invalidYieldMessage);

  const executed = page.getByText(textOf(buyer, "inventory.repack.status.EXECUTED"), { exact: true });
  const before = await executed.count();
  await page.getByLabel(textOf(buyer, "inventory.repack.input_qty"), { exact: true }).fill("5");
  await page.getByLabel(textOf(buyer, "inventory.repack.actual"), { exact: true }).fill("1");
  await page.getByRole("button", { name: textOf(buyer, "inventory.repack.execute") }).click();
  await expect(executed).toHaveCount(before + 1);
  await page.context().close();

  const manager = DEMO.m101Manager;
  page = await openAs(browser, manager, "/inventory/repacks");
  await page
    .getByLabel(textOf(manager, "inventory.field.location"))
    .selectOption(DEMO_LOCATIONS.m101Stores);
  const newestExecuted = page.getByRole("row").filter({ hasText: textOf(manager, "inventory.repack.status.EXECUTED") }).first();
  await newestExecuted.getByRole("button", { name: textOf(manager, "inventory.repack.reverse") }).click();
  const reversal = page.getByRole("dialog", { name: textOf(manager, "inventory.repack.reverse.question") });
  await expect(reversal).toBeVisible();
  await reversal.getByRole("combobox").selectOption("WRONG_QTY");
  await reversal.getByRole("button", { name: textOf(manager, "shell.reason.confirm") }).click();
  await expect(page.getByText(textOf(manager, "inventory.repack.status.REVERSED"), { exact: true }).first()).toBeVisible();
  await page.context().close();
});

test("a warehouse user opens a real pick list with physical batch details", async ({ page }) => {
  const stores = DEMO.fedStores;
  await openSignedIn(page, stores, "/trading");
  const notes = page.getByRole("heading", { name: textOf(stores, "trading.notes.title") }).locator("xpath=..");
  const href = await notes.locator('a[href^="/trading/delivery-notes/"]').first().getAttribute("href");
  expect(href).not.toBeNull();
  const deliveryNoteId = href!.split("/").at(-1)!;

  await page.goto("/inventory/pick-lists");
  await page.getByLabel(textOf(stores, "inventory.pick.delivery_note")).fill(deliveryNoteId);
  await page.getByRole("button", { name: textOf(stores, "inventory.pick.open") }).click();
  const table = page.getByRole("table");
  await expect(table).toBeVisible();
  await expect(table.getByRole("row")).not.toHaveCount(1);
  const batch = table.locator("tbody td").nth(1);
  await expect(batch).not.toHaveText(textOf(stores, "inventory.loading"));
  await expect(batch).not.toHaveText(/[0-9a-f]{8}-[0-9a-f-]{27}/i);
});

test("expiry, loss and policy screens expose honest supported views", async ({ page }) => {
  const buyer = DEMO.m101Buyer;
  await openSignedIn(page, buyer, "/inventory/expiry");
  await page.getByLabel(textOf(buyer, "inventory.field.location")).selectOption(DEMO_LOCATIONS.m101Stores);
  await expect(page.getByRole("table")).toBeVisible();

  await page.goto("/inventory/losses");
  await page.getByLabel(textOf(buyer, "inventory.field.location")).selectOption(DEMO_LOCATIONS.m101Stores);
  await expect(page.getByLabel(textOf(buyer, "inventory.loss.summary"))).toBeVisible();
  await expect(page.getByText(textOf(buyer, "inventory.loss.negative_lots"), { exact: true })).toBeVisible();

  await page.goto("/inventory/policies");
  await expect(page.getByRole("status")).toHaveText(textOf(buyer, "inventory.policy.read_unavailable"));
});

test("a user without warehouse-picking authority cannot open a pick list", async ({ page }) => {
  const manager = DEMO.m101Manager;
  await openSignedIn(page, manager, "/inventory/pick-lists");
  await expect(page.getByRole("alert")).toContainText(textOf(manager, "shell.not_allowed.title"));
  await expect(page.getByText(textOf(manager, "inventory.pick.intro"))).toHaveCount(0);
});
