import { DropdownSelect } from "../../shell/components/DropdownSelect";
import type { DropdownOption } from "../../shell/components/DropdownSelect";

export type PricingSelectOption = DropdownOption;

export interface PricingSelectProps {
  id?: string;
  "aria-label"?: string;
  value: string;
  options: PricingSelectOption[];
  onChange: (value: string) => void;
  placeholder?: string;
  className?: string;
  disabled?: boolean;
}

/**
 * Custom dropdown selector matching the Price Lists design:
 * - Rounded trigger with active border & rotating chevron
 * - Floating popup with smooth shadows
 * - Selected item with vertical accent bar, colored text & checkmark
 * - Synchronized hidden native <select> for full E2E & form accessibility
 */
export function PricingSelect({
  id,
  "aria-label": ariaLabel,
  value,
  options,
  onChange,
  placeholder,
  className = "",
  disabled = false
}: PricingSelectProps) {
  return (
    <DropdownSelect
      id={id}
      aria-label={ariaLabel}
      value={value}
      options={options}
      onChange={onChange}
      placeholder={placeholder}
      className={`pricing-dropdown-select ${className}`.trim()}
      disabled={disabled}
    />
  );
}
