import { useEffect, useRef, useState } from "react";


export type DropdownOption<T extends string = string> = {
  value: T;
  label: string;
};

type CatalogueDropdownProps<T extends string = string> = {
  id?: string;
  label?: string;
  value: T;
  onChange: (value: T) => void;
  options: DropdownOption<T>[];
  placeholder?: string;
  disabled?: boolean;
  required?: boolean;
};

/**
 * Reusable custom dropdown with accessible listbox, arrow icon, checkmark on selected option,
 * vertical indicator bar on selected option, and keyboard/click-outside dismissal.
 */
export function CatalogueDropdown<T extends string = string>({
  label,
  value,
  onChange,
  options,
  placeholder,
  disabled,
  required
}: CatalogueDropdownProps<T>) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLUListElement>(null);

  const matched = options.find((opt) => opt.value === value);
  const currentLabel = matched ? matched.label : (placeholder ?? (options[0]?.label || ""));

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

  useEffect(() => {
    if (open && menuRef.current) {
      const selected = menuRef.current.querySelector<HTMLElement>(
        ".register-dropdown-option--selected"
      );
      if (selected) {
        selected.scrollIntoView({ block: "nearest" });
      }
    }
  }, [open]);

  return (
    <div
      className={`catalogue-form-field register-field-group ${open ? "register-field-group--open" : ""}`}
      ref={containerRef}
      style={{ position: "relative" }}
    >
      {label && (
        <span className="catalogue-filter-label" style={{ display: "flex", gap: "var(--space-half)" }}>
          <span>{label}</span>
          {required && <span className="register-field-required">*</span>}
        </span>
      )}

      <button
        type="button"
        className="register-dropdown-trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => setOpen(!open)}
      >
        <span className="custom-status-dropdown__label">{currentLabel}</span>
        <svg
          className="custom-status-dropdown__arrow"
          viewBox="0 0 24 24"
          width="18"
          height="18"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="m7 9 5 5 5-5" />
        </svg>
      </button>

      {open && (
        <ul ref={menuRef} className="register-dropdown-menu" role="listbox">
          {placeholder && (
            <li role="presentation">
              <button
                type="button"
                role="option"
                aria-selected={value === ""}
                className={
                  value === ""
                    ? "register-dropdown-option register-dropdown-option--selected"
                    : "register-dropdown-option"
                }
                onClick={() => {
                  onChange("" as T);
                  setOpen(false);
                }}
              >
                <span className="register-dropdown-option-label">{placeholder}</span>
                {value === "" && (
                  <span className="custom-status-dropdown__check" aria-hidden="true">
                    <svg
                      viewBox="0 0 24 24"
                      width="16"
                      height="16"
                      stroke="currentColor"
                      strokeWidth="2.5"
                      fill="none"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    >
                      <polyline points="20 6 9 17 4 12" />
                    </svg>
                  </span>
                )}
              </button>
            </li>
          )}
          {options.map((opt) => {
            const isSelected = value === opt.value;
            return (
              <li key={String(opt.value)} role="presentation">
                <button
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  className={
                    isSelected
                      ? "register-dropdown-option register-dropdown-option--selected"
                      : "register-dropdown-option"
                  }
                  onClick={() => {
                    onChange(opt.value);
                    setOpen(false);
                  }}
                >
                  <span className="register-dropdown-option-label">{opt.label}</span>
                  {isSelected && (
                    <span className="custom-status-dropdown__check" aria-hidden="true">
                      <svg
                        viewBox="0 0 24 24"
                        width="16"
                        height="16"
                        stroke="currentColor"
                        strokeWidth="2.5"
                        fill="none"
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
    </div>
  );
}
