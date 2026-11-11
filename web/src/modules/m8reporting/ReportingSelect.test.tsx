import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ReportingSelect } from "./ReportingSelect";

describe("ReportingSelect", () => {
  afterEach(cleanup);

  it("opens the custom listbox, shows active indicator bar and checkmark, and selects an option", () => {
    const onChange = vi.fn();
    render(
      <label className="report-viewer-field">
        <span className="report-viewer-field__label">Location</span>
        <ReportingSelect
          ariaLabel="Location"
          value=""
          onChange={onChange}
          options={[
            { value: "", label: "All locations" },
            { value: "loc-1", label: "S01 Kuliyapitiya shop" }
          ]}
        />
      </label>
    );

    const trigger = screen.getByRole("button", { name: "All locations" });
    expect(trigger.getAttribute("aria-expanded")).toBe("false");

    fireEvent.click(trigger);

    const listbox = screen.getByRole("listbox");
    expect(listbox.querySelectorAll(".custom-dropdown__item-bar")).toHaveLength(1);
    expect(listbox.querySelectorAll(".custom-dropdown__item-spacer")).toHaveLength(1);
    expect(listbox.querySelectorAll(".custom-dropdown__checkmark")).toHaveLength(1);

    fireEvent.click(within(listbox).getByRole("option", { name: "S01 Kuliyapitiya shop" }));

    expect(onChange).toHaveBeenCalledWith("loc-1");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("renders into document.body when portal is enabled and closes on Escape", () => {
    const onChange = vi.fn();
    const { container } = render(
      <ReportingSelect
        portal
        compact
        ariaLabel="Location"
        value="loc-1"
        onChange={onChange}
        options={[
          { value: "loc-1", label: "S01 Kuliyapitiya shop" },
          { value: "loc-2", label: "S02 Hettipola shop" }
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "S01 Kuliyapitiya shop" }));

    const listbox = screen.getByRole("listbox");
    expect(listbox.className).toContain("reporting-dropdown-portal");
    expect(container.querySelector("ul[role='listbox']")).toBeNull();
    expect(document.body.contains(listbox)).toBe(true);

    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
  });
});

