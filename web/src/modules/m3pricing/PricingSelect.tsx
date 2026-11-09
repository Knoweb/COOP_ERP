import type { ChangeEvent } from "react";

export interface PricingSelectOption {
  value: string;
  label: string;
}

export interface PricingSelectProps {
  id?: string;
  "aria-label"?: string;
  ariaLabel?: string;
  value: string;
  options: PricingSelectOption[];
  onChange: (value: string) => void;
  placeholder?: string;
  className?: string;
  disabled?: boolean;
}

/**
 * Accessible dropdown selector matching the Price Lists design:
 * Styled via pricing.css (.pricing-select, .pricing-filter-bar select, .pricing-form-field select)
 * with rounded pill trigger, royal blue chevron, and full Playwright E2E compatibility.
 */
export function PricingSelect({
  id,
  "aria-label": ariaLabelProp,
  ariaLabel,
  value,
  options,
  onChange,
  placeholder,
  className = "",
  disabled = false
}: PricingSelectProps) {
  const effectiveAriaLabel = ariaLabelProp ?? ariaLabel;

  return (
    <select
      id={id}
      aria-label={effectiveAriaLabel}
      value={value}
      onChange={(e: ChangeEvent<HTMLSelectElement>) => onChange(e.target.value)}
      disabled={disabled}
      className={`pricing-select ${className}`.trim()}
    >
      {placeholder && <option value="">{placeholder}</option>}
      {options.map((opt) => (
        <option key={opt.value} value={opt.value}>
          {opt.label}
        </option>
      ))}
    </select>
  );
}
