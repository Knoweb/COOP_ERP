import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent, MouseEvent as ReactMouseEvent } from "react";
import { createPortal } from "react-dom";

export interface CustomSelectOption<T extends string = string> {
  value: T;
  label: string;
  key?: string;
}

export interface CustomSelectProps<T extends string = string> {
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

/**
 * Custom accessible dropdown matching the unified brand palette while keeping a synchronized
 * visually-hidden native <select> so form constraints, Testing Library queries, and Playwright
 * `selectOption` work identically to a standard select.
 */
export function CustomSelect<T extends string = string>({
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
  const [focusedIndex, setFocusedIndex] = useState(-1);
  const [portalCoords, setPortalCoords] = useState<PortalCoords | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const portalRef = useRef<HTMLUListElement>(null);
  const effectiveAriaLabel = ariaLabelProp ?? ariaLabel;

  const visibleOptions = useMemo(
    () => options.filter((opt) => !(String(opt.value) === "" && opt.label.trim() === "")),
    [options]
  );
  const hasPlaceholderRow = Boolean(placeholder) && !visibleOptions.some((o) => String(o.value) === "");
  const selectableOptions: CustomSelectOption<T>[] = useMemo(
    () =>
      hasPlaceholderRow && placeholder
        ? [{ value: "" as T, label: placeholder }, ...visibleOptions]
        : visibleOptions,
    [hasPlaceholderRow, placeholder, visibleOptions]
  );

  const updatePlacement = useCallback(() => {
    if (!containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const spaceBelow = window.innerHeight - rect.bottom;
    const spaceAbove = rect.top;
    const shouldOpenUp = spaceBelow < 220 && spaceAbove > spaceBelow;
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

  const openMenu = useCallback(() => {
    updatePlacement();
    const currentIdx = selectableOptions.findIndex((opt) => opt.value === value);
    setFocusedIndex(currentIdx >= 0 ? currentIdx : 0);
    setOpen(true);
  }, [selectableOptions, updatePlacement, value]);

  const toggleOpen = (e?: ReactMouseEvent<HTMLButtonElement>) => {
    if (e) {
      e.preventDefault();
      e.stopPropagation();
    }
    if (disabled) return;
    if (open) {
      setOpen(false);
    } else {
      openMenu();
    }
  };

  useEffect(() => {
    if (!open) {
      setFocusedIndex(-1);
      return;
    }

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

  const selectedOption = options.find((opt) => opt.value === value);
  const fallbackLabel = placeholder ?? (visibleOptions[0]?.label || "");
  const triggerLabel =
    selectedOption !== undefined
      ? selectedOption.label || placeholder || "\u00A0"
      : fallbackLabel || "\u00A0";

  const handleKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (disabled) return;
    if (e.key === "ArrowDown") {
      e.preventDefault();
      if (!open) {
        openMenu();
      } else if (selectableOptions.length > 0) {
        setFocusedIndex((prev) => (prev + 1) % selectableOptions.length);
      }
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      if (!open) {
        openMenu();
      } else if (selectableOptions.length > 0) {
        setFocusedIndex((prev) => (prev <= 0 ? selectableOptions.length - 1 : prev - 1));
      }
    } else if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      if (!open) {
        openMenu();
      } else {
        const chosen = selectableOptions[focusedIndex];
        if (chosen) {
          onChange(chosen.value);
        }
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
      className={`ui-select__menu ${portal ? "ui-select__menu--portal" : ""} ${openUp ? "ui-select__menu--up" : ""}`.trim()}
      role="listbox"
      style={portal && portalCoords ? portalCoords : undefined}
      onMouseMove={() => setFocusedIndex(-1)}
      onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
      }}
    >
      {selectableOptions.map((opt, idx) => {
        const isSelected = opt.value === value;
        const isFocused = focusedIndex === idx;
        return (
          <li
            key={opt.key ?? String(opt.value)}
            role="option"
            aria-selected={isSelected}
            className={`ui-select__item ${isSelected ? "ui-select__item--selected" : ""} ${isFocused ? "ui-select__item--focused" : ""}`.trim()}
            onClick={(e) => handleSelectOption(e, opt.value)}
          >
            {isSelected ? (
              <span className="ui-select__item-bar" aria-hidden="true" />
            ) : (
              <span className="ui-select__item-spacer" aria-hidden="true" />
            )}
            <span className="ui-select__item-label">{opt.label}</span>
            {isSelected && (
              <svg className="ui-select__check" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
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
      className={`ui-select ${compact ? "ui-select--compact" : ""} ${open ? "ui-select--open" : ""} ${openUp ? "ui-select--up" : ""} ${className}`.trim()}
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
        {hasPlaceholderRow && placeholder && <option value="">{placeholder}</option>}
        {options.map((opt) => (
          <option key={opt.key ?? String(opt.value)} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>

      <button
        type="button"
        className="ui-select__trigger"
        onClick={toggleOpen}
        onKeyDown={handleKeyDown}
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        <span className="ui-select__value">{triggerLabel}</span>
        <svg
          className={`ui-select__chevron ${open ? "ui-select__chevron--open" : ""}`.trim()}
          viewBox="0 0 24 24"
          aria-hidden="true"
          focusable="false"
        >
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>

      {portal && typeof document !== "undefined" ? createPortal(menuNode, document.body) : menuNode}
    </div>
  );
}

