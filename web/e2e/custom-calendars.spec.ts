import { expect, test, type Page } from "@playwright/test";
import { FED_ADMIN, openSignedIn, textOf } from "./support/stack";

async function assertCalendarPopupOpensNearTrigger(page: Page, triggerIndex = 0): Promise<void> {
  const trigger = page.locator(".catalogue-datepicker-trigger").nth(triggerIndex);
  await expect(trigger).toBeVisible();
  const triggerBox = await trigger.boundingBox();
  expect(triggerBox).not.toBeNull();

  await trigger.click();

  const popup = page.locator(".catalogue-calendar-popup");
  await expect(popup).toBeVisible();

  const popupBox = await popup.boundingBox();
  expect(popupBox).not.toBeNull();

  // Regression guard: portal CSS must never override inline fixed coordinates with `left: 0` or `top: auto` off-screen.
  const viewport = page.viewportSize() ?? { width: 1280, height: 720 };
  expect(popupBox!.x).toBeGreaterThanOrEqual(0);
  expect(popupBox!.y).toBeGreaterThanOrEqual(0);
  expect(popupBox!.x + popupBox!.width).toBeLessThanOrEqual(viewport.width + 20);
  expect(popupBox!.y + popupBox!.height).toBeLessThanOrEqual(viewport.height + 20);
  expect(Math.abs(popupBox!.x - triggerBox!.x)).toBeLessThanOrEqual(30);

  const opensBelow = popupBox!.y >= triggerBox!.y + triggerBox!.height - 8;
  const opensAbove = popupBox!.y + popupBox!.height <= triggerBox!.y + 8;
  expect(opensBelow || opensAbove).toBe(true);
}

test("custom calendar popups open visibly under triggers across journal exports and finance", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });

  await openSignedIn(page, FED_ADMIN, "/integration/journal");
  await assertCalendarPopupOpensNearTrigger(page, 0);

  const dayCell = page.locator(".catalogue-calendar-popup .catalogue-calendar-day:not(:disabled)").first();
  await expect(dayCell).toBeVisible();
  await dayCell.click();
  await expect(page.locator(".catalogue-calendar-popup")).toHaveCount(0);

  await page.goto("/reporting/finance");
  await assertCalendarPopupOpensNearTrigger(page, 0);
  await page.keyboard.press("Escape");

  await page.getByRole("tab", { name: textOf(FED_ADMIN, "reporting.finance.tab_final_accounts") }).click();
  await assertCalendarPopupOpensNearTrigger(page, 0);
  await expect(page.locator(".catalogue-calendar-popup .catalogue-calendar-month")).toHaveCount(12);
});
