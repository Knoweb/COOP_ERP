import { useEffect, useRef, useState } from "react";

export type CustomSelectOption = {
  value: string;
  label: string;
};

export function CustomSelect({
  label,
  value,
  onChange,
  options,
  required,
  disabled,
  ariaLabel
}: {
  label?: string;
  value: string;
  onChange: (value: string) => void;
  options: CustomSelectOption[];
  required?: boolean;
  disabled?: boolean;
  ariaLabel?: string;
}) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const currentOption = options.find((opt) => opt.value === value) || options[0] || { value: "", label: "" };

  useEffect(() => {
    if (!open) return;
    const handleClickOutside = (event: MouseEvent | TouchEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    document.addEventListener("touchstart", handleClickOutside);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("touchstart", handleClickOutside);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open]);

  return (
    <div className="custom-status-dropdown customers-custom-dropdown" ref={containerRef}>
      <button
        type="button"
        className="custom-status-dropdown__trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => setOpen((prev) => !prev)}
      >
        <span className="custom-status-dropdown__label">{currentOption.label}</span>
        <svg className="custom-status-dropdown__arrow" viewBox="0 0 24 24" aria-hidden="true">
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>

      {open && (
        <ul className="custom-status-dropdown__menu" role="listbox">
          {options.map((opt) => {
            const isSelected = value === opt.value;
            return (
              <li key={opt.value} role="presentation">
                <button
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  className={
                    isSelected
                      ? "custom-status-dropdown__option custom-status-dropdown__option--selected"
                      : "custom-status-dropdown__option"
                  }
                  onClick={() => {
                    onChange(opt.value);
                    setOpen(false);
                  }}
                >
                  <span className="custom-status-dropdown__option-label">{opt.label}</span>
                  {isSelected && (
                    <span className="custom-status-dropdown__check" aria-hidden="true">
                      <svg
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2.5"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                      >
                        <polyline points="20 6 9 17 4 12" />
                      </svg>
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      )}

      <select
        aria-label={ariaLabel || label}
        className="visually-hidden"
        tabIndex={-1}
        aria-hidden="true"
        value={value}
        required={required}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
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

