import { expect, test, type Page } from "@playwright/test";
import { DEMO, FED_OFFICER, openSignedIn, textOf, type DevUser } from "./support/stack";

const FEDERATION_WAREHOUSE = "0190f0de-0000-7000-8000-000000000101";

async function openFirstStockLot(page: Page, user: DevUser): Promise<void> {
  await openSignedIn(page, user, "/inventory");
  await page.getByLabel(textOf(user, "inventory.field.location")).selectOption(FEDERATION_WAREHOUSE);
  const firstSku = page.locator("tr.stock-sku-row").first();
  await expect(firstSku).toBeVisible();
  await firstSku
    .getByRole("button", { name: textOf(user, "inventory.stock.action.expand_lots") })
    .click();
}

test("the stock reprice condition list stays in the viewport and supports mouse and keyboard", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await openFirstStockLot(page, FED_OFFICER);

  await page.getByRole("button", { name: textOf(FED_OFFICER, "inventory.reprice.action") }).first().click();
  const modal = page.getByRole("dialog");
  const trigger = modal
    .locator("label")
    .filter({ hasText: textOf(FED_OFFICER, "inventory.reprice.target_condition") })
    .getByRole("button");
  const triggerBox = await trigger.boundingBox();
  expect(triggerBox).not.toBeNull();
  expect(triggerBox!.y).toBeGreaterThan(720 / 2);

  await trigger.click();
  let listbox = page.getByRole("listbox", {
    name: textOf(FED_OFFICER, "inventory.reprice.target_condition")
  });
  const good = listbox.getByRole("option", { name: textOf(FED_OFFICER, "inventory.condition.GOOD") });
  const damaged = listbox.getByRole("option", {
    name: textOf(FED_OFFICER, "inventory.condition.DAMAGED")
  });
  await expect(good).toBeVisible();
  await expect(damaged).toBeVisible();

  const menuBox = await listbox.boundingBox();
  expect(menuBox).not.toBeNull();
  expect(menuBox!.x).toBeGreaterThanOrEqual(0);
  expect(menuBox!.y).toBeGreaterThanOrEqual(0);
  expect(menuBox!.x + menuBox!.width).toBeLessThanOrEqual(1280);
  expect(menuBox!.y + menuBox!.height).toBeLessThanOrEqual(720);

  await damaged.click();
  await expect(trigger).toHaveText(textOf(FED_OFFICER, "inventory.condition.DAMAGED"));
  await expect(trigger).toBeFocused();

  await trigger.press("ArrowUp");
  listbox = page.getByRole("listbox", {
    name: textOf(FED_OFFICER, "inventory.reprice.target_condition")
  });
  await expect(
    listbox.getByRole("option", { name: textOf(FED_OFFICER, "inventory.condition.DAMAGED") })
  ).toBeFocused();
  await page.keyboard.press("ArrowUp");
  await expect(
    listbox.getByRole("option", { name: textOf(FED_OFFICER, "inventory.condition.GOOD") })
  ).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(trigger).toHaveText(textOf(FED_OFFICER, "inventory.condition.GOOD"));

  await page.mouse.move(0, 0);
  await trigger.press("ArrowDown");
  listbox = page.getByRole("listbox", {
    name: textOf(FED_OFFICER, "inventory.reprice.target_condition")
  });
  await expect(
    listbox.getByRole("option", { name: textOf(FED_OFFICER, "inventory.condition.GOOD") })
  ).toBeFocused();
  await page.keyboard.press("ArrowDown");
  await expect(
    listbox.getByRole("option", { name: textOf(FED_OFFICER, "inventory.condition.DAMAGED") })
  ).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(listbox).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await expect(trigger).toHaveText(textOf(FED_OFFICER, "inventory.condition.GOOD"));

  await page.setViewportSize({ width: 1280, height: 520 });
  await trigger.scrollIntoViewIfNeeded();
  await trigger.click();
  const nearBottomTriggerBox = await trigger.boundingBox();
  const upwardMenuBox = await page
    .getByRole("listbox", { name: textOf(FED_OFFICER, "inventory.reprice.target_condition") })
    .boundingBox();
  expect(nearBottomTriggerBox).not.toBeNull();
  expect(upwardMenuBox).not.toBeNull();
  expect(upwardMenuBox!.y + upwardMenuBox!.height).toBeLessThanOrEqual(nearBottomTriggerBox!.y);
  await page.keyboard.press("Escape");
});

test("an account without inv.stock.reprice never receives the Reprice action", async ({ page }) => {
  const accounts = DEMO.fedAccounts;
  await openFirstStockLot(page, accounts);

  await expect(page.getByRole("button", { name: textOf(accounts, "inventory.reprice.action") })).toHaveCount(0);
});
