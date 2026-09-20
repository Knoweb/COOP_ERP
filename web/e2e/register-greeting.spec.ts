// Registering a greeting, the template command of the whole system, through the browser: the
// form posts, the list shows the new row, and the second attempt with the same text is refused
// by the SERVER with the server's own message, in the user's language.
//
// The wording of that refusal is not written here: it is read from the backend's Sinhala
// catalogue, the file the server itself answers from. A translation that changes there changes
// this assertion with it.

import { expect, test } from "@playwright/test";
import backendSinhala from "../../backend/app/src/main/resources/i18n/si.json" with { type: "json" };
import { MPCS_ADMIN, openSignedIn, textOf } from "./support/stack";

// The greeting table is insert-only and the stack is not reset between runs, so every run
// registers a text nobody has registered before. That is what makes `make e2e` repeatable
// against a stack these tests have already run against.
const uniqueText = () => `E2E greeting ${crypto.randomUUID()}`;

test("a registered greeting appears in the list, and the same text again is refused in Sinhala", async ({ page }) => {
  await openSignedIn(page, MPCS_ADMIN, "/hello");

  const text = uniqueText();
  const englishField = page.getByLabel(textOf(MPCS_ADMIN, "hello.field.text_en"));
  const registerButton = page.getByRole("button", { name: textOf(MPCS_ADMIN, "hello.register") });

  await englishField.fill(text);
  await registerButton.click();

  await expect(page.getByRole("status")).toHaveText(textOf(MPCS_ADMIN, "hello.registered"));
  await expect(page.getByText(text)).toBeVisible();

  // Again, with the same text. The guard of the handler refuses it (422
  // hello.greeting.duplicate) and the screen shows the title of the problem document as it
  // arrived: the client never invents its own wording for a business rule.
  await englishField.fill(text);
  await registerButton.click();

  await expect(page.getByRole("alert")).toHaveText(backendSinhala["hello.greeting.duplicate"]);
});
