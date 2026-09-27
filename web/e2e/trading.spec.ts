// Phase 1 of the demo through the browser (docs/DEMO.md, steps 4 to 9; M4-11): the Federation
// sells to the Wayamba distributor, each step signed in as the demo user whose job it is. Needs
// the demo data (`make demo-data`; the stack smoke loads it before the e2e run).
//
// The stack is not reset between runs: every run places a new order, and the demo's opening
// stock (600 bags of rice, 700 of dhal at the Federation warehouse) lasts many runs.

import { expect, test, type Page } from "@playwright/test";
import { DEMO, openSignedIn, textOf, type DevUser } from "./support/stack";

const FED_WAREHOUSE = "0190f0de-0000-7000-8000-000000000101";

async function addItem(page: Page, user: DevUser, query: string, name: RegExp, qty: string) {
  await page.getByLabel(textOf(user, "trading.field.find_item")).fill(query);
  await page.getByRole("button", { name: textOf(user, "trading.find"), exact: true }).click();
  await page.getByRole("button", { name }).first().click();
  await page.getByLabel(textOf(user, "trading.column.qty")).last().fill(qty);
}

test("the Federation sells to D101: order, acceptance, delivery note, dispatch, goods received", async ({ browser }) => {
  test.setTimeout(240_000);
  const as = async (user: DevUser, path: string) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    await openSignedIn(page, user, path);
    return page;
  };

  // 4. Order: d101-buyer orders rice and dhal from the Federation at the Federation's list.
  const buyer = DEMO.d101Buyer;
  let page = await as(buyer, "/trading/orders/new");
  const seller = page.getByLabel(textOf(buyer, "trading.field.seller"));
  await expect(seller.locator("option")).not.toHaveCount(1);
  await seller.selectOption({ index: 1 });
  const deliverTo = page.getByLabel(textOf(buyer, "trading.field.deliver_to"));
  await expect(deliverTo.locator("option")).not.toHaveCount(1);
  await deliverTo.selectOption({ index: 1 });
  await addItem(page, buyer, "Samba rice 5 kg", /සම්බා සහල් 5 kg/, "120");
  await addItem(page, buyer, "Red dhal 1 kg", /රතු පරිප්පු 1 kg/, "40");
  await page.getByRole("button", { name: textOf(buyer, "trading.order.submit") }).click();
  await expect(page).toHaveURL(/\/trading\/orders\/[0-9a-f-]{36}$/);
  await expect(page.getByText(textOf(buyer, "trading.order.status.SUBMITTED"), { exact: true })).toBeVisible();
  const orderPath = new URL(page.url()).pathname;
  await page.context().close();

  // 5. Accept: fed-sales at the order desk.
  const sales = DEMO.fedSales;
  page = await as(sales, orderPath);
  await page.getByRole("button", { name: textOf(sales, "trading.order.accept") }).click();
  await expect(page.getByText(textOf(sales, "trading.order.status.ACCEPTED"), { exact: true })).toBeVisible();

  // 6. Delivery note: fed-sales drafts it from the accepted order and issues it.
  await page.getByRole("link", { name: textOf(sales, "trading.note.new") }).click();
  await page.getByLabel(textOf(sales, "trading.field.from_location")).selectOption(FED_WAREHOUSE);
  // Each line leaves from the first lot in FEFO order there, once the warehouse's stock is read.
  await expect(page.getByLabel(textOf(sales, "trading.column.batch"))).toHaveCount(2);
  for (const batch of await page.getByLabel(textOf(sales, "trading.column.batch")).all()) {
    await expect(batch).not.toHaveValue("");
  }
  await page.getByRole("button", { name: textOf(sales, "trading.note.create") }).click();
  await expect(page).toHaveURL(/\/trading\/delivery-notes\/[0-9a-f-]{36}$/);
  await page.getByRole("button", { name: textOf(sales, "trading.note.issue") }).click();
  await expect(page.getByText(textOf(sales, "trading.note.reserved"))).toBeVisible();
  const notePath = new URL(page.url()).pathname;
  await page.context().close();

  // 7. Dispatch: fed-stores, at the Federation warehouse.
  const stores = DEMO.fedStores;
  page = await as(stores, notePath);
  await page.getByLabel(textOf(stores, "trading.field.vehicle")).last().fill("NB-4521");
  await page.getByLabel(textOf(stores, "trading.field.driver")).last().fill("Sunil Perera");
  await page.getByRole("button", { name: textOf(stores, "trading.note.dispatch") }).click();
  await expect(page.getByText(textOf(stores, "trading.note.in_transit"))).toBeVisible();
  await page.context().close();

  // 8. GRN: d101-stores counts at Kurunegala, two bags of dhal short, and confirms.
  const receiver = DEMO.d101Stores;
  page = await as(receiver, notePath);
  await page.getByRole("link", { name: textOf(receiver, "trading.grn.new") }).click();
  const received = page.getByLabel(textOf(receiver, "trading.column.received"));
  await expect(received).toHaveCount(2);
  // The seller's batches fill the batch fields once read (M2); count after that, as a person would.
  for (const batch of await page.getByLabel(textOf(receiver, "trading.column.batch")).all()) {
    await expect(batch).not.toHaveValue("");
  }
  await received.nth(1).fill("38");
  await expect(received.nth(1)).toHaveValue("38");
  await expect(page.getByText(textOf(receiver, "trading.grn.short"), { exact: true })).toBeVisible();
  await page.getByRole("button", { name: textOf(receiver, "trading.grn.capture") }).click();
  await expect(page).toHaveURL(/\/trading\/grns\/[0-9a-f-]{36}$/);
  await page.getByRole("button", { name: textOf(receiver, "trading.grn.confirm") }).click();
  await expect(page.getByText(textOf(receiver, "trading.grn.status.CONFIRMED"), { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: textOf(receiver, "trading.grn.stock_moved") })).toBeVisible();
  await expect(page.getByRole("link", { name: textOf(receiver, "trading.stock.link") })).toBeVisible();
  const grnPath = new URL(page.url()).pathname;
  await page.context().close();

  // 9. Invoice: fed-accounts issues it from the confirmed GRN, at the received quantities.
  const accounts = DEMO.fedAccounts;
  page = await as(accounts, grnPath);
  await page.getByRole("button", { name: textOf(accounts, "trading.invoice.issue") }).click();
  await expect(page).toHaveURL(/\/trading\/invoices\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { name: textOf(accounts, "trading.invoice.title") })).toBeVisible();
  await expect(page.getByRole("row")).toHaveCount(3); // the header and the two lines
  await expect(page.getByRole("link", { name: textOf(accounts, "trading.note.open") })).toBeVisible();
  await expect(page.getByRole("button", { name: textOf(accounts, "trading.invoice.print") })).toBeVisible();
  const invoicePath = new URL(page.url()).pathname;
  await page.context().close();

  // d101-accounts reads the same invoice; the printed copy is the seller's.
  const buyerAccounts = DEMO.d101Accounts;
  page = await as(buyerAccounts, invoicePath);
  await expect(page.getByRole("heading", { name: textOf(buyerAccounts, "trading.invoice.title") })).toBeVisible();
  await expect(page.getByRole("button", { name: textOf(buyerAccounts, "trading.invoice.print") })).toHaveCount(0);
  await page.context().close();
});
