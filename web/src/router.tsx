import { createBrowserRouter, Outlet } from "react-router-dom";
import { ScopeBanner } from "./shell/scope/ScopeBanner";
import { HelloPage } from "./modules/hello/HelloPage";

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
      {
        path: "hello",
        element: <HelloPage />
      }
    ]
  }
]);
