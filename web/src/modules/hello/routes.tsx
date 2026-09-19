// The routes of the hello module. router.tsx spreads this array; a module never edits the
// shell to add a screen. When the shell's ModuleDefinition lands (S0-07) this file also
// exports the navigation items and the permissions each route needs.

import type { RouteObject } from "react-router-dom";
import { HelloPage } from "./HelloPage";

export const helloRoutes: RouteObject[] = [
  {
    path: "hello",
    element: <HelloPage />
  }
];
