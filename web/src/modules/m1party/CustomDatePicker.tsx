import { DatePicker } from "../../shell/components/DatePicker";

interface CustomDatePickerProps {
  id?: string;
  value: string;            // "YYYY-MM-DD" or ""
  onChange: (value: string) => void;
  ariaLabel?: string;
  required?: boolean;
}

export function CustomDatePicker({ id, value, onChange, ariaLabel, required }: CustomDatePickerProps) {
  return (
    <DatePicker
      id={id}
      value={value}
      onChange={onChange}
      ariaLabel={ariaLabel}
      required={required}
    />
  );
}
