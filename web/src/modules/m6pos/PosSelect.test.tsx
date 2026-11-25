import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PosSelect } from "./PosSelect";

describe("PosSelect", () => {
  afterEach(cleanup);

  it("opens the custom listbox, shows active indicator bar and checkmark, and selects an option", () => {
    const onChange = vi.fn();
    render(
      <label className="pos-field">
        <span>Shop</span>
        <PosSelect
          ariaLabel="Shop"
          value=""
          onChange={onChange}
          options={[
            { value: "", label: "Choose a shop" },
            { value: "s01", label: "S01 Development shop" },
            { value: "s02", label: "S02 Hettipola shop" }
          ]}
        />
      </label>
    );

    const trigger = screen.getByRole("button", { name: "Choose a shop" });
    expect(trigger.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByRole("listbox")).toBeNull();

    fireEvent.click(trigger);

    const listbox = screen.getByRole("listbox");
    expect(trigger.getAttribute("aria-expanded")).toBe("true");
    expect(listbox.querySelectorAll(".custom-dropdown__item-bar")).toHaveLength(1);
    expect(listbox.querySelectorAll(".custom-dropdown__item-spacer")).toHaveLength(2);
    expect(listbox.querySelectorAll(".custom-dropdown__checkmark")).toHaveLength(1);

    fireEvent.click(within(listbox).getByRole("option", { name: "S02 Hettipola shop" }));

    expect(onChange).toHaveBeenCalledWith("s02");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("renders into document.body when portal is enabled and closes on Escape or outside click", () => {
    const onChange = vi.fn();
    const { container } = render(
      <PosSelect
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
    expect(listbox.className).toContain("pos-dropdown-portal");
    expect(container.querySelector("ul[role='listbox']")).toBeNull();
    expect(document.body.contains(listbox)).toBe(true);

    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "S01 Development shop" }));
    expect(screen.getByRole("listbox")).toBeTruthy();

    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("keeps leading empty option in hidden select while hiding blank rows in visible listbox", () => {
    const onChange = vi.fn();
    render(
      <PosSelect
        ariaLabel="Shop"
        value=""
        onChange={onChange}
        options={[
          { value: "", label: "" },
          { value: "s01", label: "S01 Development shop" }
        ]}
      />
    );

    const combobox = screen.getByRole("combobox", { name: "Shop" });
    expect(combobox.querySelectorAll("option")).toHaveLength(2);

    fireEvent.click(combobox.parentElement!.querySelector("button")!);
    const listbox = screen.getByRole("listbox");
    expect(within(listbox).getAllByRole("option")).toHaveLength(1);
    expect(within(listbox).getByRole("option", { name: "S01 Development shop" })).toBeTruthy();
  });
});

