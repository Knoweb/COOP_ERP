import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { afterEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import { TargetsDatePicker } from "./TargetsDatePicker";

describe("TargetsDatePicker", () => {
  afterEach(cleanup);

  it("opens the COOPFED date calendar popover, navigates months, and picks a date", () => {
    const handleChange = vi.fn();
    render(
      <IntlProvider locale="en" messages={messages.en}>
        <TargetsDatePicker
          id="filter-from"
          label="From"
          value="2026-11-11"
          onChange={handleChange}
        />
      </IntlProvider>
    );

    fireEvent.click(screen.getByRole("button", { name: /From: 11\/11\/2026/i }));
    expect(screen.getByRole("dialog", { name: "From" })).toBeDefined();

    fireEvent.click(screen.getByRole("button", { name: "Previous month" }));
    fireEvent.click(screen.getByRole("gridcell", { name: "15/10/2026" }));
    expect(handleChange).toHaveBeenCalledWith("2026-10-15");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("supports portaling, Clear action, and Escape key", () => {
    const handleChange = vi.fn();
    render(
      <IntlProvider locale="en" messages={messages.en}>
        <TargetsDatePicker
          id="target-start"
          label="Period Start"
          value="2026-11-01"
          onChange={handleChange}
          portal
        />
      </IntlProvider>
    );

    fireEvent.click(screen.getByRole("button", { name: /Period Start:/i }));
    const dialog = screen.getByRole("dialog", { name: "Period Start" });
    expect(dialog.className).toContain("targets-calendar-portal");

    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    expect(handleChange).toHaveBeenCalledWith("");

    fireEvent.click(screen.getByRole("button", { name: /Period Start:/i }));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

