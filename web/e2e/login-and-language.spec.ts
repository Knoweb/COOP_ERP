// The done criterion of S0-07, as a test: "Login, banner, hello page in three languages"
// (17A section 7 and the plan). One run per development user, through a real browser and the
// real identity server.
//
// What it proves for each user: an anonymous visitor cannot see the back office; the password
// is typed on the identity server's page and nowhere else; after signing in the browser is back
// on the page that was ASKED FOR and not on the start page; and the screen is in the user's own
// language, which comes from the token, not from the browser (the browser is English here).

import { expect, test } from "@playwright/test";
import { ALL_DEV_USERS, openSignedIn, textOf } from "./support/stack";

for (const user of ALL_DEV_USERS) {
  test(`${user.username} signs in and reaches the hello screen, with the banner and the navigation in ${user.locale}`, async ({
    page
  }) => {
    await openSignedIn(page, user, "/hello");

    // The scope banner is on every page and says who is acting for which entity in which
    // policy class. The entity has no name before M1, so the banner is checked by the two
    // facts it can state: the user and the access class, both in the user's language.
    const banner = page.getByRole("region", { name: textOf(user, "shell.scope.label") });
    await expect(banner).toBeVisible();
    await expect(banner).toContainText(user.displayName);
    await expect(banner).toContainText(textOf(user, "shell.scope.access"));
    await expect(banner).toContainText(textOf(user, `shell.scope.class.${user.policyClass}`));

    // The navigation shows the module this user may open, with the module's own label.
    const navigation = page.getByRole("navigation", { name: textOf(user, "shell.nav.label") });
    await expect(navigation.getByRole("link", { name: textOf(user, "hello.nav") })).toBeVisible();

    // And the page itself. `hello.nav` and `hello.title` are the same word in all three
    // languages, which is why the heading is asked for by role and not by text.
    await expect(page.getByRole("heading", { level: 1, name: textOf(user, "hello.title") })).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: textOf(user, "hello.list.title") })).toBeVisible();
  });
}
