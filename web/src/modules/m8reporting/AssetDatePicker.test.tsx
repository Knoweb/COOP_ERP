import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { afterEach, describe, expect, it, vi } from "vitest";
import { messages } from "../../shell/i18n/messages";
import { AssetDatePicker } from "./AssetDatePicker";

describe("AssetDatePicker", () => {
  afterEach(cleanup);

  it("opens the COOPFED date calendar portal, navigates months, and picks a date", () => {
    const handleChange = vi.fn();
    render(
      <IntlProvider locale="en" messages={messages.en}>
        <AssetDatePicker
          id="form-asset-date"
          label="Acquisition Date"
          value="2026-11-11"
          onChange={handleChange}
          portal
        />
      </IntlProvider>
    );

    fireEvent.click(screen.getByRole("button", { name: /Acquisition Date: 11\/11\/2026/i }));
    const dialog = screen.getByRole("dialog", { name: "Acquisition Date" });
    expect(dialog.className).toContain("assets-calendar-portal");

    fireEvent.click(screen.getByRole("button", { name: "Previous month" }));
    fireEvent.click(screen.getByRole("gridcell", { name: "15/10/2026" }));
    expect(handleChange).toHaveBeenCalledWith("2026-10-15");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("supports Clear action and Escape key", () => {
    const handleChange = vi.fn();
    render(
      <IntlProvider locale="en" messages={messages.en}>
        <AssetDatePicker
          id="form-asset-date"
          label="Acquisition Date"
          value="2026-11-11"
          onChange={handleChange}
        />
      </IntlProvider>
    );

    fireEvent.click(screen.getByRole("button", { name: /Acquisition Date:/i }));
    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    expect(handleChange).toHaveBeenCalledWith("");

    fireEvent.click(screen.getByRole("button", { name: /Acquisition Date:/i }));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

