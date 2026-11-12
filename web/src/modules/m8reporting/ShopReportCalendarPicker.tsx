import { useEffect, useRef, useState } from "react";
import type { MouseEvent as ReactMouseEvent, ReactNode, RefObject } from "react";
import { useIntl } from "react-intl";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";

export interface ShopReportCalendarProps {
  id: string;
  label: string;
  value: string;
  onChange: (nextValue: string) => void;
}

const MONTH_INDICES = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11];
const WEEKDAY_OFFSETS = [0, 1, 2, 3, 4, 5, 6];
const DAY_CELL_COUNT = 42;

function parseIsoDate(value: string): { year: number; month: number; day: number } | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim());
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (month < 1 || month > 12 || day < 1 || day > 31) return null;
  return { year, month, day };
}

function parseYearMonth(value: string): { year: number; month: number } | null {
  const match = /^(\d{4})-(\d{2})$/.exec(value.trim());
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  if (month < 1 || month > 12) return null;
  return { year, month };
}

function formatUtcDate(date: Date): string {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, "0");
  const d = String(date.getUTCDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function buildCalendarDays(viewYear: number, viewMonthOneBased: number): Array<{ iso: string; day: number; outside: boolean }> {
  const firstOfMonth = new Date(Date.UTC(viewYear, viewMonthOneBased - 1, 1));
  const startOffset = firstOfMonth.getUTCDay();
  const gridStart = new Date(Date.UTC(viewYear, viewMonthOneBased - 1, 1 - startOffset));
  const cells: Array<{ iso: string; day: number; outside: boolean }> = [];
  for (let i = 0; i < DAY_CELL_COUNT; i += 1) {
    const cellDate = new Date(Date.UTC(gridStart.getUTCFullYear(), gridStart.getUTCMonth(), gridStart.getUTCDate() + i));
    cells.push({
      iso: formatUtcDate(cellDate),
      day: cellDate.getUTCDate(),
      outside: cellDate.getUTCMonth() !== viewMonthOneBased - 1 || cellDate.getUTCFullYear() !== viewYear
    });
  }
  return cells;
}

function usePopoverDismiss(containerRef: RefObject<HTMLDivElement>, open: boolean, onClose: () => void) {
  useEffect(() => {
    if (!open) return;

    const handleClickOutside = (event: MouseEvent | TouchEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        onClose();
      }
    };
    const handleKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
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
  }, [containerRef, open, onClose]);
}

