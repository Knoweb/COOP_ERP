import { ReportingDatePicker } from "./reportingPickers";

export interface EstimatorDatePickerProps {
  id: string;
  label: string;
  value: string;
  onChange: (nextValue: string) => void;
  portal?: boolean;
}

/** Custom COOPFED-themed date picker aligned with Revenue Estimator visuals. */
export function EstimatorDatePicker({ id, label, value, onChange, portal = false }: EstimatorDatePickerProps) {
  return (
    <ReportingDatePicker
      id={id}
      label={label}
      value={value}
      onChange={onChange}
      portal={portal}
      portalClassName="sales-estimator-calendar-portal"
    />
  );
}
