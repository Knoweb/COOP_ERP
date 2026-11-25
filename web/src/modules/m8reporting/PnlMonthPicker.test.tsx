import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { afterEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import { PnlMonthPicker } from "./PnlMonthPicker";

function renderPicker(value: string, onChange: (next: string) => void) {
  return render(
    <IntlProvider locale="en" messages={messages.en}>
      <PnlMonthPicker id="filter-month" label="Month" value={value} onChange={onChange} />
    </IntlProvider>
  );
}

describe("PnlMonthPicker", () => {
  afterEach(cleanup);

  it("opens the COOPFED month popover, navigates years, and picks a month", () => {
    const handleChange = vi.fn();
    renderPicker("2026-11", handleChange);

    const trigger = screen.getByRole("button", { name: /Month: November 2026/i });
    fireEvent.click(trigger);

    expect(screen.getByRole("dialog", { name: "Month" })).toBeDefined();
    expect(screen.getByText("2026")).toBeDefined();

    fireEvent.click(screen.getByRole("button", { name: "Previous year" }));
    expect(screen.getByText("2025")).toBeDefined();

    fireEvent.click(screen.getByRole("gridcell", { name: "Mar" }));
    expect(handleChange).toHaveBeenCalledWith("2025-03");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("supports Clear and This month actions and closes on Escape", () => {
    const handleChange = vi.fn();
    renderPicker("2025-06", handleChange);

    fireEvent.click(screen.getByRole("button", { name: /Month:/i }));
    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    expect(handleChange).toHaveBeenCalledWith("");

    fireEvent.click(screen.getByRole("button", { name: /Month:/i }));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

