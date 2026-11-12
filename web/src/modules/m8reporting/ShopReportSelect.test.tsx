import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ShopReportSelect } from "./ShopReportSelect";

describe("ShopReportSelect", () => {
  afterEach(cleanup);

  it("opens the custom listbox, shows active indicator bar and checkmark, and selects a shop", () => {
    const onChange = vi.fn();
    render(
      <label htmlFor="shop-filter" className="shop-reports-field">
        <span className="shop-reports-field__label">Shop</span>
        <ShopReportSelect
          id="shop-filter"
          ariaLabel="Shop"
          value=""
          onChange={onChange}
          options={[
            { value: "", label: "All Permitted Shops" },
            { value: "s01", label: "S01 Development shop" },
            { value: "s02", label: "S02 Hettipola shop" }
          ]}
        />
      </label>
    );

    const trigger = screen.getByRole("button", { name: "All Permitted Shops" });
    expect(trigger.getAttribute("aria-expanded")).toBe("false");

    fireEvent.click(trigger);

    const listbox = screen.getByRole("listbox");
    expect(listbox.querySelectorAll(".custom-dropdown__item-bar")).toHaveLength(1);
    expect(listbox.querySelectorAll(".custom-dropdown__item-spacer")).toHaveLength(2);
    expect(listbox.querySelectorAll(".custom-dropdown__checkmark")).toHaveLength(1);

    fireEvent.click(within(listbox).getByRole("option", { name: "S01 Development shop" }));

    expect(onChange).toHaveBeenCalledWith("s01");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("renders into document.body when portal is enabled and closes on Escape", () => {
    const onChange = vi.fn();
    const { container } = render(
      <ShopReportSelect
        portal
        compact
        ariaLabel="Shop"
        value="s01"
        onChange={onChange}
        options={[
          { value: "s01", label: "S01 Development shop" },
          { value: "s02", label: "S02 Hettipola shop" }
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "S01 Development shop" }));

    const listbox = screen.getByRole("listbox");
    expect(listbox.className).toContain("shop-reports-dropdown-portal");
    expect(container.querySelector("ul[role='listbox']")).toBeNull();
    expect(document.body.contains(listbox)).toBe(true);

    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
  });
});

