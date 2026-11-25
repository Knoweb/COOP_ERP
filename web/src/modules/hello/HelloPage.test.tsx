import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import helloMessages from "./hello.messages.json";
import { HelloPage } from "./HelloPage";
import type { Greeting, RegisterGreetingRequest } from "./helloApi";

let allowedPermissions = new Set<string>();
vi.mock("../../shell/auth/permissions", () => ({
  useHasPermission: (permission: string) => allowedPermissions.has(permission)
}));

const listGreetingsMock = vi.fn<() => Promise<Greeting[]>>();
const registerGreetingMock = vi.fn<(req: RegisterGreetingRequest, key: string) => Promise<Greeting>>();
vi.mock("./helloApi", () => ({
  useHelloApi: () => ({
    listGreetings: listGreetingsMock,
    registerGreeting: registerGreetingMock
  })
}));

function renderPage() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false }
    }
  });
  return render(
    <IntlProvider locale="en" messages={helloMessages.en}>
      <QueryClientProvider client={client}>
        <HelloPage />
      </QueryClientProvider>
    </IntlProvider>
  );
}

describe("HelloPage", () => {
  beforeEach(() => {
    allowedPermissions = new Set();
    listGreetingsMock.mockResolvedValue([
      {
        id: "0190f000-0000-7000-8000-000000000001",
        textEn: "Welcome from the Federation",
        textSi: null,
        textTa: null,
        status: "REGISTERED",
        createdAt: "2026-10-14T16:13:00Z"
      }
    ]);
    registerGreetingMock.mockResolvedValue({
      id: "0190f000-0000-7000-8000-000000000002",
      textEn: "Welcome from the society",
      textSi: null,
      textTa: null,
      status: "REGISTERED",
      createdAt: "2026-10-14T16:14:00Z"
    });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("renders read-only notice and existing entries when user cannot register", async () => {
    renderPage();

    expect(screen.getByRole("heading", { level: 1, name: helloMessages.en["hello.title"] })).toBeTruthy();
    expect(screen.getByRole("heading", { level: 2, name: helloMessages.en["hello.list.title"] })).toBeTruthy();
    expect(screen.getByRole("note").textContent).toBe(helloMessages.en["hello.read_only"]);
    expect(screen.queryByRole("button", { name: helloMessages.en["hello.register"] })).toBeNull();

    await waitFor(() => {
      expect(screen.getByText("Welcome from the Federation")).toBeTruthy();
    });
  });

  it("renders the registration form when permitted and submits entries cleanly", async () => {
    allowedPermissions = new Set(["hello.greeting.register"]);
    renderPage();

    expect(screen.queryByRole("note")).toBeNull();

    const inputEn = screen.getByLabelText(helloMessages.en["hello.field.text_en"]);
    const submitBtn = screen.getByRole("button", { name: helloMessages.en["hello.register"] }) as HTMLButtonElement;
    expect(submitBtn.disabled).toBe(true);

    fireEvent.change(inputEn, { target: { value: "Welcome from the society" } });
    expect(submitBtn.disabled).toBe(false);

    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(registerGreetingMock).toHaveBeenCalledTimes(1);
    });
    expect(screen.getByRole("status").textContent).toBe(helloMessages.en["hello.registered"]);
  });
});
