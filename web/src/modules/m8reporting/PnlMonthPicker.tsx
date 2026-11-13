import { ReportingMonthPicker } from "./reportingPickers";

export interface PnlMonthPickerProps {
  id: string;
  label: string;
  value: string;
  onChange: (nextValue: string) => void;
}

/** Custom COOPFED-themed month picker replacing the native browser chrome popup. */
export function PnlMonthPicker(props: PnlMonthPickerProps) {
  return <ReportingMonthPicker {...props} />;
}
