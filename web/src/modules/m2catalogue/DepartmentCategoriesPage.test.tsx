import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import type { DepartmentCategory, DefineDepartmentCategoryRequest, UpdateDepartmentCategoryRequest } from "./catalogueApi";
import { DepartmentCategoriesPage } from "./DepartmentCategoriesPage";

const state = {
  permissions: new Set<string>(["cat.department.manage"])
};

const mockDepartments: DepartmentCategory[] = [
  {
    departmentId: "0192e000-0000-7000-8000-000000000010",
    code: "GROCERY",
    nameEn: "Grocery",
    nameSi: "වියලි ආහාර",
    nameTa: "மளிகை",
    description: "Dry food items and staples",
    displayOrder: 10,
    status: "ACTIVE"
  },
  {
    departmentId: "0192e000-0000-7000-8000-000000000020",
    code: "BEVERAGES",
    nameEn: "Beverages",
    nameSi: "බීම වර්ග",
    nameTa: "பானங்கள்",
    description: "Cold and hot drinks",
    displayOrder: 20,
    status: "INACTIVE"
  }
];

const api = {
  listDepartmentCategories: vi.fn(async () => mockDepartments),
  defineDepartmentCategory: vi.fn(async (body: DefineDepartmentCategoryRequest) => ({
    departmentId: "0192e000-0000-7000-8000-000000000030",
    code: body.code,
    nameEn: body.nameEn,
    nameSi: body.nameSi,
    nameTa: body.nameTa,
    description: body.description,
    displayOrder: body.displayOrder,
    status: "ACTIVE" as const
  })),
  updateDepartmentCategory: vi.fn(async (id: string, body: UpdateDepartmentCategoryRequest) => ({
    departmentId: id,
    code: "GROCERY",
    nameEn: body.nameEn,
    nameSi: body.nameSi,
    nameTa: body.nameTa,
    description: body.description,
    displayOrder: body.displayOrder,
    status: body.status
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

function renderDepartmentCategoriesPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } }
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <IntlProvider locale="en" messages={messages.en}>
        <MemoryRouter initialEntries={["/catalogue/departments"]}>
          <Routes>
            <Route path="/catalogue/departments" element={<DepartmentCategoriesPage />} />
          </Routes>
        </MemoryRouter>
      </IntlProvider>
    </QueryClientProvider>
  );
}

describe("DepartmentCategoriesPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.permissions = new Set(["cat.department.manage"]);
  });

  afterEach(() => {
    cleanup();
  });

  it("renders existing department categories with code, names and status", async () => {
    renderDepartmentCategoriesPage();

    expect(screen.getByText("Department Categories")).toBeTruthy();
    await waitFor(() => {
      expect(screen.getByText("GROCERY")).toBeTruthy();
      expect(screen.getByText("Grocery")).toBeTruthy();
      expect(screen.getByText("වියලි ආහාර")).toBeTruthy();
      expect(screen.getByText("மளிகை")).toBeTruthy();
      expect(screen.getByText("BEVERAGES")).toBeTruthy();
      expect(screen.getByText("Beverages")).toBeTruthy();
    });
  });

  it("shows '+ New Department' button only when user has cat.department.manage", async () => {
    state.permissions = new Set();
    const { unmount } = renderDepartmentCategoriesPage();
    expect(screen.queryByText("+ New Department")).toBeNull();

    unmount();
    state.permissions = new Set(["cat.department.manage"]);
    renderDepartmentCategoriesPage();
    expect(screen.getByText("+ New Department")).toBeTruthy();
  });

  it("defines a new department category when form is submitted", async () => {
    renderDepartmentCategoriesPage();

    const newBtn = screen.getByText("+ New Department");
    fireEvent.click(newBtn);

    expect(screen.getByText("Define Department Category")).toBeTruthy();

    const codeInput = screen.getByLabelText(/Code/i);
    const nameEnInput = screen.getByLabelText(/Name \(English\)/i);

    fireEvent.change(codeInput, { target: { value: "PRODUCE" } });
    fireEvent.change(nameEnInput, { target: { value: "Fresh Produce" } });

    const saveBtn = screen.getByText("Save Department");
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(api.defineDepartmentCategory).toHaveBeenCalledWith(
        expect.objectContaining({
          code: "PRODUCE",
          nameEn: "Fresh Produce"
        }),
        expect.any(String)
      );
    });
  });

  it("updates an existing department category when edit form is submitted", async () => {
    renderDepartmentCategoriesPage();

    await waitFor(() => {
      expect(screen.getByText("GROCERY")).toBeTruthy();
    });

    const editButtons = screen.getAllByRole("button", { name: "Edit" });
    fireEvent.click(editButtons[0]);

    expect(
      screen.getByText((_, el) => el?.tagName === "H3" && el?.textContent?.includes("GROCERY") === true)
    ).toBeTruthy();

    const nameInputs = screen.getAllByDisplayValue("Grocery");
    fireEvent.change(nameInputs[0], { target: { value: "Groceries & Staples" } });

    const updateBtn = screen.getByText("Update Department");
    fireEvent.click(updateBtn);

    await waitFor(() => {
      expect(api.updateDepartmentCategory).toHaveBeenCalledWith(
        "0192e000-0000-7000-8000-000000000010",
        expect.objectContaining({
          nameEn: "Groceries & Staples"
        }),
        expect.any(String)
      );
    });
  });
});
