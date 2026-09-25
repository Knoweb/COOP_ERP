// The society register through the browser, as the Federation officer: register a society
// from the form, land on its card in ONBOARDING with Activate disabled and the reason beside
// it, find it in the register, and be refused by the SERVER when the same code is registered
// again, with the server's own words.
//
// The wording of that refusal is not written here: it is read from the backend's English
// catalogue, the file the server itself answers from.

import { expect, test } from "@playwright/test";
import backendEnglish from "../../backend/app/src/main/resources/i18n/en.json" with { type: "json" };
import { FED_ADMIN, FED_OFFICER, openSignedIn, textOf } from "./support/stack";

// The register is insert-only and the stack is not reset between runs, so every run registers
// a code nobody has registered before (12 characters at most).
const uniqueCode = () => `E${Date.now().toString(36).toUpperCase().slice(-9)}`;

test("a registered society lands on its card, is found in the register, and its code cannot be used twice", async ({ page }) => {
  await openSignedIn(page, FED_OFFICER, "/party/societies/new");

  const code = uniqueCode();
  const name = `E2E society ${code}`;
  await page.getByLabel(textOf(FED_OFFICER, "party.field.code")).fill(code);
  await page.getByLabel(textOf(FED_OFFICER, "party.field.name_en")).fill(name);
  await page.getByLabel(textOf(FED_OFFICER, "party.field.district")).fill("Kandy");
  await page.getByRole("button", { name: textOf(FED_OFFICER, "party.new.submit") }).click();

  // The card: header with code and name, ONBOARDING chip, Activate disabled with its reason.
  await expect(page).toHaveURL(/\/party\/societies\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(name);
  await expect(page.getByText(textOf(FED_OFFICER, "party.status.ONBOARDING"))).toBeVisible();
  const activate = page.getByRole("button", { name: textOf(FED_OFFICER, "party.card.activate") });
  await expect(activate).toBeDisabled();
  await expect(page.getByText(textOf(FED_OFFICER, "party.card.activate.no_officer"))).toBeVisible();

  // The register: the new row is there, and the search box narrows to it.
  await page.getByRole("link", { name: textOf(FED_OFFICER, "party.back_to_register") }).click();
  await page.getByLabel(textOf(FED_OFFICER, "party.filter.search")).fill(code);
  await expect(page.getByRole("link", { name: code })).toBeVisible();

  // The same code again is refused by the handler (422 m1.entity.code_duplicate) and the
  // screen shows the title of the problem document as it arrived.
  await page.getByRole("link", { name: textOf(FED_OFFICER, "party.register.new") }).click();
  await page.getByLabel(textOf(FED_OFFICER, "party.field.code")).fill(code);
  await page.getByLabel(textOf(FED_OFFICER, "party.field.name_en")).fill(name);
  await page.getByRole("button", { name: textOf(FED_OFFICER, "party.new.submit") }).click();
  await expect(page.getByRole("alert")).toHaveText(backendEnglish["m1.entity.code_duplicate"]);
});

test("the Federation view sees the register but is refused a registration by the server", async ({ page }) => {
  await openSignedIn(page, FED_ADMIN, "/party/societies/new");

  await page.getByLabel(textOf(FED_ADMIN, "party.field.code")).fill(uniqueCode());
  await page.getByLabel(textOf(FED_ADMIN, "party.field.name_en")).fill("Not the Federation itself");
  await page.getByRole("button", { name: textOf(FED_ADMIN, "party.new.submit") }).click();

  // fed-admin's scope is FEDERATION_VIEW, a read-only class that resolves to no permission
  // (K-03b), and the stack enforces permissions (K-02): the kernel refuses before the handler's
  // own guard ("only the Federation") is reached, and the screen shows why.
  await expect(page.getByRole("alert")).toHaveText(backendEnglish["permission.denied"]);
});
