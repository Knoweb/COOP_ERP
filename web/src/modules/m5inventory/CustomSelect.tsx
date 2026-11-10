import { useCallback, useEffect, useRef, useState } from "react";
import type { KeyboardEvent, MouseEvent as ReactMouseEvent } from "react";
import { createPortal } from "react-dom";

export interface CustomSelectOption<T extends string> {
  value: T;
  label: string;
}

export interface CustomSelectProps<T extends string> {
  id?: string;
  "aria-label"?: string;
  ariaLabel?: string;
  value: T;
  options: CustomSelectOption<T>[];
  onChange: (value: T) => void;
  placeholder?: string;
  className?: string;
  disabled?: boolean;
  required?: boolean;
  portal?: boolean;
  compact?: boolean;
}

interface PortalCoords {
  top?: number;
  bottom?: number;
  left: number;
  minWidth: number;
}

export function CustomSelect<T extends string>({
  id,
  "aria-label": ariaLabelProp,
  ariaLabel,
  value,
  options,
  onChange,
  placeholder,
  className = "",
  disabled = false,
  required = false,
  portal = false,
  compact = false
}: CustomSelectProps<T>) {
  const [open, setOpen] = useState(false);
  const [openUp, setOpenUp] = useState(false);
  const [portalCoords, setPortalCoords] = useState<PortalCoords | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const portalRef = useRef<HTMLUListElement>(null);
  const effectiveAriaLabel = ariaLabelProp ?? ariaLabel;

  const updatePlacement = useCallback(() => {
    if (!containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const spaceBelow = window.innerHeight - rect.bottom;
    const spaceAbove = rect.top;
    const shouldOpenUp = spaceBelow < 200 && spaceAbove > spaceBelow;
    setOpenUp(shouldOpenUp);
    if (portal) {
      const safeLeft = Math.max(0, rect.left);
      if (shouldOpenUp) {
        setPortalCoords({
          bottom: window.innerHeight - rect.top,
          left: safeLeft,
          minWidth: rect.width
        });
      } else {
        setPortalCoords({
          top: rect.bottom,
          left: safeLeft,
          minWidth: rect.width
        });
      }
    }
  }, [portal]);

  const toggleOpen = (e?: ReactMouseEvent<HTMLButtonElement>) => {
    if (e) {
      e.preventDefault();
      e.stopPropagation();
    }
    if (disabled) return;
    if (!open) {
      updatePlacement();
    }
    setOpen((prev) => !prev);
  };

  useEffect(() => {
    if (!open) return;

    const handleClickOutside = (event: MouseEvent | TouchEvent) => {
      const target = event.target as Node;
      if (
        containerRef.current &&
        !containerRef.current.contains(target) &&
        (!portalRef.current || !portalRef.current.contains(target))
      ) {
        setOpen(false);
      }
    };
    const handleKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };
    const handleScrollOrResize = (event: Event) => {
      if (portalRef.current && event.target instanceof Node && portalRef.current.contains(event.target)) {
        return;
      }
      updatePlacement();
    };

    document.addEventListener("mousedown", handleClickOutside);
    document.addEventListener("touchstart", handleClickOutside);
    document.addEventListener("keydown", handleKeyDown);
    if (portal) {
      window.addEventListener("resize", handleScrollOrResize);
      window.addEventListener("scroll", handleScrollOrResize, true);
    }

    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("touchstart", handleClickOutside);
      document.removeEventListener("keydown", handleKeyDown);
      if (portal) {
        window.removeEventListener("resize", handleScrollOrResize);
        window.removeEventListener("scroll", handleScrollOrResize, true);
      }
    };
  }, [open, portal, updatePlacement]);

  const visibleOptions = options.filter((opt) => !(String(opt.value) === "" && opt.label.trim() === ""));
  const hasPlaceholderRow = Boolean(placeholder) && !visibleOptions.some((o) => String(o.value) === "");
  const selectedOption = options.find((opt) => opt.value === value);
  const fallbackLabel = placeholder ?? (visibleOptions[0]?.label || "");
  const triggerLabel =
    selectedOption !== undefined
      ? selectedOption.label || placeholder || "\u00A0"
      : fallbackLabel || "\u00A0";

  const handleKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (disabled) return;
    if (e.key === "Enter" || e.key === " " || e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (!open) {
        updatePlacement();
        setOpen(true);
      } else if (e.key === "Enter" || e.key === " ") {
        setOpen(false);
      }
    }
  };

  const handleSelectOption = (e: ReactMouseEvent<HTMLLIElement>, nextValue: T) => {
    e.preventDefault();
    e.stopPropagation();
    onChange(nextValue);
    setOpen(false);
  };

  const menuNode = open && (
    <ul
      ref={portal ? portalRef : undefined}
      className={`custom-dropdown__menu inventory-dropdown-menu ${portal ? "inventory-dropdown-portal" : ""} ${openUp ? "custom-dropdown__menu--up" : ""}`.trim()}
      role="listbox"
      style={portal && portalCoords ? portalCoords : undefined}
      onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
      }}
    >
      {hasPlaceholderRow && placeholder && (
        <li
          role="option"
          aria-selected={String(value) === ""}
          className={`custom-dropdown__item ${String(value) === "" ? "custom-dropdown__item--selected" : ""}`}
          onClick={(e) => handleSelectOption(e, "" as T)}
        >
          {String(value) === "" ? (
            <span className="custom-dropdown__item-bar" aria-hidden="true" />
          ) : (
            <span className="custom-dropdown__item-spacer" aria-hidden="true" />
          )}
          <span className="custom-dropdown__item-label">{placeholder}</span>
          {String(value) === "" && (
            <svg className="custom-dropdown__checkmark" viewBox="0 0 24 24" aria-hidden="true">
              <path d="M20 6 9 17l-5-5" />
            </svg>
          )}
        </li>
      )}
      {visibleOptions.map((opt) => {
        const isSelected = opt.value === value;
        return (
          <li
            key={opt.value}
            role="option"
            aria-selected={isSelected}
            className={`custom-dropdown__item ${isSelected ? "custom-dropdown__item--selected" : ""}`}
            onClick={(e) => handleSelectOption(e, opt.value)}
          >
            {isSelected ? (
              <span className="custom-dropdown__item-bar" aria-hidden="true" />
            ) : (
              <span className="custom-dropdown__item-spacer" aria-hidden="true" />
            )}
            <span className="custom-dropdown__item-label">{opt.label}</span>
            {isSelected && (
              <svg className="custom-dropdown__checkmark" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M20 6 9 17l-5-5" />
              </svg>
            )}
          </li>
        );
      })}
    </ul>
  );

  return (
    <div
      className={`custom-dropdown inventory-dropdown-select ${compact ? "inventory-dropdown-select--compact" : ""} ${open ? "custom-dropdown--open" : ""} ${openUp ? "custom-dropdown--up" : ""} ${className}`.trim()}
      ref={containerRef}
    >
      <select
        id={id}
        className="visually-hidden"
        value={value}
        onChange={(e) => onChange(e.target.value as T)}
        aria-label={effectiveAriaLabel}
        disabled={disabled}
        required={required}
        tabIndex={-1}
      >
        {placeholder && !options.some((o) => String(o.value) === "") && (
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

      {portal && typeof document !== "undefined" ? createPortal(menuNode, document.body) : menuNode}
    </div>
  );
}

