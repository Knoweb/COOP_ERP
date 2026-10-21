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
import { DEMO, DEMO_LOCATIONS, FED_OFFICER, openSignedIn, textOf } from "./support/stack";

const RICE = /නාඩු සහල් 5 kg/;

function colomboToday(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo" }).format(new Date());
}

function toIsoDate(text: string): string | null {
  const parts = text.split("/");
  if (parts.length === 3) {
    const [day, month, year] = parts;
    return `${year}-${month.padStart(2, "0")}-${day.padStart(2, "0")}`;
  }
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) {
    return text;
  }
  return null;
}

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

test("the effective shelf list prints eligible labels and reports a label above the control price", async ({ page }) => {
  test.setTimeout(120_000);
  const manager = DEMO.m101Manager;
  const text = (id: string) => textOf(manager, id);
  let requested: { locationId: string; skuIds: string[] } | null = null;
  const jobId = "0190e903-2000-7000-8000-000000000001";

  // Chromium and the worker are covered by the backend print integration test. Here the browser
  // contract is deterministic: queue, poll to RENDERED, then show the control-price refusal.
  await page.route(/\/v1\/pricing\/labels/, async (route) => {
    const request = route.request();
    const cors = {
      "access-control-allow-origin": request.headers()["origin"] ?? "*",
      "access-control-allow-credentials": "true"
    };
    if (request.method() === "POST") {
      requested = request.postDataJSON() as { locationId: string; skuIds: string[] };
      return route.fulfill({
        status: 202,
        contentType: "application/json",
        headers: cors,
        json: {
          jobId,
          locationId: requested.locationId,
          status: "QUEUED",
          requestedCount: requested.skuIds.length,
          renderedCount: 0,
          refused: [],
          createdAt: "2026-10-21T06:00:00Z"
        }
      });
    }
    if (request.method() === "GET" && requested) {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        headers: cors,
        json: {
          jobId,
          locationId: requested.locationId,
          status: "RENDERED",
          requestedCount: requested.skuIds.length,
          renderedCount: Math.max(0, requested.skuIds.length - 1),
          refused: [{
            skuId: requested.skuIds[0],
            skuCode: "SKU-NADU-5KG",
            reason: "ABOVE_CONTROL_PRICE",
            listPrice: 1200,
            ceilingPrice: 1100,
            ceilingReference: "2492/29"
          }],
          downloadUrl: `${new URL(request.url()).origin}/reports/labels.pdf`,
          createdAt: "2026-10-21T06:00:00Z"
        }
      });
    }
    return route.continue();
  });

  await openSignedIn(page, manager, "/pricing/shelf");
  await expect(page.getByRole("table")).toBeVisible();

  // Find the effective retail list in force today (apply_from <= today, highest version).
  // This is independent of test execution order and whether another test drafted/published
  // a new version applying from tomorrow.
  const today = colomboToday();
  const tableRows = page.getByRole("table").getByRole("row");
  const rowCount = await tableRows.count();
  let effectiveLink = null;
  let highestVersion = -1;

  for (let i = 1; i < rowCount; i++) {
    const row = tableRows.nth(i);
    const cells = row.getByRole("cell");
    const cellCount = await cells.count();
    if (cellCount < 5) continue;
    const versionText = (await cells.nth(2).innerText()).trim();
    const version = parseInt(versionText, 10);
    const applyFromText = (await cells.nth(4).innerText()).trim();
    const isoDate = toIsoDate(applyFromText);
    if (isoDate && isoDate <= today && version > highestVersion) {
      highestVersion = version;
      effectiveLink = cells.nth(0).getByRole("link");
    }
  }

  if (!effectiveLink) {
    const v1Row = page.getByRole("row").filter({ has: page.getByRole("cell", { name: "1", exact: true }) });
    if ((await v1Row.count()) > 0) {
      effectiveLink = v1Row.first().getByRole("link");
    } else {
      effectiveLink = page.getByRole("row").nth(1).getByRole("link");
    }
  }

  await expect(effectiveLink).toBeVisible();
  await effectiveLink.click();

  await expect(page.getByRole("button", { name: text("pricing.labels.print"), exact: true })).toBeVisible();

  await page.getByLabel(text("pricing.labels.shop"), { exact: true }).selectOption(DEMO_LOCATIONS.m101TownShop);
  await page.getByRole("button", { name: text("pricing.labels.print"), exact: true }).click();

  await expect(page.getByText("SKU-NADU-5KG", { exact: true })).toBeVisible();
  await expect(page.getByText(text("pricing.labels.reason.above_control_price"), { exact: true })).toBeVisible();
  await expect(page.getByText(/2492\/29/)).toBeVisible();
  await expect(page.getByRole("button", { name: text("pricing.labels.open"), exact: true })).toBeVisible();
  expect(requested?.locationId).toBe(DEMO_LOCATIONS.m101TownShop);
  expect(requested?.skuIds.length).toBeGreaterThan(1);

  await page.context().route("**/reports/labels.pdf*", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/pdf",
      headers: { "content-disposition": 'attachment; filename="labels.pdf"' },
      body: "%PDF-1.4"
    })
  );
  const [download] = await Promise.all([
    page.context().waitForEvent("download"),
    page.getByRole("button", { name: text("pricing.labels.open"), exact: true }).click()
  ]);
  expect(download.url()).toContain("/reports/labels.pdf");
});

