import { createBrowserRouter, Outlet } from "react-router-dom";
import { UserMenu } from "./shell/auth/UserMenu";
import { ScopeBanner } from "./shell/scope/ScopeBanner";
import { helloRoutes } from "./modules/hello/routes";
// new-module:import (make new-module adds a line above this one; keep the comment)

const RootLayout = () => {
  return (
    <div>
      <header className="shell-header">
        <ScopeBanner />
        <UserMenu />
      </header>
      <Outlet />
    </div>
  );
};

export const router = createBrowserRouter([
  {
    path: "/",
    element: <RootLayout />,
    children: [
      // One line per module: the module owns its routes (17A section 7).
      ...helloRoutes,
      // new-module:entry (make new-module adds a line above this one; keep the comment)
    ]
  }
]);
