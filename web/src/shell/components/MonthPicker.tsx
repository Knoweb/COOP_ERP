import { useEffect, useId, useState } from "react";
import type { MouseEvent } from "react";
import { createPortal } from "react-dom";
import { useIntl } from "react-intl";
import { businessToday } from "../i18n/formats";
import { useT } from "../i18n/useT";
import {
  CalendarIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  pad2,
  useCalendarPopup
} from "./calendarShared";

export type MonthPickerProps = {
  id?: string;
  label?: string;
  ariaLabel?: string;
  triggerAriaLabel?: string;
  value: string; // YYYY-MM
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  required?: boolean;
  portal?: boolean;
  portalClassName?: string;
  className?: string;
  compact?: boolean;
};

const MONTH_INDICES = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11];

function parseYearMonth(value: string): { year: number; month: number } | null {
  const match = /^(\d{4})-(\d{2})$/.exec(value.trim());
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  if (month < 1 || month > 12) return null;
  return { year, month };
}

export function MonthPicker({
  id,
  label,
  ariaLabel,
  triggerAriaLabel,
  value,
  onChange,
  placeholder,
  disabled = false,
  required = false,
  portal = true,
  portalClassName,
  className,
  compact = false
}: MonthPickerProps) {
  const autoId = useId();
  const inputId = id || `monthpicker-${autoId}`;
  const t = useT();
  const intl = useIntl();

  const {
    open,
    setOpen,
    openUpward,
    popupStyle,
    containerRef,
    triggerRef,
    popupRef
  } = useCalendarPopup(portal);

  const currentMonthStr = businessToday().slice(0, 7);
  const currentParsed = parseYearMonth(currentMonthStr) ?? { year: 2026, month: 1 };
  const selectedParsed = parseYearMonth(value);

  const [viewYear, setViewYear] = useState<number>(selectedParsed?.year ?? currentParsed.year);

  useEffect(() => {
    const parsed = parseYearMonth(value);
    if (parsed) {
      setViewYear(parsed.year);
    }
  }, [value]);

  const handleToggle = (e: MouseEvent<HTMLButtonElement>) => {
    if (disabled) return;
    e.preventDefault();
    if (!open) {
      setViewYear(selectedParsed?.year ?? currentParsed.year);
    }
    setOpen((prev) => !prev);
  };

  const handlePickMonth = (monthIndexZeroBased: number) => {
    onChange(`${viewYear}-${pad2(monthIndexZeroBased + 1)}`);
    setOpen(false);
  };

  const formattedMonth = selectedParsed
    ? intl.formatDate(new Date(Date.UTC(selectedParsed.year, selectedParsed.month - 1, 1)), {
        month: "long",
        year: "numeric",
        timeZone: "UTC"
      })
    : "";
  const effectivePlaceholder = placeholder ?? t("shell.datepicker.placeholder_month").text;
  const dialogLabel = label ?? ariaLabel ?? effectivePlaceholder;

  const popupClasses = [
    "catalogue-calendar-popup",
    portal ? "catalogue-calendar-popup--portal" : "",
    openUpward ? "catalogue-calendar-popup--up" : "",
    portalClassName ?? ""
  ]
    .filter(Boolean)
    .join(" ");

  const popupNode = open ? (
    <div
      ref={popupRef}
      className={popupClasses}
      style={portal ? popupStyle : undefined}
      role="dialog"
      aria-label={dialogLabel}
    >
      <div className="catalogue-calendar-header">
        <button
          type="button"
          className="catalogue-calendar-nav-btn"
          onClick={(e) => {
            e.stopPropagation();
            setViewYear((y) => y - 1);
          }}
          aria-label={t("shell.datepicker.prev_year").text}
        >
          <ChevronLeftIcon />
        </button>

        <span className="catalogue-calendar-title">{viewYear}</span>

        <button
          type="button"
          className="catalogue-calendar-nav-btn"
          onClick={(e) => {
            e.stopPropagation();
            setViewYear((y) => y + 1);
          }}
          aria-label={t("shell.datepicker.next_year").text}
        >
          <ChevronRightIcon />
        </button>
      </div>

      <div className="catalogue-calendar-month-grid" role="grid" aria-label={String(viewYear)}>
        {MONTH_INDICES.map((monthIdx) => {
          const isSelected = selectedParsed?.year === viewYear && selectedParsed.month === monthIdx + 1;
          const isCurrent = currentParsed.year === viewYear && currentParsed.month === monthIdx + 1;
          const shortLabel = intl.formatDate(new Date(Date.UTC(viewYear, monthIdx, 1)), {
            month: "short",
            timeZone: "UTC"
          });
          const btnClass = [
            "catalogue-calendar-month",
            isSelected ? "catalogue-calendar-month--selected" : "",
            !isSelected && isCurrent ? "catalogue-calendar-month--current" : ""
          ]
            .filter(Boolean)
            .join(" ");

          return (
            <button
              key={monthIdx}
              type="button"
              role="gridcell"
              aria-selected={isSelected}
              className={btnClass}
              onClick={() => handlePickMonth(monthIdx)}
            >
              {shortLabel}
            </button>
          );
        })}
      </div>

      <div className="catalogue-calendar-footer">
        <button
          type="button"
          className="catalogue-calendar-action"
          onClick={(e) => {
            e.stopPropagation();
            onChange("");
            setOpen(false);
          }}
        >
          {t("shell.datepicker.clear").text}
        </button>
        <button
          type="button"
          className="catalogue-calendar-action"
          onClick={(e) => {
            e.stopPropagation();
            onChange(currentMonthStr);
            setViewYear(currentParsed.year);
            setOpen(false);
          }}
        >
          {t("shell.datepicker.this_month").text}
        </button>
      </div>
    </div>
  ) : null;

  return (
    <div
      className={`catalogue-datepicker-wrap ${compact ? "catalogue-datepicker-wrap--compact" : ""} ${className ?? ""}`.trim()}
      ref={containerRef}
    >
      {label && (
        <label className="catalogue-datepicker-label" htmlFor={inputId}>
          <span>{label}</span>
          {required && <span className="register-field-required" aria-hidden="true">*</span>}
        </label>
      )}

      <div className="catalogue-datepicker-control">
        <input
          id={inputId}
          type="text"
          className="catalogue-datepicker-input"
          aria-label={!label ? ariaLabel : undefined}
          value={value}
          disabled={disabled}
          required={required}
          onChange={(e) => onChange(e.target.value)}
          tabIndex={-1}
        />

        <button
          ref={triggerRef}
          type="button"
          className={`catalogue-datepicker-trigger ${open ? "catalogue-datepicker-trigger--open" : ""} ${compact ? "catalogue-datepicker-trigger--compact" : ""}`.trim()}
          disabled={disabled}
          onClick={handleToggle}
          aria-haspopup="dialog"
          aria-expanded={open}
          aria-label={triggerAriaLabel}
        >
          <span className={formattedMonth ? "catalogue-datepicker-trigger__value" : "catalogue-datepicker-trigger__placeholder"}>
            {formattedMonth || effectivePlaceholder}
          </span>
          <CalendarIcon />
        </button>
      </div>

      {popupNode && (portal && typeof document !== "undefined" ? createPortal(popupNode, document.body) : popupNode)}
    </div>
  );
}
