import { useEffect, useRef, useState } from "react";
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
  placeholder?: string;
  className?: string;
  disabled?: boolean;
}

/**
 * Custom dropdown select component matching the system design:
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
  const [open, setOpen] = useState(false);
  const [openUp, setOpenUp] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const toggleOpen = () => {
    if (disabled) return;
    if (!open && containerRef.current) {
      const rect = containerRef.current.getBoundingClientRect();
      const spaceBelow = window.innerHeight - rect.bottom;
      const spaceAbove = rect.top;
      setOpenUp(spaceBelow < 200 && spaceAbove > spaceBelow);
    }
    setOpen((prev) => !prev);
  };

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent | TouchEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const handleKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };
    if (open) {
      document.addEventListener("mousedown", handleClickOutside);
      document.addEventListener("touchstart", handleClickOutside);
      document.addEventListener("keydown", handleKeyDown);
    }
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("touchstart", handleClickOutside);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open]);

  const selectedOption = options.find((opt) => opt.value === value);
  const triggerLabel = selectedOption?.label ?? placeholder ?? (options[0]?.label ?? "");

  const handleKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (disabled) return;
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      toggleOpen();
    }
  };

  return (
    <div
      ref={containerRef}
      className={`custom-dropdown pricing-dropdown-select ${open ? "custom-dropdown--open" : ""} ${openUp ? "custom-dropdown--up" : ""} ${className}`.trim()}
    >
      <select
        id={id}
        className="visually-hidden"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-label={ariaLabel}
        disabled={disabled}
        tabIndex={-1}
      >
        {placeholder && !options.some((o) => o.value === "") && (
          <option value="">{placeholder}</option>
        )}
        {options.map((opt) => (
          <option key={opt.value} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>

      <button
        type="button"
        className="custom-dropdown__trigger"
        onClick={toggleOpen}
        onKeyDown={handleKeyDown}
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
      >
        <span className="custom-dropdown__value">{triggerLabel}</span>
        <svg
          className={`custom-dropdown__arrow ${open ? "custom-dropdown__arrow--open" : ""}`}
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>

      {open && (
        <ul className="custom-dropdown__menu" role="listbox">
          {placeholder && !options.some((o) => o.value === "") && (
            <li
              role="option"
              aria-selected={value === ""}
              className={`custom-dropdown__item ${value === "" ? "custom-dropdown__item--selected" : ""}`}
              onClick={() => {
                onChange("");
                setOpen(false);
              }}
            >
              <span className="custom-dropdown__item-label">{placeholder}</span>
              {value === "" && (
                <svg className="custom-dropdown__checkmark" viewBox="0 0 24 24" aria-hidden="true">
                  <polyline points="20 6 9 17 4 12" />
                </svg>
              )}
            </li>
          )}
          {options.map((opt) => {
            const isSelected = opt.value === value;
            return (
              <li
                key={opt.value}
                role="option"
                aria-selected={isSelected}
                className={`custom-dropdown__item ${isSelected ? "custom-dropdown__item--selected" : ""}`}
                onClick={() => {
                  onChange(opt.value);
                  setOpen(false);
                }}
              >
                <span className="custom-dropdown__item-label">{opt.label}</span>
                {isSelected && (
                  <svg className="custom-dropdown__checkmark" viewBox="0 0 24 24" aria-hidden="true">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
