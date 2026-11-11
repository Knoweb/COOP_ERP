import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PnlSelect } from "./PnlSelect";

describe("PnlSelect", () => {
  afterEach(cleanup);

  it("opens the custom listbox, shows active indicator bar and checkmark, and selects an option", () => {
    const handleChange = vi.fn();
    const { container } = render(
      <PnlSelect
        id="pnl-center-filter"
        aria-label="Profit Center"
        value=""
        onChange={handleChange}
        options={[
          { value: "", label: "All Profit Centers" },
          { value: "pc-1", label: "AG-01 — Polgolla Agency A" }
        ]}
      />
    );

    const trigger = screen.getByRole("button", { name: "All Profit Centers" });
    fireEvent.click(trigger);

    const listbox = screen.getByRole("listbox");
    expect(listbox).toBeDefined();
    expect(container.querySelector(".custom-dropdown__item-bar")).not.toBeNull();
    expect(container.querySelector(".custom-dropdown__checkmark")).not.toBeNull();

    fireEvent.click(within(listbox).getByRole("option", { name: "AG-01 — Polgolla Agency A" }));
    expect(handleChange).toHaveBeenCalledWith("pc-1");
  });

  it("supports portaling and closes on Escape", () => {
    const handleChange = vi.fn();
    render(
      <PnlSelect
        id="pnl-category-modal"
        aria-label="Category"
        value="AGENCY"
        onChange={handleChange}
        portal
        options={[
          { value: "AGENCY", label: "Agency" },
          { value: "PACKING", label: "Packing Operation" }
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Agency" }));
    const listbox = screen.getByRole("listbox");
    expect(listbox.className).toContain("pnl-dropdown-portal");

    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
  });
});

