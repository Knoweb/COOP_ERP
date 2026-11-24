import { render, screen } from "@testing-library/react";
import { BrowserRouter } from "react-router-dom";
import { IntlProvider } from "react-intl";
import { describe, expect, it, vi } from "vitest";
import { ShellHome } from "./ShellHome";
import { PermissionsContext } from "../auth/PermissionsContext";
import { messages } from "../i18n/messages";
import { MODULES } from "../../modules/registry";

const mockSession = {
  displayName: "Nirmala Perera",
  userId: "user-1",
  entityId: "ent-1",
  policyClass: "FEDERATION_VIEW" as const,
  language: "en" as const,
  accessToken: "token",
  signOut: () => {}
};

const mockScope = {
  entityId: "ent-1",
  entityName: "Cooperative Federation (development)",
  entityShortId: "coopfed-dev",
  locationId: null,
  policyClass: "FEDERATION_VIEW" as const,
  seesData: true
};

vi.mock("../auth/session", () => ({
  useSession: () => mockSession
}));

vi.mock("../scope/useScope", () => ({
  useScope: () => mockScope
}));

describe("ShellHome portal landing page", () => {
  it("renders the welcome banner and quick access modules", () => {
    const mockPermissions = {
      permissions: ["pty.society.read", "cat.sku.read", "inv.stock.read"],
      failed: false
    };

    render(
      <IntlProvider locale="en" messages={messages.en}>
        <BrowserRouter>
          <PermissionsContext.Provider value={mockPermissions as any}>
            <ShellHome modules={MODULES} />
          </PermissionsContext.Provider>
        </BrowserRouter>
      </IntlProvider>
    );

    // Welcome title contains user name
    const heading = screen.getByRole("heading", { level: 1 });
    expect(heading.textContent).toContain("Nirmala Perera");
    // Entity name is displayed
    expect(screen.getByText("Cooperative Federation (development)")).toBeDefined();
    // System status / badge is displayed
    expect(screen.getByText(/COOPFED ERP/i)).toBeDefined();
    // Quick access section header is present
    expect(screen.getByRole("heading", { level: 2 })).toBeDefined();
  });
});
