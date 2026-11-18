import { useState, useRef, useEffect } from "react";
import type { KeyboardEvent } from "react";

export interface DropdownOption {
  value: string;
  label: string;
}

export interface DropdownSelectProps {
  id?: string;
  "aria-label"?: string;
  ariaLabel?: string;
  value: string;
  options: DropdownOption[];
  onChange: (value: string) => void;
  placeholder?: string;
  className?: string;
  disabled?: boolean;
  required?: boolean;
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
  "aria-label": ariaLabelProp,
  ariaLabel: ariaLabelProp2,
  value,
  options,
  onChange,
  placeholder,
  className = "",
  disabled = false,
  required = false
}: DropdownSelectProps) {
  const effectiveAriaLabel = ariaLabelProp ?? ariaLabelProp2;
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLUListElement>(null);

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

  // Scroll selected item into view when menu opens
  useEffect(() => {
    if (isOpen && menuRef.current) {
      const selected = menuRef.current.querySelector<HTMLElement>(".dropdown-select__item--selected");
      if (selected) {
        selected.scrollIntoView({ block: "nearest" });
      }
    }
  }, [isOpen]);

  const handleKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (disabled) return;
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      setIsOpen((prev) => !prev);
    } else if (e.key === "Escape") {
      if (isOpen) {
        e.stopPropagation();
        setIsOpen(false);
      }
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      if (!isOpen) {
        setIsOpen(true);
      } else {
        const currentIndex = options.findIndex((opt) => opt.value === value);
        const nextIndex = currentIndex < options.length - 1 ? currentIndex + 1 : 0;
        if (options[nextIndex]) {
          onChange(options[nextIndex].value);
        }
      }
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      if (!isOpen) {
        setIsOpen(true);
      } else {
        const currentIndex = options.findIndex((opt) => opt.value === value);
        const prevIndex = currentIndex > 0 ? currentIndex - 1 : options.length - 1;
        if (options[prevIndex]) {
          onChange(options[prevIndex].value);
        }
      }
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
      style={{ position: "relative" }}
    >
      {/* Hidden native select for accessibility & Playwright selectOption support */}
      <select
        id={id}
        aria-label={effectiveAriaLabel}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        required={required}
        tabIndex={-1}
        className="dropdown-select__native"
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

      {/* Custom Trigger - visual representation; hidden from AT/test tree to avoid duplicate combobox/label collisions */}
      <div
        className={`dropdown-select__trigger ${isOpen ? "dropdown-select__trigger--active" : ""}`}
        onClick={() => !disabled && setIsOpen((prev) => !prev)}
        aria-hidden="true"
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

      {/* Custom Floating Menu - visual layer synchronized with authoritative native select */}
      {isOpen && (
        <ul ref={menuRef} className="dropdown-select__menu" aria-hidden="true">
          {options.map((opt) => {
            const isSelected = opt.value === value;
            return (
              <li
                key={opt.value}
                className={`dropdown-select__item ${isSelected ? "dropdown-select__item--selected" : ""}`}
                onClick={() => handleSelect(opt.value)}
              >
                <div className="dropdown-select__item-content">
                  <span className={`dropdown-select__indicator ${isSelected ? "dropdown-select__indicator--selected" : ""}`} />
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
