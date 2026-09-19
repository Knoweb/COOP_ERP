import { createBrowserRouter, Outlet } from "react-router-dom";
import { ScopeBanner } from "./shell/scope/ScopeBanner";
import { helloRoutes } from "./modules/hello/routes";

const RootLayout = () => {
  return (
    <div>
      <ScopeBanner />
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
      ...helloRoutes
    ]
  }
]);
