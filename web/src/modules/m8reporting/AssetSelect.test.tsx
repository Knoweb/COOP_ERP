import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AssetSelect } from "./AssetSelect";

describe("AssetSelect", () => {
  afterEach(cleanup);

  it("opens the custom listbox, renders active indicator bar and checkmark, and selects an option", () => {
    const handleChange = vi.fn();
    const { container } = render(
      <AssetSelect
        id="asset-type-filter"
        aria-label="Type"
        value=""
        onChange={handleChange}
        options={[
          { value: "", label: "All Asset Types" },
          { value: "EQUIPMENT", label: "Equipment" }
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "All Asset Types" }));

    const listbox = screen.getByRole("listbox");
    expect(listbox).toBeDefined();
    expect(container.querySelector(".custom-dropdown__item-bar")).not.toBeNull();
    expect(container.querySelector(".custom-dropdown__checkmark")).not.toBeNull();

    fireEvent.click(within(listbox).getByRole("option", { name: "Equipment" }));
    expect(handleChange).toHaveBeenCalledWith("EQUIPMENT");
  });

  it("supports portaling and closes on Escape", () => {
    const handleChange = vi.fn();
    render(
      <AssetSelect
        id="asset-type-modal"
        aria-label="Asset Type"
        value="EQUIPMENT"
        onChange={handleChange}
        portal
        options={[
          { value: "EQUIPMENT", label: "Equipment" },
          { value: "VEHICLE", label: "Vehicle" }
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Equipment" }));
    const listbox = screen.getByRole("listbox");
    expect(listbox.className).toContain("assets-dropdown-portal");

    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
  });
});

