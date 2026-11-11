import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { afterEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import { ShopReportDatePicker, ShopReportMonthPicker } from "./ShopReportCalendarPicker";

function renderWithIntl(ui: React.ReactNode) {
  return render(
    <IntlProvider locale="en" messages={messages.en}>
      {ui}
    </IntlProvider>
  );
}

describe("ShopReportCalendarPicker", () => {
  afterEach(cleanup);

  it("opens the daily COOPFED date popover, navigates months, and selects a day", () => {
    const handleChange = vi.fn();
    renderWithIntl(
      <ShopReportDatePicker
        id="shop-report-date"
        label="Date"
        value="2026-11-11"
        onChange={handleChange}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /Date: 11\/11\/2026/i }));
    expect(screen.getByRole("dialog", { name: "Date" })).toBeDefined();
    expect(screen.getByText("November 2026")).toBeDefined();

    fireEvent.click(screen.getByRole("button", { name: "Previous month" }));
    expect(screen.getByText("October 2026")).toBeDefined();

    fireEvent.click(screen.getByRole("gridcell", { name: "15/10/2026" }));
    expect(handleChange).toHaveBeenCalledWith("2026-10-15");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("opens the monthly COOPFED month popover, navigates years, and selects a month", () => {
    const handleChange = vi.fn();
    renderWithIntl(
      <ShopReportMonthPicker
        id="shop-report-month"
        label="Month"
        value="2026-11"
        onChange={handleChange}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /Month: November 2026/i }));
    expect(screen.getByRole("dialog", { name: "Month" })).toBeDefined();
    expect(screen.getByText("2026")).toBeDefined();

    fireEvent.click(screen.getByRole("button", { name: "Previous year" }));
    expect(screen.getByText("2025")).toBeDefined();

    fireEvent.click(screen.getByRole("gridcell", { name: "Apr" }));
    expect(handleChange).toHaveBeenCalledWith("2025-04");
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

