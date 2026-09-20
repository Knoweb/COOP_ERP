// What the hello module tells the shell about itself: its screens, its navigation entries and
// the permissions that open them (17A section 7). It is listed once in ../registry.ts; the
// shell builds the router and the navigation from there, so a module never edits the shell.

import type { ModuleDefinition } from "../../shell/modules/ModuleDefinition";
import { HelloPage } from "./HelloPage";

export const helloModule: ModuleDefinition = {
  id: "hello",

  routes: [
    {
      path: "hello",
      element: <HelloPage />
    }
  ],

  // The label is a message id of hello.messages.json, never literal text.
  navItems: [{ labelId: "hello.nav", to: "/hello" }],

  // The same codes as the x-permission of openapi/hello.yaml. A user with at least one of
  // them sees the module; the page itself hides what the user may not do (HelloPage.tsx).
  requiredPermissions: ["hello.greeting.read", "hello.greeting.register"]
};
