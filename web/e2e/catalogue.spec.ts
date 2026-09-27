// The catalogue through the browser, as the Federation officer (M2-10, demo scope): create an
// item as a draft with its three names, share it with every society, and find it again in the
// browser by its Sinhala name, with its new state.

import { expect, test } from "@playwright/test";
import { FED_OFFICER, openSignedIn, textOf } from "./support/stack";

// The stack is not reset between runs, so every run names an item nobody has named before.
const unique = () => Date.now().toString(36).toUpperCase();

test("a new item is created as a draft, shared, and found by its Sinhala name", async ({ page }) => {
  const mark = unique();
  const nameEn = `E2E rice ${mark}`;
  const nameSi = `E2E සහල් ${mark}`;

  await openSignedIn(page, FED_OFFICER, "/catalogue");
  await page.getByRole("link", { name: textOf(FED_OFFICER, "catalogue.new") }).click();

  await page.getByLabel(textOf(FED_OFFICER, "catalogue.field.name_en")).fill(nameEn);
  await page.getByLabel(textOf(FED_OFFICER, "catalogue.field.name_si")).fill(nameSi);
  const tax = page.getByLabel(textOf(FED_OFFICER, "catalogue.field.tax_category"));
  await expect(tax.locator("option")).not.toHaveCount(1);
  await tax.selectOption({ index: 1 });
  await page.getByRole("button", { name: textOf(FED_OFFICER, "catalogue.new.submit") }).click();

  // The card: the draft, with the Federation's way to share it.
  await expect(page).toHaveURL(/\/catalogue\/skus\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(nameEn);
  await expect(page.getByText(textOf(FED_OFFICER, "catalogue.status.DRAFT"))).toBeVisible();
  await page.getByRole("button", { name: textOf(FED_OFFICER, "catalogue.activate.shared") }).click();
  await expect(page.getByText(textOf(FED_OFFICER, "catalogue.status.SHARED"))).toBeVisible();

  // The browser finds it by the Sinhala name.
  await page.getByRole("link", { name: textOf(FED_OFFICER, "catalogue.back") }).click();
  await page.getByLabel(textOf(FED_OFFICER, "catalogue.filter.search")).fill(nameSi);
  const row = page.getByRole("row", { name: new RegExp(nameEn) });
  await expect(row).toBeVisible();
  await expect(row.getByText(textOf(FED_OFFICER, "catalogue.status.SHARED"))).toBeVisible();
});
