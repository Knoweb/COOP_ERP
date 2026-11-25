import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PricingSelect } from "./PricingSelect";

describe("PricingSelect", () => {
  afterEach(cleanup);

  it("opens the custom listbox, shows active indicator bar and checkmark, and selects an option", () => {
    const onChange = vi.fn();
    render(
      <label>
        <span>Status</span>
        <PricingSelect
          ariaLabel="Status"
          value="ALL"
          onChange={onChange}
          options={[
            { value: "ALL", label: "All" },
            { value: "ACTIVE", label: "Active" },
            { value: "EXPIRED", label: "Expired" }
          ]}
        />
      </label>
    );

    const trigger = screen.getByRole("button", { name: "All" });
    expect(trigger.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByRole("listbox")).toBeNull();

    fireEvent.click(trigger);

    const listbox = screen.getByRole("listbox");
    expect(trigger.getAttribute("aria-expanded")).toBe("true");
    expect(listbox.querySelectorAll(".custom-dropdown__item-bar")).toHaveLength(1);
    expect(listbox.querySelectorAll(".custom-dropdown__item-spacer")).toHaveLength(2);
    expect(listbox.querySelectorAll(".custom-dropdown__checkmark")).toHaveLength(1);

    fireEvent.click(within(listbox).getByRole("option", { name: "Active" }));

    expect(onChange).toHaveBeenCalledWith("ACTIVE");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("renders into document.body when portal is enabled and closes on Escape", () => {
    const onChange = vi.fn();
    const { container } = render(
      <PricingSelect
        portal
        compact
        ariaLabel="Unit"
        value="EA"
        onChange={onChange}
        options={[
          { value: "EA", label: "EA" },
          { value: "CASE", label: "CASE" }
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "EA" }));

    const listbox = screen.getByRole("listbox");
    expect(listbox.className).toContain("pricing-dropdown-portal");
    expect(container.querySelector("ul[role='listbox']")).toBeNull();
    expect(document.body.contains(listbox)).toBe(true);

    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("keeps leading empty option in hidden select while hiding blank rows in visible listbox", () => {
    const onChange = vi.fn();
    render(
      <PricingSelect
        ariaLabel="Category"
        value=""
        onChange={onChange}
        options={[
          { value: "", label: "" },
          { value: "RETAIL", label: "Retail" }
        ]}
      />
    );

    const combobox = screen.getByRole("combobox", { name: "Category" });
    expect(combobox.querySelectorAll("option")).toHaveLength(2);

    fireEvent.click(combobox.parentElement!.querySelector("button")!);
    const listbox = screen.getByRole("listbox");
    expect(within(listbox).getAllByRole("option")).toHaveLength(1);
    expect(within(listbox).getByRole("option", { name: "Retail" })).toBeTruthy();
  });
});
