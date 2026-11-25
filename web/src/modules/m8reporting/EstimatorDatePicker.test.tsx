import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { afterEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import { EstimatorDatePicker } from "./EstimatorDatePicker";

describe("EstimatorDatePicker", () => {
  afterEach(cleanup);

  it("opens the COOPFED date calendar popover, navigates months, and picks a date", () => {
    const handleChange = vi.fn();
    render(
      <IntlProvider locale="en" messages={messages.en}>
        <EstimatorDatePicker
          id="as-of-date-input"
          label="Effective Date"
          value="2026-11-12"
          onChange={handleChange}
        />
      </IntlProvider>
    );

    fireEvent.click(screen.getByRole("button", { name: /Effective Date: 12\/11\/2026/i }));
    expect(screen.getByRole("dialog", { name: "Effective Date" })).toBeDefined();

    fireEvent.click(screen.getByRole("button", { name: "Previous month" }));
    fireEvent.click(screen.getByRole("gridcell", { name: "29/10/2026" }));
    expect(handleChange).toHaveBeenCalledWith("2026-10-29");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("supports portaling, Clear action, and Escape key", () => {
    const handleChange = vi.fn();
    render(
      <IntlProvider locale="en" messages={messages.en}>
        <EstimatorDatePicker
          id="as-of-date-input"
          label="Effective Date"
          value="2026-11-12"
          onChange={handleChange}
          portal
        />
      </IntlProvider>
    );

    fireEvent.click(screen.getByRole("button", { name: /Effective Date:/i }));
    const dialog = screen.getByRole("dialog", { name: "Effective Date" });
    expect(dialog.className).toContain("sales-estimator-calendar-portal");

    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    expect(handleChange).toHaveBeenCalledWith("");

    fireEvent.click(screen.getByRole("button", { name: /Effective Date:/i }));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

