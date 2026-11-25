import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TargetsSelect } from "./TargetsSelect";

describe("TargetsSelect", () => {
  afterEach(cleanup);

  it("opens the custom listbox, renders indicator bar and checkmark, and selects a location", () => {
    const handleChange = vi.fn();
    const { container } = render(
      <TargetsSelect
        id="filter-location"
        aria-label="Location"
        value=""
        onChange={handleChange}
        options={[
          { value: "", label: "All locations" },
          { value: "loc-1", label: "S101 Colombo Main Branch" }
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "All locations" }));

    const listbox = screen.getByRole("listbox");
    expect(listbox).toBeDefined();
    expect(container.querySelector(".custom-dropdown__item-bar")).not.toBeNull();
    expect(container.querySelector(".custom-dropdown__checkmark")).not.toBeNull();

    fireEvent.click(within(listbox).getByRole("option", { name: "S101 Colombo Main Branch" }));
    expect(handleChange).toHaveBeenCalledWith("loc-1");
  });

  it("supports portaling and closes on Escape", () => {
    const handleChange = vi.fn();
    render(
      <TargetsSelect
        id="target-location"
        aria-label="Shop / Location"
        value="loc-1"
        onChange={handleChange}
        portal
        options={[{ value: "loc-1", label: "S101 Colombo Main Branch" }]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "S101 Colombo Main Branch" }));
    const listbox = screen.getByRole("listbox");
    expect(listbox.className).toContain("targets-dropdown-portal");

    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
  });
});

