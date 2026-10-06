// The society's credit book through the browser (M7, back office): the office clerk of Kuliyapitiya
// MPCS registers a member, opens the member's credit account, records a repayment at the office,
// and the statement shows it with its CPR number. Needs the demo data (`make demo-data`).
//
// The stack is not reset between runs, so every run registers a new member under a made-up phone
// and NIC number of its own (070 9xx xxxx, 1909xxxxxxxx: plainly not real).

import { expect, test } from "@playwright/test";
import { DEMO, openSignedIn, textOf } from "./support/stack";

test("register a member, open the account, record a repayment, and the statement shows it", async ({ page }) => {
  test.setTimeout(120_000);
  const user = DEMO.m101Office;
  const run = String(Date.now() % 1_000_000).padStart(6, "0");
  const name = `E2E Member ${run}`;

  await openSignedIn(page, user, "/customers");
  await page.getByLabel(textOf(user, "customers.field.name"), { exact: true }).fill(name);
  await page.getByLabel(textOf(user, "customers.field.phone"), { exact: true }).fill(`0709${run}`);
  await page.getByLabel(textOf(user, "customers.field.language"), { exact: true }).selectOption("en");
  await page.getByLabel(textOf(user, "customers.consent.credit"), { exact: true }).check();
  await page.getByRole("button", { name: textOf(user, "customers.register.submit"), exact: true }).click();

  // The card of the new member, with no account yet: open one with a limit and the NIC.
  await expect(page).toHaveURL(/\/customers\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 1, name: new RegExp(name) })).toBeVisible();
  await page.getByLabel(textOf(user, "customers.account.limit"), { exact: true }).fill("10000");
  await page.getByLabel(textOf(user, "customers.account.nic"), { exact: true }).fill(`1909${run}00`);
  await page.getByRole("button", { name: textOf(user, "customers.account.open.submit"), exact: true }).click();

  // The account is open: record a repayment at the office.
  const amount = page.getByLabel(textOf(user, "customers.payment.amount"), { exact: true });
  await expect(amount).toBeVisible();
  await amount.fill("1500");
  await page.getByLabel(textOf(user, "customers.payment.method"), { exact: true }).selectOption("CASH");
  await page.getByRole("button", { name: textOf(user, "customers.payment.record"), exact: true }).click();
  const recorded = page.getByRole("status").filter({ hasText: /-CPR-/ });
  await expect(recorded).toContainText(/-CPR-\d{7}/);
  const cpr = ((await recorded.textContent()) ?? "").match(/[A-Z0-9]+-CPR-\d{7}/)?.[0] ?? "";

  // The statement shows the repayment with its receipt number.
  await page.getByRole("link", { name: textOf(user, "customers.statement.open"), exact: true }).click();
  await expect(page).toHaveURL(/\/statement$/);
  const row = page.locator("tbody tr").filter({ hasText: cpr });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText(textOf(user, "customers.statement.kind.PAYMENT"));
});
