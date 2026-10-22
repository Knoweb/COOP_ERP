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
  // A shared item needs its name in all three languages (m2.sku.shared_translations_required).
  const nameTa = `E2E அரிசி ${mark}`;

  await openSignedIn(page, FED_OFFICER, "/catalogue");
  await page.getByRole("link", { name: textOf(FED_OFFICER, "catalogue.new") }).click();

  await page.getByLabel(textOf(FED_OFFICER, "catalogue.field.name_en")).fill(nameEn);
  await page.getByLabel(textOf(FED_OFFICER, "catalogue.field.name_si")).fill(nameSi);
  await page.getByLabel(textOf(FED_OFFICER, "catalogue.field.name_ta")).fill(nameTa);
  const tax = page.getByLabel(textOf(FED_OFFICER, "catalogue.field.tax_category"));
  await expect(tax.locator("option")).not.toHaveCount(1);
  await tax.selectOption({ index: 1 });
  await page.getByRole("button", { name: textOf(FED_OFFICER, "catalogue.new.submit") }).click();

  // The card: the draft, with the Federation's way to share it.
  await expect(page).toHaveURL(/\/catalogue\/skus\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(nameEn);
  await expect(page.getByText(textOf(FED_OFFICER, "catalogue.status.DRAFT"), { exact: true })).toBeVisible();
  await page.getByRole("button", { name: textOf(FED_OFFICER, "catalogue.activate.shared") }).click();
  await expect(page.getByText(textOf(FED_OFFICER, "catalogue.status.SHARED"), { exact: true })).toBeVisible();

  // The browser finds it by the Sinhala name.
  await page.getByRole("link", { name: textOf(FED_OFFICER, "catalogue.back") }).click();
  await page.getByRole("button", { name: textOf(FED_OFFICER, "catalogue.filter.toggle") }).click();
  await page.getByLabel(textOf(FED_OFFICER, "catalogue.filter.search")).fill(nameSi);
  const row = page.getByRole("row", { name: new RegExp(nameEn) });
  await expect(row).toBeVisible();
  await expect(row.getByText(textOf(FED_OFFICER, "catalogue.status.SHARED"), { exact: true })).toBeVisible();
});

test("an image can be attached to an active SKU and removed, and the SKU can be deactivated and reactivated", async ({ page }) => {
  const mark = unique();
  const nameEn = `E2E biscuit ${mark}`;
  const nameSi = `E2E බිස්කට් ${mark}`;
  const nameTa = `E2E பிஸ்கட் ${mark}`;

  await openSignedIn(page, FED_OFFICER, "/catalogue");
  await page.getByRole("link", { name: textOf(FED_OFFICER, "catalogue.new") }).click();

  await page.getByLabel(textOf(FED_OFFICER, "catalogue.field.name_en")).fill(nameEn);
  await page.getByLabel(textOf(FED_OFFICER, "catalogue.field.name_si")).fill(nameSi);
  await page.getByLabel(textOf(FED_OFFICER, "catalogue.field.name_ta")).fill(nameTa);
  const tax = page.getByLabel(textOf(FED_OFFICER, "catalogue.field.tax_category"));
  await expect(tax.locator("option")).not.toHaveCount(1);
  await tax.selectOption({ index: 1 });
  await page.getByRole("button", { name: textOf(FED_OFFICER, "catalogue.new.submit") }).click();

  await expect(page).toHaveURL(/\/catalogue\/skus\/[0-9a-f-]{36}$/);
  await page.getByRole("button", { name: textOf(FED_OFFICER, "catalogue.activate.shared") }).click();
  await expect(page.getByText(textOf(FED_OFFICER, "catalogue.status.SHARED"), { exact: true })).toBeVisible();

  // 1. Image-Attach Flow
  const pngBuffer = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    "base64"
  );
  const fileInput = page.locator('.catalogue-image-actions input[type="file"]');
  await fileInput.setInputFiles({
    name: "biscuit.png",
    mimeType: "image/png",
    buffer: pngBuffer
  });

  // Verify preview is rendered
  await expect(page.locator(".catalogue-image-img")).toBeVisible({ timeout: 10_000 });

  // Remove the attached image
  const removeBtn = page.getByRole("button", { name: textOf(FED_OFFICER, "catalogue.images.remove") });
  await expect(removeBtn).toBeVisible();
  await removeBtn.click();
  await expect(page.locator(".catalogue-image-no-image")).toBeVisible({ timeout: 10_000 });

  // 2. Deactivate the SKU
  await page.getByRole("button", { name: textOf(FED_OFFICER, "catalogue.deactivate") }).click();
  await expect(page.getByRole("heading", { level: 2, name: textOf(FED_OFFICER, "catalogue.deactivate.title") })).toBeVisible();
  await page.getByLabel(textOf(FED_OFFICER, "shell.reason.code")).selectOption({ index: 1 });
  await page.getByRole("button", { name: textOf(FED_OFFICER, "shell.reason.confirm") }).click();
  await expect(page.getByText(textOf(FED_OFFICER, "catalogue.status.INACTIVE"), { exact: true })).toBeVisible();

  // 3. Reactivate the SKU
  await page.getByRole("button", { name: textOf(FED_OFFICER, "catalogue.reactivate") }).click();
  await expect(page.getByRole("heading", { level: 2, name: textOf(FED_OFFICER, "catalogue.reactivate.title") })).toBeVisible();
  await page.getByLabel(textOf(FED_OFFICER, "shell.reason.code")).selectOption({ index: 1 });
  await page.getByRole("button", { name: textOf(FED_OFFICER, "shell.reason.confirm") }).click();
  await expect(page.getByText(textOf(FED_OFFICER, "catalogue.status.SHARED"), { exact: true })).toBeVisible();
});

