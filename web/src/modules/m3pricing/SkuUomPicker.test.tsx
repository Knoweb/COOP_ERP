import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IntlProvider } from "react-intl";
import { afterEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import { SkuUomPicker } from "./SkuPicker";

const api = {
  getSku: vi.fn(async () => ({ baseUomCode: "EA" })),
  skuConversions: vi.fn(async () => [
    {
      uomCode: "CASE",
      factorToBase: 24,
      effectiveFrom: "2020-01-01"
    },
    {
      uomCode: "OLD",
      factorToBase: 12,
      effectiveFrom: "2000-01-01",
      effectiveTo: "2001-01-01"
    },
    {
      uomCode: "FUTURE",
      factorToBase: 10,
      effectiveFrom: "2999-01-01"
    }
  ])
};

vi.mock("./pricingApi", () => ({
  usePricingApi: () => api
}));

afterEach(cleanup);

function renderPicker(uomCode = "EA", onChange = vi.fn()) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } }
  });

  render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={messages.en}>
        <SkuUomPicker
          skuId="test-sku"
          uomCode={uomCode}
          onChange={onChange}
        />
      </IntlProvider>
    </QueryClientProvider>
  );

  return onChange;
}

describe("TRADE pricing unit selection", () => {
  it("offers base and effective converted units", async () => {
    const changed = renderPicker();

    expect(await screen.findByRole("option", { name: "CASE" }))
      .toBeTruthy();

    expect(screen.getByRole("option", { name: "EA" }))
      .toBeTruthy();

    expect(screen.queryByRole("option", { name: "OLD" }))
      .toBeNull();

    expect(screen.queryByRole("option", { name: "FUTURE" }))
      .toBeNull();

    fireEvent.change(screen.getByRole("combobox"), {
      target: { value: "CASE" }
    });

    expect(changed).toHaveBeenCalledWith("CASE");
  });

  it("keeps an existing expired unit visible for correction", async () => {
    renderPicker("OLD");

    expect(await screen.findByRole("option", { name: "EA" }))
      .toBeTruthy();

    expect(screen.getByRole("combobox"))
      .toHaveProperty("value", "OLD");
  });
});
