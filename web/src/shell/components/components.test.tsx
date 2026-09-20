import { cleanup, render, screen } from "@testing-library/react";
import type { ReactElement } from "react";
import { IntlProvider } from "react-intl";
import { afterEach, describe, expect, it } from "vitest";
import shellCss from "../shell.css?raw";
import { messages } from "../i18n/messages";
import type { Locale } from "../i18n/messages";
import { MoneyDisplay } from "./MoneyDisplay";
import { CHIP_STATES, StateChip } from "./StateChip";
import { TrainingBadge } from "./TrainingBadge";

function renderIn(locale: Locale, element: ReactElement) {
  return render(
    <IntlProvider locale={locale} messages={messages[locale]}>
      {element}
    </IntlProvider>
  );
}

afterEach(cleanup);

describe("MoneyDisplay", () => {
  it("shows an amount as Rs 1,234.50 with two decimals and a thousands separator", () => {
    const { container } = renderIn("en", <MoneyDisplay amount="1234.5" />);
    expect(container.textContent).toBe("Rs 1,234.50");
  });

  it("asks for tabular figures, so that amounts line up in a column", () => {
    const { container } = renderIn("en", <MoneyDisplay amount="10.00" />);
    expect(container.querySelector(".money")).toBeTruthy();
    // jsdom applies no style sheet; what can be checked is that the class means what it says.
    expect(shellCss).toMatch(/\.money \{[^}]*font-variant-numeric: tabular-nums;/);
  });

  it("uses the 40 px step for a total and the normal size otherwise", () => {
    const { container } = renderIn("en", <MoneyDisplay amount="10.00" size="total" />);
    expect(container.querySelector(".money--total")).toBeTruthy();
    cleanup();
    expect(renderIn("en", <MoneyDisplay amount="10.00" />).container.querySelector(".money--total")).toBeNull();
  });

  it("keeps Western Arabic digits in Sinhala and Tamil and writes the currency their way", () => {
    expect(renderIn("si", <MoneyDisplay amount="1234567.00" />).container.textContent).toBe("රු. 1,234,567.00");
    cleanup();
    expect(renderIn("ta", <MoneyDisplay amount="1234567.00" />).container.textContent).toBe("ரூ. 1,234,567.00");
  });

  it("shows more than two decimals exactly as they arrived, because the client never rounds", () => {
    expect(renderIn("en", <MoneyDisplay amount="1234567.891" />).container.textContent).toBe("Rs 1,234,567.891");
  });

  it("shows a negative amount with a real minus sign and reads it out in words", () => {
    const { container } = renderIn("en", <MoneyDisplay amount="-250" />);

    const shown = container.querySelector("[aria-hidden='true']");
    expect(shown?.textContent).toBe("−Rs 250.00");
    // What a screen reader gets instead: some readers skip the sign, and a debt would become a credit.
    expect(screen.getByText("minus Rs 250.00")).toBeTruthy();
  });

  it("shows a dash and says so, never NaN, for an amount that is not a number", () => {
    for (const bad of ["abc", NaN, null, undefined, ""]) {
      const { container } = renderIn("en", <MoneyDisplay amount={bad} />);
      expect(container.textContent).not.toContain("NaN");
      expect(container.querySelector("[aria-hidden='true']")?.textContent).toBe("—");
      expect(screen.getByText("Amount not available")).toBeTruthy();
      cleanup();
    }
  });
});

describe("StateChip", () => {
  it("says every state in words and adds a symbol, so that colour is never the only signal", () => {
    const words = { draft: "Draft", issued: "Issued", disputed: "Disputed", void: "Void", alert: "Alert" };
    for (const state of CHIP_STATES) {
      const { container } = renderIn("en", <StateChip state={state} />);
      expect(container.textContent, state).toBe(words[state]);
      expect(container.querySelector("svg"), state).toBeTruthy();
      cleanup();
    }
  });

  it("draws a different symbol for every state", () => {
    const drawings = CHIP_STATES.map((state) => {
      const drawing = renderIn("en", <StateChip state={state} />).container.querySelector("svg")?.innerHTML;
      cleanup();
      return drawing;
    });
    expect(new Set(drawings).size).toBe(CHIP_STATES.length);
  });

  it("hides the symbol from a screen reader, which reads the word", () => {
    const { container } = renderIn("en", <StateChip state="alert" />);
    expect(container.querySelector("svg")?.getAttribute("aria-hidden")).toBe("true");
  });

  it("has a style rule for every state, so that none comes out unstyled", () => {
    for (const state of CHIP_STATES) {
      expect(shellCss, state).toContain(`.state-chip--${state} {`);
    }
  });

  it("speaks Sinhala and Tamil", () => {
    expect(renderIn("si", <StateChip state="draft" />).container.textContent).toBe("කෙටුම්පත");
    cleanup();
    expect(renderIn("ta", <StateChip state="void" />).container.textContent).toBe("ரத்து");
  });

  it("shows the word of the module's own state machine when it is given one", () => {
    const { container } = renderIn("en", <StateChip state="issued" label="Goods received" />);
    expect(container.textContent).toBe("Goods received");
    expect(container.querySelector(".state-chip--issued")).toBeTruthy();
  });
});

describe("TrainingBadge", () => {
  it("renders nothing at all outside training mode", () => {
    const { container } = renderIn("en", <TrainingBadge active={false} />);
    expect(container.innerHTML).toBe("");
  });

  it("says in a full sentence that nothing here is real when training mode is on", () => {
    renderIn("en", <TrainingBadge active={true} />);
    const band = screen.getByRole("region", { name: "TRAINING MODE" });
    expect(band.textContent).toContain("TRAINING MODE");
    expect(band.textContent).toContain("Nothing you do here is real");
  });

  it("holds nothing that takes the focus: it cannot be closed and cannot trap the keyboard", () => {
    const { container } = renderIn("en", <TrainingBadge active={true} />);
    expect(container.querySelectorAll("button, a, input, [tabindex]")).toHaveLength(0);
  });

  it("speaks Sinhala and Tamil", () => {
    renderIn("si", <TrainingBadge active={true} />);
    expect(screen.getByRole("region", { name: "පුහුණු ප්‍රකාරය" })).toBeTruthy();
    cleanup();
    renderIn("ta", <TrainingBadge active={true} />);
    expect(screen.getByRole("region", { name: "பயிற்சி முறை" })).toBeTruthy();
  });
});