test("an MPCS user authors and activates a promotion rule on screen", async ({ page }) => {
  test.setTimeout(120_000);
  const manager = DEMO.m101Manager;
  const text = (id: string) => textOf(manager, id);
  await openSignedIn(page, manager, "/pricing/rules");

  const promoName = `Promo E2E ${Date.now()}`;
  await page.getByLabel(text("pricing.rules.field.name"), { exact: true }).fill(promoName);
  await page.getByLabel(text("pricing.rules.field.kind"), { exact: true }).selectOption("TIME_LIMITED_PRICE");

  // Pick SKU
  await page.getByLabel(text("pricing.field.find_item"), { exact: true }).fill("නාඩු");
  await page.getByRole("button", { name: text("pricing.find"), exact: true }).click();
  await page.getByRole("button", { name: /නාඩු/ }).first().click();

  // Benefit
  await page.getByLabel(text("pricing.rules.field.benefit_kind"), { exact: true }).selectOption("PERCENT_OFF");
  await page.getByLabel(text("pricing.rules.field.benefit_value"), { exact: true }).fill("10");

  // Valid from today
  const today = colomboTomorrow();
  await page.getByLabel(text("pricing.rules.field.valid_from"), { exact: true }).fill(today);

  // Save draft
  await page.getByRole("button", { name: text("pricing.rules.action.save_draft"), exact: true }).click();

  // Locate rule in table and assert DRAFT
  const ruleRow = page.getByRole("row").filter({ hasText: promoName });
  await expect(ruleRow).toBeVisible();
  await expect(ruleRow.getByText(text("pricing.rules.status.DRAFT"), { exact: true })).toBeVisible();

  // Activate rule
  await ruleRow.getByRole("button", { name: text("pricing.rules.action.activate"), exact: true }).click();
  await expect(ruleRow.getByText(text("pricing.rules.status.ACTIVE"), { exact: true })).toBeVisible();
});

