import { ReportingDatePicker } from "./reportingPickers";

export interface TargetsDatePickerProps {
  id: string;
  label: string;
  value: string;
  onChange: (nextValue: string) => void;
  portal?: boolean;
}

/** Custom COOPFED-themed date picker matching Shop Targets form inputs, with optional portal support. */
export function TargetsDatePicker({ id, label, value, onChange, portal = false }: TargetsDatePickerProps) {
  return (
    <ReportingDatePicker
      id={id}
      label={label}
      value={value}
      onChange={onChange}
      portal={portal}
      portalClassName="targets-calendar-portal"
    />
  );
}
