import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { EstimatorSelect } from "./EstimatorSelect";

describe("EstimatorSelect", () => {
  afterEach(cleanup);

  it("opens the custom listbox, renders indicator bar and checkmark, and selects an option", () => {
    const handleChange = vi.fn();
    const { container } = render(
      <EstimatorSelect
        id="location-select"
        aria-label="Shop / Location"
        value=""
        onChange={handleChange}
        placeholder="Select Shop / Location"
        options={[{ value: "loc-1", label: "LOC-01 Polgolla Main Shop" }]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Select Shop / Location" }));

    const listbox = screen.getByRole("listbox");
    expect(listbox).toBeDefined();
    expect(container.querySelector(".custom-dropdown__item-bar")).not.toBeNull();
    expect(container.querySelector(".custom-dropdown__checkmark")).not.toBeNull();

    fireEvent.click(within(listbox).getByRole("option", { name: "LOC-01 Polgolla Main Shop" }));
    expect(handleChange).toHaveBeenCalledWith("loc-1");
  });

  it("supports portaling and closes on Escape", () => {
    const handleChange = vi.fn();
    render(
      <EstimatorSelect
        id="pricing-basis-select"
        aria-label="Pricing Basis"
        value="RETAIL"
        onChange={handleChange}
        portal
        options={[
          { value: "RETAIL", label: "Retail Price" },
          { value: "COMMERCIAL", label: "Commercial Price" }
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Retail Price" }));
    const listbox = screen.getByRole("listbox");
    expect(listbox.className).toContain("sales-estimator-dropdown-portal");

    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
  });
});

