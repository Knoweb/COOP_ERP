import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { UomDetail, DefineUomRequest, UpdateUomRequest } from "./catalogueApi";
import { UnitsOfMeasurePage } from "./UnitsOfMeasurePage";

const state = {
  permissions: new Set<string>(["cat.uom.manage"])
};

const mockUoms: UomDetail[] = [
  {
    uomCode: "KG",
    nameEn: "Kilogram",
    nameSi: "කිලෝග්‍රෑම්",
    nameTa: "கிலோகிராம்",
    description: "Standard metric weight",
    weight: true,
    status: "ACTIVE"
  },
  {
    uomCode: "EA",
    nameEn: "Each",
    nameSi: "එකක්",
    nameTa: "ஒன்று",
    description: "Single discrete unit",
    weight: false,
    status: "ACTIVE"
  },
  {
    uomCode: "ROLL",
    nameEn: "Roll",
    nameSi: "රෝලය",
    nameTa: "சுருள்",
    description: "Rolled packaging",
    weight: false,
    status: "INACTIVE"
  }
];

const api = {
  listUoms: vi.fn(async (includeInactive?: boolean) => {
    if (includeInactive) return mockUoms;
    return mockUoms.filter((u) => u.status === "ACTIVE");
  }),
  defineUom: vi.fn(async (body: DefineUomRequest) => ({
    uomCode: body.uomCode,
    nameEn: body.nameEn,
    nameSi: body.nameSi,
    nameTa: body.nameTa,
    description: body.description,
    weight: body.weight,
    status: "ACTIVE" as const
  })),
  updateUom: vi.fn(async (code: string, body: UpdateUomRequest) => ({
    uomCode: code,
    nameEn: body.nameEn,
    nameSi: body.nameSi,
    nameTa: body.nameTa,
    description: body.description,
    weight: body.weight,
    status: body.status ?? ("ACTIVE" as const)
  }))
};

vi.mock("./catalogueApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./catalogueApi")>();
  return {
    ...actual,
    useCatalogueApi: () => api
  };
});

vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (code: string) => state.permissions.has(code)
}));

function renderUnitsOfMeasurePage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } }
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={["/catalogue/uom"]}>
          <Routes>
            <Route path="/catalogue/uom" element={<UnitsOfMeasurePage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("UnitsOfMeasurePage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.permissions = new Set(["cat.uom.manage"]);
  });

  afterEach(() => {
    cleanup();
  });

  it("renders active units of measure with code, names, weight badge, and status", async () => {
    renderUnitsOfMeasurePage();

    expect(screen.getByText("Units of Measure")).toBeTruthy();
    await waitFor(() => {
      expect(screen.getByText("KG")).toBeTruthy();
      expect(screen.getByText("Kilogram")).toBeTruthy();
      expect(screen.getByText("කිලෝග්‍රෑම්")).toBeTruthy();
      expect(screen.getByText("கிலோகிராம்")).toBeTruthy();
      expect(screen.getByText("EA")).toBeTruthy();
      expect(screen.getByText("Each")).toBeTruthy();
      expect(screen.queryByText("ROLL")).toBeNull();
    });
  });

  it("shows '+ New Unit' button only when user has cat.uom.manage", async () => {
    state.permissions = new Set();
    const { unmount } = renderUnitsOfMeasurePage();
    expect(screen.queryByText("+ New Unit")).toBeNull();

    unmount();
    state.permissions = new Set(["cat.uom.manage"]);
    renderUnitsOfMeasurePage();
    expect(screen.getByText("+ New Unit")).toBeTruthy();
  });

  it("includes inactive units when toggle checkbox is checked", async () => {
    renderUnitsOfMeasurePage();

    await waitFor(() => {
      expect(screen.getByText("KG")).toBeTruthy();
    });

    const toggle = screen.getByLabelText("Show inactive units");
    fireEvent.click(toggle);

    await waitFor(() => {
      expect(api.listUoms).toHaveBeenCalledWith(true);
      expect(screen.getByText("ROLL")).toBeTruthy();
      expect(screen.getByText("Roll")).toBeTruthy();
    });
  });

  it("defines a new unit of measure when form is submitted", async () => {
    renderUnitsOfMeasurePage();

    const newBtn = screen.getByText("+ New Unit");
    fireEvent.click(newBtn);

    expect(screen.getByText("Define Unit of Measure")).toBeTruthy();

    const codeInput = screen.getByLabelText(/UOM Code/i);
    const nameEnInput = screen.getByLabelText(/Name \(English\)/i);

    fireEvent.change(codeInput, { target: { value: "PACK" } });
    fireEvent.change(nameEnInput, { target: { value: "Pack" } });

    const saveBtn = screen.getByText("Save Unit");
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(api.defineUom).toHaveBeenCalledWith(
        expect.objectContaining({
          uomCode: "PACK",
          nameEn: "Pack",
          weight: false
        }),
        expect.any(String)
      );
    });
  });

  it("updates an existing unit of measure when edit form is submitted", async () => {
    renderUnitsOfMeasurePage();

    await waitFor(() => {
      expect(screen.getByText("KG")).toBeTruthy();
    });

    const editButtons = screen.getAllByRole("button", { name: "Edit" });
    fireEvent.click(editButtons[0]);

    expect(
      screen.getByText((_, el) => el?.tagName === "H3" && el?.textContent?.includes("KG") === true)
    ).toBeTruthy();

    const nameInputs = screen.getAllByDisplayValue("Kilogram");
    fireEvent.change(nameInputs[0], { target: { value: "Kilograms (Metric)" } });

    const updateBtn = screen.getByText("Update Unit");
    fireEvent.click(updateBtn);

    await waitFor(() => {
      expect(api.updateUom).toHaveBeenCalledWith(
        "KG",
        expect.objectContaining({
          nameEn: "Kilograms (Metric)"
        }),
        expect.any(String)
      );
    });
  });
});
