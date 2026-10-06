// What central flagged about a till receipt, in the shop manager's own language (wave 2, M6-09:
// the flags were shown as raw English codes). A flagged receipt needs a till that breaks its own
// contract, which the demo data never has, so the receipts list is answered here with one
// flagged receipt; everything else (sign-in, the shop, the screen, the catalogue of texts) is the
// real stack. Sinhala for the M101 manager, Tamil for the M103 manager.

import { expect, test } from "@playwright/test";
import { DEMO, DEMO_LOCATIONS, openSignedIn, textOf, type DevUser } from "./support/stack";

const flaggedReceipt = (locationId: string) => ({
  documentId: "0190f6aa-0000-7000-8000-00000000e2e1",
  locationId,
  docNumberDisplay: "E2E-RCT-0000001",
  issuedAt: "2026-09-28T04:30:00Z",
  businessDate: "2026-09-28",
  netAmount: 1250,
  taxAmount: 0,
  grossAmount: 1300,
  flags: ["TOTAL_MISMATCH"],
  lines: [],
  tenders: [{ seq: 1, kind: "CASH", amount: 1250 }]
});

const cases: { user: DevUser; shop: string }[] = [
  { user: DEMO.m101Manager, shop: DEMO_LOCATIONS.m101TownShop },
  { user: DEMO.m103Manager, shop: DEMO_LOCATIONS.m103PointPedroShop }
];

for (const { user, shop } of cases) {
  test(`the receipts list tells ${user.username} in ${user.locale} what central flagged`, async ({ page }) => {
    // The API is another origin than the web client: the preflight goes to the real server, and
    // the answer carries the CORS headers the browser asks for.
    await page.route(
      (url) => url.pathname.endsWith("/v1/pos/receipts"),
      (route) => {
        const request = route.request();
        if (request.method() !== "GET") {
          return route.continue();
        }
        return route.fulfill({
          json: { items: [flaggedReceipt(shop)] },
          headers: {
            "access-control-allow-origin": request.headers()["origin"] ?? "*",
            "access-control-allow-credentials": "true"
          }
        });
      }
    );

    await openSignedIn(page, user, `/pos?location=${shop}`);

    const row = page.getByRole("row").filter({ has: page.getByRole("link", { name: "E2E-RCT-0000001" }) });
    await expect(row.getByText(textOf(user, "pos.receipt.flagged"), { exact: true })).toBeVisible();
    await expect(row.getByText(textOf(user, "pos.flag.TOTAL_MISMATCH"), { exact: true })).toBeVisible();
  });
}