test("a mis-keyed MRP on a batch can be successfully corrected via the screen", async ({ page }) => {
  test.setTimeout(120_000);

  // Navigate to an existing catalogue item that has batch tracking (from demo data: Samba rice 5 kg)
  await openSignedIn(page, FED_OFFICER, "/catalogue");
  await page.getByRole("button", { name: textOf(FED_OFFICER, "catalogue.filter.toggle") }).click();
  await page.getByLabel(textOf(FED_OFFICER, "catalogue.filter.search")).fill("Samba rice 5 kg");

  const itemRow = page.getByRole("row", { name: /Samba rice 5 kg/ });
  await expect(itemRow).toBeVisible();
  await itemRow.getByRole("link").click();

  // On the SKU page, navigate to batches
  await expect(page).toHaveURL(/\/catalogue\/skus\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 2, name: textOf(FED_OFFICER, "catalogue.batches.title") })).toBeVisible();

  // Select the active registered batch
  const registeredBatchRow = page
    .getByRole("row")
    .filter({ hasText: textOf(FED_OFFICER, "catalogue.batch.status.REGISTERED") })
    .first();
  await expect(registeredBatchRow).toBeVisible();

  await registeredBatchRow.getByRole("link", { name: textOf(FED_OFFICER, "catalogue.batches.view_batch") }).click();

  // On Batch Details Page
  await expect(page).toHaveURL(/\/catalogue\/batches\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 2, name: textOf(FED_OFFICER, "catalogue.batch.title") })).toBeVisible();
  await expect(page.getByText(textOf(FED_OFFICER, "catalogue.batch.status.REGISTERED"), { exact: true })).toBeVisible();

  // Initiate correction
  const correctBtn = page.getByRole("button", { name: textOf(FED_OFFICER, "catalogue.batch.correct") });
  await expect(correctBtn).toBeVisible();
  await correctBtn.click();

  // Form opens
  await expect(page.getByRole("heading", { level: 3, name: textOf(FED_OFFICER, "catalogue.batch.correct.title") })).toBeVisible();

  const mrpInput = page.getByLabel(textOf(FED_OFFICER, "catalogue.batch.field.mrp"));
  const currentVal = await mrpInput.inputValue();
  const targetMrp = currentVal === "1280.00" || currentVal === "1280" ? "1290.00" : "1280.00";

  await mrpInput.fill(targetMrp);
  await page.getByLabel(textOf(FED_OFFICER, "shell.reason.code")).selectOption("MISKEYED_MRP");
  await page.getByLabel(textOf(FED_OFFICER, "shell.reason.text")).fill("Correction of mis-keyed printed MRP");

  // Submit correction
  await page.getByRole("button", { name: textOf(FED_OFFICER, "catalogue.batch.correct.submit") }).click();

  // Assert successful correction notice and superseded state
  await expect(page.getByText(textOf(FED_OFFICER, "catalogue.batch.success.corrected"))).toBeVisible();
  await expect(page.getByText(textOf(FED_OFFICER, "catalogue.batch.superseded_notice"))).toBeVisible();
  await expect(page.getByText(textOf(FED_OFFICER, "catalogue.batch.status.SUPERSEDED"), { exact: true })).toBeVisible();

  // Click the link to the replacement batch
  await page.locator(".import-alert--success a").click();

  // Assert replacement batch is now the active registered batch with the corrected MRP
  await expect(page).toHaveURL(/\/catalogue\/batches\/[0-9a-f-]{36}$/);
  await expect(page.getByText(textOf(FED_OFFICER, "catalogue.batch.status.REGISTERED"), { exact: true })).toBeVisible();
  await expect(page.locator(".batch-detail-value--mrp")).toContainText(targetMrp.replace(".00", ""));
  await expect(page.locator(".batch-chain-notice")).toBeVisible();
});
