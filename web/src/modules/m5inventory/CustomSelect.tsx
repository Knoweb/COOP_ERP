import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from "react";
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
  const [activeIndex, setActiveIndex] = useState(-1);
  const [portalCoords, setPortalCoords] = useState<PortalCoords | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const portalRef = useRef<HTMLUListElement>(null);
  const optionRefs = useRef<Array<HTMLLIElement | null>>([]);
  const generatedId = useId().replace(/:/g, "");
  const listboxId = `${id ?? `custom-select-${generatedId}`}-listbox`;
  const effectiveAriaLabel = ariaLabelProp ?? ariaLabel;
  const visibleOptions = options.filter((opt) => !(String(opt.value) === "" && opt.label.trim() === ""));
  const hasPlaceholderRow = Boolean(placeholder) && !visibleOptions.some((o) => String(o.value) === "");
  const selectableOptions = hasPlaceholderRow
    ? [{ value: "" as T, label: placeholder! }, ...visibleOptions]
    : visibleOptions;

  const updatePlacement = useCallback(() => {
    if (!containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const spaceBelow = window.innerHeight - rect.bottom;
    const spaceAbove = rect.top;
    const menuHeight = portalRef.current?.getBoundingClientRect().height ?? 200;
    const shouldOpenUp = spaceBelow < menuHeight && spaceAbove > spaceBelow;
    setOpenUp(shouldOpenUp);
    if (portal) {
      const viewportGap = 8;
      const menuWidth = portalRef.current?.getBoundingClientRect().width ?? rect.width;
      const safeLeft = Math.min(
        Math.max(viewportGap, rect.left),
        Math.max(viewportGap, window.innerWidth - menuWidth - viewportGap)
      );
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

  const matchingIndex = selectableOptions.findIndex((option) => option.value === value);
  const selectedIndex = matchingIndex >= 0 ? matchingIndex : selectableOptions.length > 0 ? 0 : -1;

  const openMenu = () => {
    updatePlacement();
    setActiveIndex(selectedIndex);
    setOpen(true);
  };

  const closeMenu = useCallback((restoreFocus = false) => {
    setOpen(false);
    if (restoreFocus) {
      triggerRef.current?.focus();
    }
  }, []);

  useEffect(() => {
    if (!open) setActiveIndex(selectedIndex);
  }, [open, selectedIndex]);

  const toggleOpen = (e?: ReactMouseEvent<HTMLButtonElement>) => {
    if (e) {
      e.preventDefault();
      e.stopPropagation();
    }
    if (disabled) return;
    if (open) {
      closeMenu();
    } else {
      openMenu();
    }
  };

  useLayoutEffect(() => {
    if (!open) return;
    if (portal) updatePlacement();
    optionRefs.current[activeIndex]?.focus({ preventScroll: true });
  }, [activeIndex, open, portal, updatePlacement]);

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
        closeMenu(true);
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
  }, [closeMenu, open, portal, updatePlacement]);

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
      openMenu();
    }
  };

  const selectOption = (nextValue: T) => {
    onChange(nextValue);
    closeMenu(true);
  };

  const handleSelectOption = (e: ReactMouseEvent<HTMLLIElement>, nextValue: T) => {
    e.preventDefault();
    e.stopPropagation();
    selectOption(nextValue);
  };

  const handleListboxKeyDown = (e: KeyboardEvent<HTMLUListElement>) => {
    if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      closeMenu(true);
      return;
    }
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      const activeOption = selectableOptions[activeIndex];
      if (activeOption) selectOption(activeOption.value);
      return;
    }
    if (e.key === "ArrowDown" || e.key === "ArrowUp" || e.key === "Home" || e.key === "End") {
      e.preventDefault();
      if (selectableOptions.length === 0) return;
      if (e.key === "Home") {
        setActiveIndex(0);
      } else if (e.key === "End") {
        setActiveIndex(selectableOptions.length - 1);
      } else {
        const direction = e.key === "ArrowDown" ? 1 : -1;
        setActiveIndex((current) => (current + direction + selectableOptions.length) % selectableOptions.length);
      }
    }
  };

  const menuNode = open && (
    <ul
      id={listboxId}
      ref={portal ? portalRef : undefined}
      className={`custom-dropdown__menu inventory-dropdown-menu ${portal ? "inventory-dropdown-portal" : ""} ${openUp ? "custom-dropdown__menu--up" : ""}`.trim()}
      role="listbox"
      aria-label={effectiveAriaLabel}
      style={portal && portalCoords ? portalCoords : undefined}
      onKeyDown={handleListboxKeyDown}
      onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
      }}
    >
      {hasPlaceholderRow && placeholder && (
        <li
          ref={(element) => {
            optionRefs.current[0] = element;
          }}
          role="option"
          aria-selected={String(value) === ""}
          tabIndex={activeIndex === 0 ? 0 : -1}
          className={`custom-dropdown__item ${String(value) === "" ? "custom-dropdown__item--selected" : ""}`}
          onClick={(e) => handleSelectOption(e, "" as T)}
          onMouseEnter={() => setActiveIndex(0)}
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
      {visibleOptions.map((opt, optionIndex) => {
        const isSelected = opt.value === value;
        const selectableIndex = optionIndex + (hasPlaceholderRow ? 1 : 0);
        return (
          <li
            key={opt.value}
            ref={(element) => {
              optionRefs.current[selectableIndex] = element;
            }}
            role="option"
            aria-selected={isSelected}
            tabIndex={activeIndex === selectableIndex ? 0 : -1}
            className={`custom-dropdown__item ${isSelected ? "custom-dropdown__item--selected" : ""}`}
            onClick={(e) => handleSelectOption(e, opt.value)}
            onMouseEnter={() => setActiveIndex(selectableIndex)}
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
        ref={triggerRef}
        type="button"
        className="custom-dropdown__trigger"
        onClick={toggleOpen}
        onKeyDown={handleKeyDown}
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listboxId : undefined}
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

