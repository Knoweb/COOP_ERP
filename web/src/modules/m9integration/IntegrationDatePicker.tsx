import { DatePicker } from "../../shell/components/DatePicker";
import { isValidIsoDate } from "../../shell/components/calendarShared";
import { useFormatDate } from "../../shell/i18n/formats";

export interface IntegrationDatePickerProps {
  id: string;
  value: string; // YYYY-MM-DD or ""
  onChange: (value: string) => void;
  ariaLabel: string;
  min?: string;
  max?: string;
  required?: boolean;
  disabled?: boolean;
}

/** Journal Exports date picker delegating to the unified COOPFED shell calendar. */
export function IntegrationDatePicker({
  id,
  value,
  onChange,
  ariaLabel,
  min,
  max,
  required,
  disabled
}: IntegrationDatePickerProps) {
  const formatDate = useFormatDate();
  const displayValue = value && isValidIsoDate(value) ? formatDate(value) : "—";

  return (
    <DatePicker
      id={id}
      ariaLabel={ariaLabel}
      triggerAriaLabel={`${ariaLabel}: ${displayValue}`}
      placeholder="—"
      value={value}
      onChange={onChange}
      min={min}
      max={max}
      required={required}
      disabled={disabled}
      displayValueMode="formatted"
      portal
    />
  );
}
