import { ReportingDatePicker } from "./reportingPickers";

export interface AssetDatePickerProps {
  id: string;
  label: string;
  value: string;
  onChange: (nextValue: string) => void;
  portal?: boolean;
}

/** Custom COOPFED-themed date picker with optional document-body portaling for modals. */
export function AssetDatePicker({ id, label, value, onChange, portal = false }: AssetDatePickerProps) {
  return (
    <ReportingDatePicker
      id={id}
      label={label}
      value={value}
      onChange={onChange}
      portal={portal}
      portalClassName="assets-calendar-portal"
    />
  );
}
