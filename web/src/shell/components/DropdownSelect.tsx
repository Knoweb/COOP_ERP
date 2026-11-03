import { useState, useRef, useEffect } from "react";
import type { KeyboardEvent } from "react";

export interface DropdownOption {
  value: string;
  label: string;
}

export interface DropdownSelectProps {
  id?: string;
  "aria-label"?: string;
  value: string;
  options: DropdownOption[];
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
export function DropdownSelect({
  id,
  "aria-label": ariaLabel,
  value,
  options,
  onChange,
  placeholder,
  className = "",
  disabled = false
}: DropdownSelectProps) {
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const selectedOption = options.find((opt) => opt.value === value);
  const displayText = selectedOption?.label ?? placeholder ?? (options[0]?.label || "");

  // Close when clicking outside
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, []);

  const handleKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (disabled) return;
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      setIsOpen((prev) => !prev);
    } else if (e.key === "Escape") {
      setIsOpen(false);
    }
  };

  const handleSelect = (optValue: string) => {
    onChange(optValue);
    setIsOpen(false);
  };

  return (
    <div
      ref={containerRef}
      className={`dropdown-select ${isOpen ? "dropdown-select--open" : ""} ${disabled ? "dropdown-select--disabled" : ""} ${className}`.trim()}
      onKeyDown={handleKeyDown}
    >
      {/* Hidden native select for accessibility & Playwright selectOption support */}
      <select
        id={id}
        aria-label={ariaLabel}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        tabIndex={-1}
        className="dropdown-select__native"
      >
        {options.map((opt) => (
          <option key={opt.value} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>

      {/* Custom Trigger */}
      <div
        className={`dropdown-select__trigger ${isOpen ? "dropdown-select__trigger--active" : ""}`}
        onClick={() => !disabled && setIsOpen((prev) => !prev)}
        role="combobox"
        aria-expanded={isOpen}
        aria-haspopup="listbox"
        tabIndex={disabled ? -1 : 0}
      >
        <span className="dropdown-select__label">{displayText}</span>
        <svg
          className={`dropdown-select__chevron ${isOpen ? "dropdown-select__chevron--open" : ""}`}
          viewBox="0 0 24 24"
          aria-hidden="true"
          focusable="false"
        >
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </div>

      {/* Custom Floating Menu */}
      {isOpen && (
        <ul className="dropdown-select__menu" role="listbox">
          {options.map((opt) => {
            const isSelected = opt.value === value;
            return (
              <li
                key={opt.value}
                role="option"
                aria-selected={isSelected}
                className={`dropdown-select__item ${isSelected ? "dropdown-select__item--selected" : ""}`}
                onClick={() => handleSelect(opt.value)}
              >
                <div className="dropdown-select__item-content">
                  {isSelected && <span className="dropdown-select__indicator" />}
                  <span className={`dropdown-select__item-text ${isSelected ? "dropdown-select__item-text--selected" : ""}`}>
                    {opt.label}
                  </span>
                </div>
                {isSelected && (
                  <svg
                    className="dropdown-select__check"
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                    focusable="false"
                  >
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
