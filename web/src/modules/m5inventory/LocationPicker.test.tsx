import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiProblem } from "../../shell/api/client";
import { messages } from "../../shell/i18n/messages";
import inventoryMessages from "./inventory.messages.json" with { type: "json" };
import { LocationPicker } from "./LocationPicker";
import type { Location } from "./inventoryApi";

/**
 * The picker of the Stock and Transfers screens (bug seen live 28 Sep 2026): a user who can see
 * the Stock tab but not list locations (missing prt.location.view) got an empty, silent select.
 * It must say so instead, and preselect a caller's one location.
 */

let locationsResult: () => Promise<Location[]>;

const api = { locations: vi.fn(() => locationsResult()) };

vi.mock("./inventoryApi", () => ({ useInventoryApi: () => api }));

const en = { ...messages.en, ...inventoryMessages.en } as Record<string, string>;

function renderPicker(onChange = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={en}>
        <LocationPicker value="" onChange={onChange} />
      </IntlProvider>
    </QueryClientProvider>
  );
  return onChange;
}

const location = (id: string, code: string): Location => ({
  locationId: id,
  ownerEntityId: "0190f000-0000-7000-8000-000000000001",
  locationCode: code,
  locationType: "SHOP",
  nameEn: `Location ${code}`,
  nameSi: null,
  nameTa: null,
  connectivitySpecMet: true,
  status: "ACTIVE"
});

describe("the location picker", () => {
  afterEach(cleanup);

  it("shows a refused message, not an empty select, when the list is refused", async () => {
    locationsResult = () =>
      Promise.reject(new ApiProblem({ title: "Refused", status: 403, code: "permission.denied" }));
    renderPicker();

    expect((await screen.findByRole("alert")).textContent).toContain("Refused");
    expect(screen.queryByRole("combobox")).toBeNull();
  });

  it("preselects the one location a caller holds", async () => {
    locationsResult = () => Promise.resolve([location("loc-1", "L1")]);
    const onChange = renderPicker();

    await waitFor(() => expect(onChange).toHaveBeenCalledWith("loc-1"));
  });

  it("leaves the choice to the caller when there is more than one location", async () => {
    locationsResult = () => Promise.resolve([location("loc-1", "L1"), location("loc-2", "L2")]);
    const onChange = renderPicker();

    await screen.findByRole("combobox");
    expect(onChange).not.toHaveBeenCalled();
  });
});