test("an MPCS user authors an expiry markdown rule and inspects the markdown queue", async ({ page }) => {
  test.setTimeout(120_000);
  const manager = DEMO.m101Manager;
  const text = (id: string) => textOf(manager, id);
  await openSignedIn(page, manager, "/pricing/rules");

  const ruleName = `Markdown E2E ${Date.now()}`;
  await page.getByLabel(text("pricing.rules.field.name"), { exact: true }).fill(ruleName);
  await page.getByLabel(text("pricing.rules.field.kind"), { exact: true }).selectOption("EXPIRY_MARKDOWN");

  // Pick SKU
  await page.getByLabel(text("pricing.field.find_item"), { exact: true }).fill("නාඩු");
  await page.getByRole("button", { name: text("pricing.find"), exact: true }).click();
  await page.getByRole("button", { name: /නාඩු/ }).first().click();

  // Days to expiry
  await page.getByLabel(text("pricing.rules.field.days_to_expiry"), { exact: true }).fill("30");

  // Benefit
  await page.getByLabel(text("pricing.rules.field.benefit_kind"), { exact: true }).selectOption("PERCENT_OFF");
  await page.getByLabel(text("pricing.rules.field.benefit_value"), { exact: true }).fill("15");

  // Valid from
  const today = colomboTomorrow();
  await page.getByLabel(text("pricing.rules.field.valid_from"), { exact: true }).fill(today);

  // Save draft and activate
  await page.getByRole("button", { name: text("pricing.rules.action.save_draft"), exact: true }).click();
  const ruleRow = page.getByRole("row").filter({ hasText: ruleName });
  await expect(ruleRow).toBeVisible();
  await ruleRow.getByRole("button", { name: text("pricing.rules.action.activate"), exact: true }).click();
  await expect(ruleRow.getByText(text("pricing.rules.status.ACTIVE"), { exact: true })).toBeVisible();

  // Inspect markdown queue
  await page.getByRole("link", { name: text("pricing.tab.markdown"), exact: true }).click();
  await expect(page).toHaveURL(/\/pricing\/markdown/);

  // Select location and verify real inventory lots are loaded and rendered
  await page.getByLabel(text("pricing.markdown.select_location"), { exact: true }).selectOption(DEMO_LOCATIONS.m101TownShop);
  await expect(page.getByRole("table")).toBeVisible();
  const firstDataRow = page.getByRole("table").getByRole("row").nth(1);
  await expect(firstDataRow).toBeVisible();
  await expect(firstDataRow.getByText(/GOOD/)).toBeVisible();
  await expect(page.getByRole("row").filter({ hasText: /නාඩු/ })).toBeVisible();
});

test("entering a control price challenges with MFA step-up and automatically replays to completion", async ({ page }) => {
  test.setTimeout(120_000);
  const officer = FED_OFFICER;
  const text = (id: string) => textOf(officer, id);

  const tomorrow = colomboTomorrow();
  const gazetteRef = `2499/${Date.now().toString().slice(-4)}`;

  // The local development realm (realm-dev.json) has no second-factor authenticator configured,
  // and VITE_OIDC_STEP_UP_ACR_VALUES is empty. To verify the browser shell's StepUpReplay and
  // PendingCommand recovery flow under real conditions, we simulate the 401 mfa.required challenge
  // on the first POST attempt to /v1/pricing/control-prices. The shell intercepts this, preserves
  // the command and its Idempotency-Key, redirects through Keycloak with prompt=login, and replays
  // the pending mutation to completion. Backend test coverage independently proves that
  // EnterControlPriceHandler requires MFA via @CommandHandler(permission = "prc.controlprice.enter", requiresMfa = true).
  // The replayed POST is answered with 201 so the browser recovery flow is proven without
  // inserting conflicting date ranges that would break repeat test runs and shelf-price tests.
  let challenged = false;
  await page.route("**/v1/pricing/control-prices", async (route) => {
    const request = route.request();
    if (request.method() === "POST") {
      if (!challenged) {
        challenged = true;
        return route.fulfill({
          status: 401,
          contentType: "application/problem+json",
          headers: {
            "access-control-allow-origin": request.headers()["origin"] ?? "*",
            "access-control-allow-credentials": "true"
          },
          json: {
            status: 401,
            code: "mfa.required",
            title: "MFA step-up required"
          }
        });
      }
      return route.fulfill({
        status: 201,
        contentType: "application/json",
        headers: {
          "access-control-allow-origin": request.headers()["origin"] ?? "*",
          "access-control-allow-credentials": "true"
        },
        json: {
          controlPriceId: "01925b6a-0000-7000-8000-000000000099",
          skuId: "01a12e4a-4d8d-7381-b4df-b91ccf070000",
          ceilingPrice: 1250,
          ceilingUomCode: "EA",
          effectiveFrom: tomorrow,
          effectiveTo: null,
          gazetteReference: gazetteRef,
          state: "FUTURE"
        }
      });
    }
    return route.continue();
  });

  await openSignedIn(page, officer, "/pricing/gazette");

  // Pick SKU
  await page.getByLabel(text("pricing.field.find_item"), { exact: true }).fill("Nadu");
  await page.getByRole("button", { name: text("pricing.find"), exact: true }).click();
  await page.getByRole("button", { name: /Add|Nadu/i }).first().click();

  // Fill control price form
  await page.getByLabel(text("pricing.column.ceiling_price"), { exact: true }).fill("1250.00");
  await page.getByLabel(text("pricing.column.from"), { exact: true }).fill(tomorrow);
  await page.getByLabel(text("pricing.column.gazette"), { exact: true }).fill(gazetteRef);

  // Submit triggers 401 step-up challenge
  await page.getByRole("button", { name: text("pricing.gazette.submit"), exact: true }).click();

  // Expect redirect to sign in again for step-up
  const { expectSignInPage, signIn } = await import("./support/stack");
  await expectSignInPage(page);
  await signIn(page, officer);

  // Replays command and reports success
  await expect(page.getByText(text("shell.stepup.done"), { exact: true })).toBeVisible({ timeout: 30_000 });
});

