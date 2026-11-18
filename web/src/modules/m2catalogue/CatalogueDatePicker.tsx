import { DatePicker } from "../../shell/components/DatePicker";

type CatalogueDatePickerProps = {
  id?: string;
  label?: string;
  value: string; // YYYY-MM-DD
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  required?: boolean;
  min?: string;
  max?: string;
};

export function CatalogueDatePicker(props: CatalogueDatePickerProps) {
  return <DatePicker {...props} />;
}
