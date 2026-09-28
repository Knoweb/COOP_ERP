import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProblem } from "../../shell/api/client";
import { messages } from "../../shell/i18n/messages";
import type { Location, Role, User } from "./adminApi";
import { UserCardPage } from "./UserCardPage";

const USER = "0190f0de-0000-7000-8000-00000000aa01";
const ROLE = "0190f0de-0000-7000-8000-000000000305";
const WAREHOUSE = "0190f0de-0000-7000-8000-000000000101";

const pending: User = {
  userId: USER,
  homeEntityId: "e1",
  username: "new-clerk",
  displayName: "Nadeesha Peiris",
  language: "en",
  userKind: "BACK_OFFICE",
  status: "PENDING",
  pinSet: false
};
const role: Role = {
  roleId: ROLE,
  ownerEntityId: "e1",
  nameEn: "Demo: accounts",
  template: false,
  roleClass: "OWN",
  templateUpdated: false,
  version: 1,
  status: "ACTIVE",
  permissions: [{ permissionCode: "bil.invoice.issue" }]
};
const template: Role = { ...role, roleId: "t1", ownerEntityId: null, nameEn: "Template: cashier", template: true };
const warehouse = { locationId: WAREHOUSE, locationCode: "W01", nameEn: "Federation warehouse" } as Location;

const api = {
  getUser: vi.fn(async () => pending),
  resetCredential: vi.fn(async () => ({ userId: USER, credential: "PASSWORD", status: "ACTIVE", delivery: "RETURNED", temporaryPassword: "shown-once-in-test" })),
  deactivateUser: vi.fn(async () => undefined),
  listAssignments: vi.fn(async () => [] as unknown[]),
  listRoles: vi.fn(async () => [role, template]),
  listLocations: vi.fn(async () => [warehouse]),
  assignRole: vi.fn(async () => undefined),
  revokeRole: vi.fn(async () => undefined)
};

vi.mock("./adminApi", () => ({ useAdminApi: () => api }));
vi.mock("../../shell/auth/permissions", () => ({ useHasPermission: () => true }));

const text = (id: string) => (messages.en as Record<string, string>)[id];

function renderCard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={[`/party/users/${USER}`]}>
          <Routes>
            <Route path="/party/users/:userId" element={<UserCardPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("the user card", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(cleanup);

  it("issues the first password and shows it once", async () => {
    renderCard();
    fireEvent.click(await screen.findByRole("button", { name: text("party.user.password") }));
    expect(await screen.findByText("shown-once-in-test")).toBeTruthy();
    expect(api.resetCredential).toHaveBeenCalledWith(USER, "PASSWORD", expect.any(String));
  });

  it("offers the entity's own roles, not the templates, and assigns one at a location", async () => {
    renderCard();
    const roleBox = await screen.findByLabelText(text("party.roles.role"));
    await waitFor(() => expect(screen.getByRole("option", { name: "Demo: accounts" })).toBeTruthy());
    expect(screen.queryByRole("option", { name: "Template: cashier" })).toBeNull();

    fireEvent.change(roleBox, { target: { value: ROLE } });
    await waitFor(() => expect(screen.getByRole("option", { name: "W01 Federation warehouse" })).toBeTruthy());
    fireEvent.change(screen.getByLabelText(text("party.roles.where")), { target: { value: WAREHOUSE } });
    fireEvent.click(screen.getByRole("button", { name: text("party.roles.assign") }));

    await waitFor(() =>
      expect(api.assignRole).toHaveBeenCalledWith({ userId: USER, roleId: ROLE, scopeLocationId: WAREHOUSE }, expect.any(String))
    );
    expect(await screen.findByText(text("party.roles.assigned"))).toBeTruthy();
  });

  it("shows a separation-of-duties refusal plainly, in the server's words", async () => {
    api.assignRole.mockRejectedValueOnce(
      new ApiProblem({ status: 422, code: "m1.assignment.sod_conflict", title: "With this role the user would hold two duties that one person may not hold together" })
    );
    renderCard();
    const roleBox = await screen.findByLabelText(text("party.roles.role"));
    await waitFor(() => expect(screen.getByRole("option", { name: "Demo: accounts" })).toBeTruthy());
    fireEvent.change(roleBox, { target: { value: ROLE } });
    fireEvent.click(screen.getByRole("button", { name: text("party.roles.assign") }));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain(text("party.roles.sod.title"));
    expect(alert.textContent).toContain("two duties that one person may not hold together");
  });

  it("offers nothing to a deactivated user", async () => {
    api.getUser.mockResolvedValueOnce({ ...pending, status: "DEACTIVATED" });
    renderCard();
    expect(await screen.findByText(text("party.user.status.DEACTIVATED"))).toBeTruthy();
    expect(screen.queryByRole("button", { name: text("party.user.deactivate") })).toBeNull();
    expect(screen.queryByRole("button", { name: text("party.roles.assign") })).toBeNull();
  });
});