test("pricing reviews screen lists exceptions with kind filtering and no status filter", async ({ page }) => {
  test.setTimeout(120_000);
  const manager = DEMO.m101Manager;
  const text = (id: string) => textOf(manager, id);

  const sampleReview = {
    reviewId: "01925b6a-0000-7000-8000-000000000001",
    kind: "BATCH_MRP_BELOW_LIST",
    severity: "REVIEW",
    occurredAt: "2026-10-13T10:00:00Z",
    ownerEntityId: "0190f0de-0000-7000-8000-000000000002",
    skuId: "0190f0de-0000-7000-8000-000000000010",
    skuCode: "SKU-NADU-5KG",
    skuName: "Nadu Rice 5kg",
    details: {
      batchNo: "BAT-2026-001",
      printedMrp: 1050,
      listPrice: 1100
    },
    status: "OPEN"
  };

  await page.route("**/v1/pricing/reviews*", async (route) => {
    const request = route.request();
    if (request.method() !== "GET") {
      return route.continue();
    }
    const url = new URL(request.url());
    const kind = url.searchParams.get("kind");
    const items = kind === "TRADE_ABOVE_MRP" ? [] : [sampleReview];
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      json: items,
      headers: {
        "access-control-allow-origin": request.headers()["origin"] ?? "*",
        "access-control-allow-credentials": "true"
      }
    });
  });

  await openSignedIn(page, manager, "/pricing/reviews");

  await expect(page.getByRole("heading", { name: text("pricing.reviews.title"), exact: true })).toBeVisible();

  // Verify review row renders with required fields: kind, severity, SKU context, details pills, status
  const reviewRow = page.getByRole("row").filter({ hasText: "SKU-NADU-5KG" });
  await expect(reviewRow).toBeVisible();
  await expect(reviewRow.getByText(text("pricing.reviews.kind.BATCH_MRP_BELOW_LIST"), { exact: true })).toBeVisible();
  await expect(reviewRow.getByText("REVIEW", { exact: true })).toBeVisible();
  await expect(reviewRow.getByText(/Nadu Rice 5kg/)).toBeVisible();
  await expect(reviewRow.getByText(/BAT-2026-001/)).toBeVisible();
  await expect(reviewRow.getByText(/1050/)).toBeVisible();
  await expect(reviewRow.getByText(/1100/)).toBeVisible();
  await expect(reviewRow.getByText(text("pricing.reviews.status.OPEN"), { exact: true })).toBeVisible();

  // Filter dropdown changes selection and filters displayed rows
  const kindFilter = page.getByLabel(text("pricing.reviews.filter_kind"), { exact: true });
  await expect(kindFilter).toBeVisible();
  await kindFilter.selectOption("BATCH_MRP_BELOW_LIST");
  await expect(reviewRow).toBeVisible();

  await kindFilter.selectOption("TRADE_ABOVE_MRP");
  await expect(reviewRow).toHaveCount(0);
  await expect(page.getByText(text("pricing.reviews.empty"), { exact: true })).toBeVisible();

  // Reset to ALL
  await kindFilter.selectOption("ALL");
  await expect(reviewRow).toBeVisible();

  // Verify scope correction: NO status filter dropdown (ALL/OPEN/SETTLED) exists
  await expect(page.getByLabel(text("pricing.rules.filter_status"))).toHaveCount(0);
});
