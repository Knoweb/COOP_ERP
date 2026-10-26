import type { ChangeEvent } from "react";

export interface PricingSelectOption {
  value: string;
  label: string;
}

export interface PricingSelectProps {
  id?: string;
  "aria-label"?: string;
  value: string;
  options: PricingSelectOption[];
  onChange: (value: string) => void;
  className?: string;
  disabled?: boolean;
}

/**
 * Dropdown selector matching the Price Lists pill design using a native select element
 * with custom branding and full accessibility/Playwright selectOption support.
 */
export function PricingSelect({
  id,
  "aria-label": ariaLabel,
  value,
  options,
  onChange,
  className = "",
  disabled = false
}: PricingSelectProps) {
  return (
    <select
      id={id}
      aria-label={ariaLabel}
      value={value}
      onChange={(e: ChangeEvent<HTMLSelectElement>) => onChange(e.target.value)}
      disabled={disabled}
      className={`pricing-select ${className}`.trim()}
    >
      {options.map((opt) => (
        <option key={opt.value} value={opt.value}>
          {opt.label}
        </option>
      ))}
    </select>
  );
}