function CalendarShell({
  containerRef,
  id,
  inputType,
  label,
  value,
  displayText,
  open,
  onToggle,
  onInputChange,
  widePopover = false,
  headerLabel,
  prevLabel,
  nextLabel,
  onPrev,
  onNext,
  clearLabel,
  shortcutLabel,
  onClear,
  onShortcut,
  children
}: {
  containerRef: RefObject<HTMLDivElement>;
  id: string;
  inputType: "date" | "month";
  label: string;
  value: string;
  displayText: string;
  open: boolean;
  onToggle: (e: ReactMouseEvent<HTMLButtonElement>) => void;
  onInputChange: (nextValue: string) => void;
  widePopover?: boolean;
  headerLabel: string;
  prevLabel: string;
  nextLabel: string;
  onPrev: () => void;
  onNext: () => void;
  clearLabel: string;
  shortcutLabel: string;
  onClear: () => void;
  onShortcut: () => void;
  children: ReactNode;
}) {
  return (
    <div
      className={`shop-reports-calendar ${open ? "shop-reports-calendar--open" : ""}`.trim()}
      ref={containerRef}
    >
      <input
        id={id}
        type={inputType}
        className="visually-hidden"
        aria-label={label}
        value={value}
        onChange={(e) => onInputChange(e.target.value)}
        tabIndex={-1}
      />

      <button
        type="button"
        className="shop-reports-calendar__trigger"
        onClick={onToggle}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={`${label}: ${displayText}`}
      >
        <span className="shop-reports-calendar__value">{displayText}</span>
        <span className="shop-reports-calendar__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" focusable="false">
            <rect x="3" y="4" width="18" height="18" rx="2" />
            <line x1="16" y1="2" x2="16" y2="6" />
            <line x1="8" y1="2" x2="8" y2="6" />
            <line x1="3" y1="10" x2="21" y2="10" />
          </svg>
        </span>
      </button>

      {open && (
        <div
          className={`shop-reports-calendar-popover ${widePopover ? "shop-reports-calendar-popover--days" : ""}`.trim()}
          role="dialog"
          aria-label={label}
        >
          <div className="shop-reports-calendar-popover__header">
            <button
              type="button"
              className="shop-reports-calendar-popover__nav"
              aria-label={prevLabel}
              onClick={onPrev}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="m15 18-6-6 6-6" />
              </svg>
            </button>

            <span className="shop-reports-calendar-popover__title">{headerLabel}</span>

            <button
              type="button"
              className="shop-reports-calendar-popover__nav"
              aria-label={nextLabel}
              onClick={onNext}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="m9 18 6-6-6-6" />
              </svg>
            </button>
          </div>

          {children}

          <div className="shop-reports-calendar-popover__footer">
            <button type="button" className="shop-reports-calendar-popover__clear" onClick={onClear}>
              {clearLabel}
            </button>
            <button type="button" className="shop-reports-calendar-popover__today" onClick={onShortcut}>
              {shortcutLabel}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/** Custom COOPFED-themed daily date picker replacing the browser chrome popup. */
export function ShopReportDatePicker({ id, label, value, onChange }: ShopReportCalendarProps) {
  const t = useT();
  const intl = useIntl();
  const formatDate = useFormatDate();
  const containerRef = useRef<HTMLDivElement>(null);

  const todayStr = businessToday();
  const todayParsed = parseIsoDate(todayStr) ?? { year: 2026, month: 1, day: 1 };
  const selectedParsed = parseIsoDate(value);

  const [open, setOpen] = useState(false);
  const [viewYear, setViewYear] = useState<number>(selectedParsed?.year ?? todayParsed.year);
  const [viewMonth, setViewMonth] = useState<number>(selectedParsed?.month ?? todayParsed.month);

  usePopoverDismiss(containerRef, open, () => setOpen(false));

  const displayText = selectedParsed ? formatDate(value) : "—";
  const headerLabel = intl.formatDate(new Date(Date.UTC(viewYear, viewMonth - 1, 1)), {
    month: "long",
    year: "numeric",
    timeZone: "UTC"
  });

  const shiftMonth = (delta: number) => {
    const shifted = new Date(Date.UTC(viewYear, viewMonth - 1 + delta, 1));
    setViewYear(shifted.getUTCFullYear());
    setViewMonth(shifted.getUTCMonth() + 1);
  };

  const days = buildCalendarDays(viewYear, viewMonth);

  return (
    <CalendarShell
      containerRef={containerRef}
      id={id}
      inputType="date"
      label={label}
      value={value}
      displayText={displayText}
      open={open}
      onToggle={(e) => {
        e.preventDefault();
        e.stopPropagation();
        if (!open) {
          setViewYear(selectedParsed?.year ?? todayParsed.year);
          setViewMonth(selectedParsed?.month ?? todayParsed.month);
        }
        setOpen((prev) => !prev);
      }}
      onInputChange={onChange}
      widePopover
      headerLabel={headerLabel}
      prevLabel={t("reporting.shop_reports.picker.prev_month").text}
      nextLabel={t("reporting.shop_reports.picker.next_month").text}
      onPrev={() => shiftMonth(-1)}
      onNext={() => shiftMonth(1)}
      clearLabel={t("reporting.shop_reports.picker.clear").text}
      shortcutLabel={t("reporting.shop_reports.picker.today").text}
      onClear={() => {
        onChange("");
        setOpen(false);
      }}
      onShortcut={() => {
        onChange(todayStr);
        setViewYear(todayParsed.year);
        setViewMonth(todayParsed.month);
        setOpen(false);
      }}
    >
      <div className="shop-reports-date-weekdays" aria-hidden="true">
        {WEEKDAY_OFFSETS.map((offset) => (
          <span key={offset} className="shop-reports-date-weekdays__cell">
            {intl.formatDate(new Date(Date.UTC(2026, 0, 4 + offset)), {
              weekday: "narrow",
              timeZone: "UTC"
            })}
          </span>
        ))}
      </div>

      <div className="shop-reports-date-grid" role="grid" aria-label={headerLabel}>
        {days.map((cell) => {
          const isSelected = cell.iso === value;
          const isToday = cell.iso === todayStr;
          return (
            <button
              key={cell.iso}
              type="button"
              role="gridcell"
              aria-selected={isSelected}
              aria-label={formatDate(cell.iso)}
              className={`shop-reports-date-grid__btn ${cell.outside ? "shop-reports-date-grid__btn--outside" : ""} ${isSelected ? "shop-reports-date-grid__btn--selected" : ""} ${!isSelected && isToday ? "shop-reports-date-grid__btn--today" : ""}`.trim()}
              onClick={() => {
                onChange(cell.iso);
                setOpen(false);
              }}
            >
              {cell.day}
            </button>
          );
        })}
      </div>
    </CalendarShell>
  );
}

/** Custom COOPFED-themed monthly picker replacing the browser chrome popup. */
export function ShopReportMonthPicker({ id, label, value, onChange }: ShopReportCalendarProps) {
  const t = useT();
  const intl = useIntl();
  const containerRef = useRef<HTMLDivElement>(null);

  const currentMonthStr = businessToday().slice(0, 7);
  const currentParsed = parseYearMonth(currentMonthStr) ?? { year: 2026, month: 1 };
  const selectedParsed = parseYearMonth(value);

  const [open, setOpen] = useState(false);
  const [viewYear, setViewYear] = useState<number>(selectedParsed?.year ?? currentParsed.year);

  usePopoverDismiss(containerRef, open, () => setOpen(false));

  const displayText = selectedParsed
    ? intl.formatDate(new Date(Date.UTC(selectedParsed.year, selectedParsed.month - 1, 1)), {
        month: "long",
        year: "numeric",
        timeZone: "UTC"
      })
    : "—";

  return (
    <CalendarShell
      containerRef={containerRef}
      id={id}
      inputType="month"
      label={label}
      value={value}
      displayText={displayText}
      open={open}
      onToggle={(e) => {
        e.preventDefault();
        e.stopPropagation();
        if (!open) {
          setViewYear(selectedParsed?.year ?? currentParsed.year);
        }
        setOpen((prev) => !prev);
      }}
      onInputChange={onChange}
      headerLabel={String(viewYear)}
      prevLabel={t("reporting.shop_reports.picker.prev_year").text}
      nextLabel={t("reporting.shop_reports.picker.next_year").text}
      onPrev={() => setViewYear((y) => y - 1)}
      onNext={() => setViewYear((y) => y + 1)}
      clearLabel={t("reporting.shop_reports.picker.clear").text}
      shortcutLabel={t("reporting.shop_reports.picker.this_month").text}
      onClear={() => {
        onChange("");
        setOpen(false);
      }}
      onShortcut={() => {
        onChange(currentMonthStr);
        setViewYear(currentParsed.year);
        setOpen(false);
      }}
    >
      <div className="shop-reports-month-grid" role="grid" aria-label={String(viewYear)}>
        {MONTH_INDICES.map((monthIdx) => {
          const isSelected =
            selectedParsed?.year === viewYear && selectedParsed.month === monthIdx + 1;
          const isCurrent =
            currentParsed.year === viewYear && currentParsed.month === monthIdx + 1;
          const shortLabel = intl.formatDate(
            new Date(Date.UTC(viewYear, monthIdx, 1)),
            { month: "short", timeZone: "UTC" }
          );
          return (
            <button
              key={monthIdx}
              type="button"
              role="gridcell"
              aria-selected={isSelected}
              className={`shop-reports-month-grid__btn ${isSelected ? "shop-reports-month-grid__btn--selected" : ""} ${!isSelected && isCurrent ? "shop-reports-month-grid__btn--current" : ""}`.trim()}
              onClick={() => {
                onChange(`${viewYear}-${String(monthIdx + 1).padStart(2, "0")}`);
                setOpen(false);
              }}
            >
              {shortLabel}
            </button>
          );
        })}
      </div>
    </CalendarShell>
  );
}

