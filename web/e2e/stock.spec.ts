// The stock screens through the browser, as the society's administrator (M5-12, demo scope):
// put a new item in use, prepare the opening stock of the development warehouse with it, land on
// the opening balance in its prepared state, and read the stock position of the warehouse.
//
// The stack is not reset between runs and a location takes one opening balance (flow 6.9), so each
// run registers a fresh warehouse and starts onboarding through M1 before preparing its balance.
// The development warehouse remains ACTIVE and proves that INV-08 refuses an opening balance there.
// Signing and countersigning ask for a fresh second factor and two different people of the
// society; the development realm has neither, so they are proved by the integration tests.

import { expect, test, type Page, type Request } from "@playwright/test";
import backendSinhala from "../../backend/app/src/main/resources/i18n/m5inventory/si.json" with { type: "json" };
import { MPCS_ADMIN, openSignedIn, textOf } from "./support/stack";

const ACTIVE_WAREHOUSE = "0190f000-0000-7000-8000-000000000102";

async function registerOnboardingWarehouse(page: Page, scopedRequest: Request, mark: string): Promise<string> {
  const requestHeaders = await scopedRequest.allHeaders();
  const authorization = requestHeaders.authorization;
  const entity = requestHeaders["x-scope-entity"];
  expect(authorization).toBeTruthy();
  expect(entity).toBeTruthy();

  const api = new URL(scopedRequest.url()).origin;
  const headers = {
    Authorization: authorization!,
    "X-Scope-Entity": entity!,
    "Accept-Language": requestHeaders["accept-language"] ?? MPCS_ADMIN.locale
  };
  const registered = await page.context().request.post(`${api}/v1/party/locations`, {
    headers: { ...headers, "Idempotency-Key": crypto.randomUUID() },
    data: { locationCode: `E2E${mark.slice(-8)}`, locationType: "WAREHOUSE", nameEn: `E2E onboarding warehouse ${mark}` }
  });
  const registeredBody = await registered.text();
  expect(registered.status(), registeredBody).toBe(201);
  const locationId = (JSON.parse(registeredBody) as { locationId: string }).locationId;

  const onboarding = await page.context().request.post(`${api}/v1/party/locations/${locationId}/start-onboarding`, {
    headers: { ...headers, "Idempotency-Key": crypto.randomUUID() }
  });
  expect(onboarding.status(), await onboarding.text()).toBe(204);
  return locationId;
}

async function addOpeningLine(page: Page, locationId: string, itemName: string): Promise<void> {
  await page.goto("/inventory/opening/new");
  await page.getByLabel(textOf(MPCS_ADMIN, "inventory.field.location")).selectOption(locationId);
  await page.getByLabel(textOf(MPCS_ADMIN, "inventory.field.find_item")).fill(itemName);
  await page.getByRole("button", { name: textOf(MPCS_ADMIN, "inventory.find"), exact: true }).click();
  await page.getByRole("button", { name: new RegExp(itemName) }).click();
  await page.getByLabel(textOf(MPCS_ADMIN, "inventory.column.qty")).fill("24");
  await page.getByLabel(textOf(MPCS_ADMIN, "inventory.column.unit_cost")).fill("310.5");
}

test("the opening stock of a location is prepared from the screen, and the stock position reads it", async ({ page }) => {
  const mark = Date.now().toString(36).toUpperCase();
  const name = `E2E dhal ${mark}`;

  // An item of the society, in use.
  const scopedRequest = page.waitForRequest((request) => request.method() === "GET" && /\/v1\/party\/entities\//.test(request.url()));
  await openSignedIn(page, MPCS_ADMIN, "/catalogue/skus/new");
  const onboardingWarehouse = await registerOnboardingWarehouse(page, await scopedRequest, mark);
  await page.getByLabel(textOf(MPCS_ADMIN, "catalogue.field.name_en")).fill(name);
  const tax = page.getByLabel(textOf(MPCS_ADMIN, "catalogue.field.tax_category"));
  await expect(tax.locator("option")).not.toHaveCount(1);
  await tax.selectOption({ index: 1 });
  await page.getByRole("button", { name: textOf(MPCS_ADMIN, "catalogue.new.submit") }).click();
  await page.getByRole("button", { name: textOf(MPCS_ADMIN, "catalogue.activate.local") }).click();
  await expect(page.getByText(textOf(MPCS_ADMIN, "catalogue.status.LOCAL"), { exact: true })).toBeVisible();

  // A fresh ONBOARDING warehouse accepts its opening stock.
  await addOpeningLine(page, onboardingWarehouse, name);
  const acceptedResponse = page.waitForResponse(
    (response) => response.request().method() === "POST" && response.url().endsWith("/v1/inventory/opening-balances")
  );
  await page.getByRole("button", { name: textOf(MPCS_ADMIN, "inventory.opening.prepare") }).click();
  expect((await acceptedResponse).status()).toBe(201);
  const prepared = page.getByText(textOf(MPCS_ADMIN, "inventory.opening.status.DRAFT"), { exact: true });
  await expect(prepared).toBeVisible();
  await expect(page).toHaveURL(/\/inventory\/opening\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("button", { name: textOf(MPCS_ADMIN, "inventory.opening.sign") })).toBeVisible();

  // The seeded ACTIVE warehouse is refused by INV-08, with the exact domain response visible.
  await addOpeningLine(page, ACTIVE_WAREHOUSE, name);
  const refusedResponse = page.waitForResponse(
    (response) => response.request().method() === "POST" && response.url().endsWith("/v1/inventory/opening-balances")
  );
  await page.getByRole("button", { name: textOf(MPCS_ADMIN, "inventory.opening.prepare") }).click();
  const refusal = await refusedResponse;
  expect(refusal.status()).toBe(422);
  expect((await refusal.json()) as { code: string }).toMatchObject({ code: "m5.opening.location_not_onboarding" });
  await expect(page.getByRole("alert")).toHaveText(backendSinhala["m5.opening.location_not_onboarding"]);

  // The stock position of the warehouse answers (empty until an opening balance is posted).
  await page.goto("/inventory");
  await expect(page.getByRole("heading", { level: 1, name: textOf(MPCS_ADMIN, "inventory.title") })).toBeVisible();
  await page.getByRole("button", { name: textOf(MPCS_ADMIN, "inventory.filter.toggle") }).click();
  await page.getByLabel(textOf(MPCS_ADMIN, "inventory.field.location")).selectOption(onboardingWarehouse);
  await expect(
    page.getByText(textOf(MPCS_ADMIN, "inventory.balances.empty"), { exact: true }).or(page.getByRole("columnheader", { name: textOf(MPCS_ADMIN, "inventory.column.on_hand") }))
  ).toBeVisible();
});
