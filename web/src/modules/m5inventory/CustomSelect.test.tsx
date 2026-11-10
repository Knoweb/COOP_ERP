import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CustomSelect } from "./CustomSelect";

describe("CustomSelect", () => {
  afterEach(cleanup);

  it("opens the custom listbox, renders indicator bar and checkmark, and updates selection", () => {
    const onChange = vi.fn();
    render(
      <label className="inventory-form-field">
        <span>Urgency tier</span>
        <CustomSelect
          id="urgency-tier-filter"
          ariaLabel="Urgency tier"
          value="ALL"
          onChange={onChange}
          options={[
            { value: "ALL", label: "All" },
            { value: "CRITICAL", label: "Critical" },
            { value: "WARNING", label: "Warning" }
          ]}
        />
      </label>
    );

    const trigger = screen.getByRole("button", { name: "All" });
    fireEvent.click(trigger);

    const listbox = screen.getByRole("listbox");
    expect(trigger.getAttribute("aria-expanded")).toBe("true");
    expect(listbox.querySelectorAll(".custom-dropdown__item-bar")).toHaveLength(1);
    expect(listbox.querySelectorAll(".custom-dropdown__item-spacer")).toHaveLength(2);
    expect(listbox.querySelectorAll(".custom-dropdown__checkmark")).toHaveLength(1);

    fireEvent.click(within(listbox).getByRole("option", { name: "Critical" }));

    expect(onChange).toHaveBeenCalledWith("CRITICAL");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("portals the listbox to document.body when portal is true and closes on outside pointerdown", () => {
    const onChange = vi.fn();
    const { container } = render(
      <CustomSelect
        portal
        compact
        ariaLabel="Condition"
        value="GOOD"
        onChange={onChange}
        options={[
          { value: "GOOD", label: "Good" },
          { value: "DAMAGED", label: "Damaged" }
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Good" }));

    const listbox = screen.getByRole("listbox");
    expect(listbox.className).toContain("inventory-dropdown-portal");
    expect(container.querySelector("ul[role='listbox']")).toBeNull();
    expect(document.body.contains(listbox)).toBe(true);

    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("preserves empty index-0 option in native combobox while omitting blank row from listbox", () => {
    const onChange = vi.fn();
    render(
      <CustomSelect
        ariaLabel="Recipe"
        value=""
        onChange={onChange}
        options={[
          { value: "", label: "" },
          { value: "recipe-1", label: "Repack 50kg to 1kg" }
        ]}
      />
    );

    const combobox = screen.getByRole("combobox", { name: "Recipe" });
    expect(combobox.querySelectorAll("option")).toHaveLength(2);

    fireEvent.click(combobox.parentElement!.querySelector("button")!);
    const listbox = screen.getByRole("listbox");
    expect(within(listbox).getAllByRole("option")).toHaveLength(1);
    expect(within(listbox).getByRole("option", { name: "Repack 50kg to 1kg" })).toBeTruthy();
  });
});
