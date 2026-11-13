import { useIntl } from "react-intl";
import { DatePicker } from "../../shell/components/DatePicker";
import { MonthPicker } from "../../shell/components/MonthPicker";
import { isValidIsoDate } from "../../shell/components/calendarShared";
import { useFormatDate } from "../../shell/i18n/formats";

export interface ReportingDatePickerProps {
  id: string;
  label: string;
  value: string;
  onChange: (nextValue: string) => void;
  portal?: boolean;
  portalClassName?: string;
  min?: string;
  max?: string;
}

export interface ReportingMonthPickerProps {
  id: string;
  label: string;
  value: string;
  onChange: (nextValue: string) => void;
  portal?: boolean;
}

function parseYearMonth(value: string): { year: number; month: number } | null {
  const match = /^(\d{4})-(\d{2})$/.exec(value.trim());
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  if (month < 1 || month > 12) return null;
  return { year, month };
}

/** Shared m8reporting adapter keeping formatted date accessibility labels atop shell DatePicker. */
export function ReportingDatePicker({
  id,
  label,
  value,
  onChange,
  portal = false,
  portalClassName,
  min,
  max
}: ReportingDatePickerProps) {
  const formatDate = useFormatDate();
  const displayValue = value && isValidIsoDate(value) ? formatDate(value) : "—";

  return (
    <DatePicker
      id={id}
      ariaLabel={label}
      triggerAriaLabel={`${label}: ${displayValue}`}
      placeholder="—"
      value={value}
      onChange={onChange}
      min={min}
      max={max}
      displayValueMode="formatted"
      portal
      portalClassName={portal ? portalClassName : undefined}
    />
  );
}

/** Shared m8reporting adapter keeping month accessibility labels atop shell MonthPicker. */
export function ReportingMonthPicker({
  id,
  label,
  value,
  onChange
}: ReportingMonthPickerProps) {
  const intl = useIntl();
  const parsed = parseYearMonth(value);
  const triggerLabel = parsed
    ? intl.formatDate(new Date(Date.UTC(parsed.year, parsed.month - 1, 1)), {
        month: "long",
        year: "numeric",
        timeZone: "UTC"
      })
    : "—";

  return (
    <MonthPicker
      id={id}
      ariaLabel={label}
      triggerAriaLabel={`${label}: ${triggerLabel}`}
      placeholder="—"
      value={value}
      onChange={onChange}
      portal
    />
  );
}

