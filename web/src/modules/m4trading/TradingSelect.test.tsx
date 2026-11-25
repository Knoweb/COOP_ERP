import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TradingSelect } from "./TradingSelect";

describe("TradingSelect", () => {
  afterEach(cleanup);

  it("opens the custom listbox, renders indicator bar and checkmark, and updates selection", () => {
    const onChange = vi.fn();
    render(
      <label className="trading-form-field">
        <span>Order from</span>
        <TradingSelect
          id="test-seller-select"
          ariaLabel="Order from"
          value="D101"
          onChange={onChange}
          options={[
            { value: "D101", label: "D101 Wayamba Cooperative Distributors" },
            { value: "D102", label: "D102 Southern Distributors" },
            { value: "D103", label: "D103 Central Cooperative" }
          ]}
        />
      </label>
    );

    const trigger = screen.getByRole("button", { name: "D101 Wayamba Cooperative Distributors" });
    fireEvent.click(trigger);

    const listbox = screen.getByRole("listbox");
    expect(trigger.getAttribute("aria-expanded")).toBe("true");
    expect(listbox.querySelectorAll(".custom-dropdown__item-bar")).toHaveLength(1);
    expect(listbox.querySelectorAll(".custom-dropdown__item-spacer")).toHaveLength(2);
    expect(listbox.querySelectorAll(".custom-dropdown__checkmark")).toHaveLength(1);

    fireEvent.click(within(listbox).getByRole("option", { name: "D102 Southern Distributors" }));

    expect(onChange).toHaveBeenCalledWith("D102");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("navigates options with arrow keys, selects with Enter, and closes with Escape", () => {
    const onChange = vi.fn();
    render(
      <TradingSelect
        ariaLabel="Deliver to"
        value="S01"
        onChange={onChange}
        options={[
          { value: "S01", label: "S01 Kuliyapitiya town shop" },
          { value: "S02", label: "S02 Bingiriya branch" }
        ]}
      />
    );

    const trigger = screen.getByRole("button", { name: "S01 Kuliyapitiya town shop" });
    fireEvent.keyDown(trigger, { key: "ArrowDown" });

    const listbox = screen.getByRole("listbox");
    expect(listbox).toBeTruthy();

    fireEvent.keyDown(listbox, { key: "ArrowDown" });
    fireEvent.keyDown(listbox, { key: "Enter" });

    expect(onChange).toHaveBeenCalledWith("S02");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("portals the listbox to document.body when portal is true and closes on outside pointerdown", () => {
    const onChange = vi.fn();
    const { container } = render(
      <TradingSelect
        portal
        compact
        ariaLabel="Location"
        value="L01"
        onChange={onChange}
        options={[
          { value: "L01", label: "Location 1" },
          { value: "L02", label: "Location 2" }
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Location 1" }));

    const listbox = screen.getByRole("listbox");
    expect(listbox.className).toContain("trading-dropdown-portal");
    expect(container.querySelector("ul[role='listbox']")).toBeNull();
    expect(document.body.contains(listbox)).toBe(true);

    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole("listbox")).toBeNull();
  });
});

