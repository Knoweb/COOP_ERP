import { useT } from "../i18n/useT";
import { formatMoneyDigits } from "../i18n/formats";

type MoneyDisplayProps = {
  /**
   * The amount exactly as the API sent it. A decimal string ("1234.50") is best; a number is
   * accepted. Never the result of a calculation done in the screen: totals come from the server.
   */
  amount: string | number | null | undefined;
  /** "total" is the 40 px step of the type scale, for the one total of a document. */
  size?: "normal" | "total";
};

/**
 * An amount of money: Rs 1,234.00 (doc 30 section 2.1). Every amount on every screen goes
 * through this component, so that they all look the same, line up in a column (tabular
 * figures), and keep Western Arabic digits in Sinhala and Tamil.
 *
 * How the digits are made, and why the client never rounds: formatMoneyDigits in
 * shell/i18n/formats.ts. This component adds the currency, the sign and the accessibility.
 *
 *   - The currency mark is a message ("Rs {amount}"), so each language writes it its own way.
 *   - A negative amount shows a real minus sign (U+2212, as wide as a digit), not a hyphen.
 *     Screen readers do not agree on how to read that sign, and some skip it, which would turn
 *     a debt into a credit. So the visible text is hidden from them and they get the sentence
 *     "minus Rs 1,234.00" instead.
 *   - An amount that is not a number shows a dash and says "amount not available". It never
 *     shows "NaN" and never breaks the page: one bad field must not hide the whole document.
 *
 * This is the single amount. The totals block of the till (subtotal, reductions, tendered,
 * change; doc 30 section 2.2) is built from it when M6 needs it.
 */
export function MoneyDisplay({ amount, size = "normal" }: MoneyDisplayProps) {
  const t = useT();
  const sizeClass = size === "total" ? "money money--total" : "money";
  const money = formatMoneyDigits(amount);

  if (money === null) {
    return (
      <span className={`${sizeClass} money--invalid`}>
        <span aria-hidden="true">—</span>
        <span className="visually-hidden">{t("shell.money.invalid").text}</span>
      </span>
    );
  }

  if (money.negative) {
    return (
      <span className={sizeClass}>
        <span aria-hidden="true">{t("shell.money.negative", undefined, { amount: money.digits }).text}</span>
        <span className="visually-hidden">{t("shell.money.negative_spoken", undefined, { amount: money.digits }).text}</span>
      </span>
    );
  }

  return <span className={sizeClass}>{t("shell.money.amount", undefined, { amount: money.digits }).text}</span>;
}
