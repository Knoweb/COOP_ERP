import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import shellCss from "../shell.css?raw";
import { CustomSelect } from "./CustomSelect";

describe("CustomSelect", () => {
  afterEach(cleanup);

  it("opens the custom listbox with brand item states, left indicator bar, and checkmark", () => {
    const onChange = vi.fn();
    render(
      <div className="trading-form-field">
        <span className="trading-form-field__label">From warehouse</span>
        <CustomSelect
          ariaLabel="From warehouse"
          value="fw01"
          onChange={onChange}
          options={[
            { value: "", label: "Choose..." },
            { value: "fw01", label: "FW01 Federation central warehouse, Peliyagoda" },
            { value: "w02", label: "W02 Kurunegala warehouse" }
          ]}
        />
      </div>
    );

    const trigger = screen.getByRole("button", { name: "FW01 Federation central warehouse, Peliyagoda" });
    expect(trigger.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByRole("listbox")).toBeNull();

    fireEvent.click(trigger);

    const listbox = screen.getByRole("listbox");
    expect(trigger.getAttribute("aria-expanded")).toBe("true");
    expect(listbox.querySelectorAll(".ui-select__item-bar")).toHaveLength(1);
    expect(listbox.querySelectorAll(".ui-select__item-spacer")).toHaveLength(2);
    expect(listbox.querySelectorAll(".ui-select__check")).toHaveLength(1);

    const selectedItem = within(listbox).getByRole("option", { name: "FW01 Federation central warehouse, Peliyagoda" });
    expect(selectedItem.className).toContain("ui-select__item--selected");
    expect(selectedItem.getAttribute("aria-selected")).toBe("true");

    fireEvent.click(within(listbox).getByRole("option", { name: "W02 Kurunegala warehouse" }));

    expect(onChange).toHaveBeenCalledWith("w02");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("supports keyboard navigation and Escape / outside click dismissal", () => {
    const onChange = vi.fn();
    render(
      <CustomSelect
        portal
        compact
        ariaLabel="Batch"
        value=""
        onChange={onChange}
        options={[
          { value: "", label: "No batch" },
          { value: "b1", label: "DEMO-2026-16 (400)" },
          { value: "b2", label: "DEMO-2026-38 (200)" }
        ]}
      />
    );

    const trigger = screen.getByRole("button", { name: "No batch" });
    fireEvent.keyDown(trigger, { key: "ArrowDown" });

    const listbox = screen.getByRole("listbox");
    expect(listbox.className).toContain("ui-select__menu--portal");
    expect(document.body.contains(listbox)).toBe(true);

    fireEvent.keyDown(trigger, { key: "ArrowDown" });
    fireEvent.keyDown(trigger, { key: "Enter" });
    expect(onChange).toHaveBeenCalledWith("b1");
    expect(screen.queryByRole("listbox")).toBeNull();

    fireEvent.click(trigger);
    expect(screen.getByRole("listbox")).toBeTruthy();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();

    fireEvent.click(trigger);
    expect(screen.getByRole("listbox")).toBeTruthy();
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("synchronizes hidden native select for form and E2E selectors", () => {
    const onChange = vi.fn();
    render(
      <CustomSelect
        ariaLabel="Payment method"
        value="TRANSFER"
        onChange={onChange}
        options={[
          { value: "TRANSFER", label: "Bank transfer" },
          { value: "CHEQUE", label: "Cheque" }
        ]}
      />
    );

    const combobox = screen.getByRole("combobox", { name: "Payment method" });
    expect(combobox.className).toContain("visually-hidden");
    expect(combobox.querySelectorAll("option")).toHaveLength(2);

    fireEvent.change(combobox, { target: { value: "CHEQUE" } });
    expect(onChange).toHaveBeenCalledWith("CHEQUE");
  });

  it("defines soft pink and wine brand tokens for dropdown items in shell.css", () => {
    expect(shellCss).toContain(".ui-select__item:hover");
    expect(shellCss).toContain(".ui-select__item--selected");
    expect(shellCss).toContain("background: var(--color-polish-31)");
    expect(shellCss).toContain("color: var(--color-accent)");
  });
});

