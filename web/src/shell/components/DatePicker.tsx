import { useEffect, useId, useState } from "react";
import type { MouseEvent } from "react";
import { createPortal } from "react-dom";
import { useIntl } from "react-intl";
import { businessToday, useFormatDate } from "../i18n/formats";
import { useT } from "../i18n/useT";
import {
  CalendarIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  isValidIsoDate,
  pad2,
  useCalendarPopup
} from "./calendarShared";

export type DatePickerProps = {
  id?: string;
  label?: string;
  prefixLabel?: string;
  ariaLabel?: string;
  triggerAriaLabel?: string;
  value: string; // YYYY-MM-DD
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  required?: boolean;
  min?: string; // YYYY-MM-DD
  max?: string; // YYYY-MM-DD
  portal?: boolean;
  portalClassName?: string;
  className?: string;
  compact?: boolean;
  displayValueMode?: "iso" | "formatted";
};

function formatIso(year: number, monthIndex: number, day: number): string {
  return `${year}-${pad2(monthIndex + 1)}-${pad2(day)}`;
}

export function DatePicker({
  id,
  label,
  prefixLabel,
  ariaLabel,
  triggerAriaLabel,
  value,
  onChange,
  placeholder,
  disabled = false,
  required = false,
  min,
  max,
  portal = true,
  portalClassName,
  className,
  compact = false,
  displayValueMode = "iso"
}: DatePickerProps) {
  const autoId = useId();
  const inputId = id || `datepicker-${autoId}`;
  const t = useT();
  const intl = useIntl();
  const formatDate = useFormatDate();

  const {
    open,
    setOpen,
    openUpward,
    popupStyle,
    containerRef,
    triggerRef,
    popupRef
  } = useCalendarPopup(portal);

  const parsedDate = (() => {
    if (!value) return null;
    const parts = value.split("-").map(Number);
    if (parts.length === 3 && !isNaN(parts[0]) && !isNaN(parts[1]) && !isNaN(parts[2])) {
      return { year: parts[0], month: parts[1] - 1, day: parts[2] };
    }
    return null;
  })();

  const todayStr = businessToday();
  const todayParts = todayStr.split("-").map(Number);
  const [viewYear, setViewYear] = useState<number>(parsedDate ? parsedDate.year : todayParts[0]);
  const [viewMonth, setViewMonth] = useState<number>(parsedDate ? parsedDate.month : todayParts[1] - 1);

  useEffect(() => {
    if (!value) return;
    const parts = value.split("-").map(Number);
    if (parts.length === 3 && !isNaN(parts[0]) && !isNaN(parts[1]) && !isNaN(parts[2])) {
      setViewYear(parts[0]);
      setViewMonth(parts[1] - 1);
    }
  }, [value]);

  const handleToggle = () => {
    if (disabled) return;
    if (!open) {
      if (parsedDate) {
        setViewYear(parsedDate.year);
        setViewMonth(parsedDate.month);
      } else {
        setViewYear(todayParts[0]);
        setViewMonth(todayParts[1] - 1);
      }
    }
    setOpen((prev) => !prev);
  };

  const handlePrevMonth = (e: MouseEvent) => {
    e.stopPropagation();
    if (viewMonth === 0) {
      setViewMonth(11);
      setViewYear((y) => y - 1);
    } else {
      setViewMonth((m) => m - 1);
    }
  };

  const handleNextMonth = (e: MouseEvent) => {
    e.stopPropagation();
    if (viewMonth === 11) {
      setViewMonth(0);
      setViewYear((y) => y + 1);
    } else {
      setViewMonth((m) => m + 1);
    }
  };

  const handleSelectDay = (day: number) => {
    const iso = formatIso(viewYear, viewMonth, day);
    if (min && iso < min) return;
    if (max && iso > max) return;
    onChange(iso);
    setOpen(false);
  };

  const handleClear = (e: MouseEvent) => {
    e.stopPropagation();
    onChange("");
    setOpen(false);
  };

  const handleToday = (e: MouseEvent) => {
    e.stopPropagation();
    let target = todayStr;
    if (min && target < min) {
      target = min;
    } else if (max && target > max) {
      target = max;
    }
    onChange(target);
    setOpen(false);
  };

  const daysInMonth = new Date(Date.UTC(viewYear, viewMonth + 1, 0)).getUTCDate();
  const firstDayOfWeek = new Date(Date.UTC(viewYear, viewMonth, 1)).getUTCDay();

  const headerTitle = intl.formatDate(new Date(Date.UTC(viewYear, viewMonth, 1)), {
    month: "long",
    year: "numeric",
    timeZone: "UTC"
  });

  const weekdayHeaders = Array.from({ length: 7 }, (_, idx) => {
    const date = new Date(Date.UTC(2023, 0, 1 + idx));
    if (intl.locale.startsWith("en")) {
      return intl.formatDate(date, { weekday: "short", timeZone: "UTC" }).slice(0, 2).toUpperCase();
    }
    return intl.formatDate(date, { weekday: "narrow", timeZone: "UTC" });
  });

  const displayText =
    value && displayValueMode === "formatted" && isValidIsoDate(value)
      ? formatDate(value)
      : value;
  const effectivePlaceholder = placeholder ?? t("shell.datepicker.placeholder").text;
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
      aria-modal={portal ? undefined : true}
      aria-label={dialogLabel}
    >
      <div className="catalogue-calendar-header">
        <button
          type="button"
          className="catalogue-calendar-nav-btn"
          onClick={handlePrevMonth}
          aria-label={t("shell.datepicker.prev_month").text}
        >
          <ChevronLeftIcon />
        </button>

        <span className="catalogue-calendar-title">{headerTitle}</span>

        <button
          type="button"
          className="catalogue-calendar-nav-btn"
          onClick={handleNextMonth}
          aria-label={t("shell.datepicker.next_month").text}
        >
          <ChevronRightIcon />
        </button>
      </div>

      <div className="catalogue-calendar-weekdays" aria-hidden="true">
        {weekdayHeaders.map((day, idx) => (
          <span key={idx} className="catalogue-calendar-weekday">
            {day}
          </span>
        ))}
      </div>

      <div className="catalogue-calendar-grid" role="grid" aria-label={headerTitle}>
        {Array.from({ length: firstDayOfWeek }).map((_, idx) => (
          <div key={`empty-${idx}`} className="catalogue-calendar-empty-slot" />
        ))}

        {Array.from({ length: daysInMonth }).map((_, idx) => {
          const day = idx + 1;
          const iso = formatIso(viewYear, viewMonth, day);
          const isOutOfRange = Boolean((min && iso < min) || (max && iso > max));
          const isSelected = Boolean(
            parsedDate &&
              parsedDate.year === viewYear &&
              parsedDate.month === viewMonth &&
              parsedDate.day === day
          );
          const isToday = iso === todayStr;
          const cellClass = [
            "catalogue-calendar-day",
            isSelected ? "catalogue-calendar-day--selected" : "",
            !isSelected && isToday ? "catalogue-calendar-day--today" : ""
          ]
            .filter(Boolean)
            .join(" ");

          return (
            <button
              key={day}
              type="button"
              role="gridcell"
              disabled={isOutOfRange}
              aria-selected={isSelected}
              aria-label={formatDate(iso)}
              className={cellClass}
              onClick={() => handleSelectDay(day)}
            >
              {day}
            </button>
          );
        })}
      </div>

      <div className="catalogue-calendar-footer">
        <button type="button" className="catalogue-calendar-action" onClick={handleClear}>
          {t("shell.datepicker.clear").text}
        </button>
        <button type="button" className="catalogue-calendar-action" onClick={handleToday}>
          {t("shell.datepicker.today").text}
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
          {prefixLabel && (
            <span className="catalogue-datepicker-trigger__prefix">
              {prefixLabel}
            </span>
          )}
          <span className={displayText ? "catalogue-datepicker-trigger__value" : "catalogue-datepicker-trigger__placeholder"}>
            {displayText || effectivePlaceholder}
          </span>
          <CalendarIcon />
        </button>
      </div>

      {popupNode && (portal && typeof document !== "undefined" ? createPortal(popupNode, document.body) : popupNode)}
    </div>
  );
}
