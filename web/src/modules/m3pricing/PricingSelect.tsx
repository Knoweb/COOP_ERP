import { useState, useRef, useEffect } from "react";
import type { KeyboardEvent } from "react";

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
 * Dropdown selector matching the Price Lists pill button design with custom brand palette
 * options instead of the browser OS default blue selection highlight.
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
  const [isOpen, setIsOpen] = useState(false);
  const [highlightedIndex, setHighlightedIndex] = useState(-1);
  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  const selectedOption = options.find((opt) => opt.value === value) ?? options[0];

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    }
    if (isOpen) {
      document.addEventListener("mousedown", handleClickOutside);
    }
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, [isOpen]);

  const handleTriggerKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (disabled) return;

    if (event.key === "ArrowDown" || event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      setIsOpen(true);
      const currentIndex = options.findIndex((opt) => opt.value === value);
      setHighlightedIndex(currentIndex >= 0 ? currentIndex : 0);
    } else if (event.key === "Escape") {
      setIsOpen(false);
    }
  };

  const handleMenuKeyDown = (event: KeyboardEvent<HTMLUListElement>) => {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setHighlightedIndex((prev) => (prev + 1) % options.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setHighlightedIndex((prev) => (prev - 1 + options.length) % options.length);
    } else if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      if (highlightedIndex >= 0 && highlightedIndex < options.length) {
        onChange(options[highlightedIndex].value);
        setIsOpen(false);
        triggerRef.current?.focus();
      }
    } else if (event.key === "Escape") {
      event.preventDefault();
      setIsOpen(false);
      triggerRef.current?.focus();
    }
  };

  return (
    <div className={`pricing-custom-select ${className}`} ref={containerRef}>
      <button
        ref={triggerRef}
        id={id}
        type="button"
        className={`pricing-custom-select__trigger${isOpen ? " pricing-custom-select__trigger--open" : ""}`}
        onClick={() => {
          if (!disabled) {
            setIsOpen((prev) => !prev);
            const currentIndex = options.findIndex((opt) => opt.value === value);
            setHighlightedIndex(currentIndex >= 0 ? currentIndex : 0);
          }
        }}
        onKeyDown={handleTriggerKeyDown}
        aria-haspopup="listbox"
        aria-expanded={isOpen}
        aria-label={ariaLabel}
        disabled={disabled}
      >
        <span className="pricing-custom-select__value">{selectedOption?.label || value}</span>
        <svg
          className={`pricing-custom-select__chevron${isOpen ? " pricing-custom-select__chevron--open" : ""}`}
          viewBox="0 0 24 24"
          aria-hidden="true"
          focusable="false"
        >
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>

      {isOpen && (
        <ul
          className="pricing-custom-select__menu"
          role="listbox"
          aria-label={ariaLabel}
          tabIndex={-1}
          onKeyDown={handleMenuKeyDown}
        >
          {options.map((opt, index) => {
            const isSelected = opt.value === value;
            const isHighlighted = index === highlightedIndex;
            return (
              <li
                key={opt.value}
                role="option"
                aria-selected={isSelected}
                className={`pricing-custom-select__item${isSelected ? " pricing-custom-select__item--selected" : ""}${isHighlighted ? " pricing-custom-select__item--highlighted" : ""}`}
                onClick={() => {
                  onChange(opt.value);
                  setIsOpen(false);
                  triggerRef.current?.focus();
                }}
                onMouseEnter={() => setHighlightedIndex(index)}
              >
                <span>{opt.label}</span>
                {isSelected && (
                  <svg className="pricing-custom-select__check" viewBox="0 0 24 24" aria-hidden="true">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                )}
              </li>
            );
          })}
        </ul>
      )}

      <select
        className="pricing-custom-select__native-hidden"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        tabIndex={-1}
        aria-hidden="true"
      >
        {options.map((opt) => (
          <option key={opt.value} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>
    </div>
  );
}
