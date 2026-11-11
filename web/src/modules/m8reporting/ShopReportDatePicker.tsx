import { useCallback, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";

export interface ShopReportPickerProps {
  id?: string;
  value: string;
  onChange: (value: string) => void;
  ariaLabel: string;
  disabled?: boolean;
}

const WEEKDAY_INDICES = [0, 1, 2, 3, 4, 5, 6] as const;
const MONTH_NUMBERS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12] as const;

function daysInMonthUtc(year: number, monthIndex: number): number {
  return new Date(Date.UTC(year, monthIndex + 1, 0)).getUTCDate();
}

function parseYmd(val: string): [number, number, number] | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(val)) return null;
  const [year, month, day] = val.split("-").map(Number);
  if (month < 1 || month > 12) return null;
  if (day < 1 || day > daysInMonthUtc(year, month - 1)) return null;
  return [year, month - 1, day];
}

function parseYm(val: string): [number, number] | null {
  if (!/^\d{4}-\d{2}$/.test(val)) return null;
  const [year, month] = val.split("-").map(Number);
  if (month < 1 || month > 12) return null;
  return [year, month - 1];
}

function pad2(n: number): string {
  return String(n).padStart(2, "0");
}

function useCdpPopover(disabled?: boolean) {
  const [open, setOpen] = useState(false);
  const [openUp, setOpenUp] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

  const close = useCallback(() => setOpen(false), []);

  const toggleOpen = () => {
    if (disabled) return;
    if (!open && wrapRef.current) {
      const rect = wrapRef.current.getBoundingClientRect();
      const spaceBelow = window.innerHeight - rect.bottom;
      const spaceAbove = rect.top;
      setOpenUp(spaceBelow < 300 && spaceAbove > spaceBelow);
    }
    setOpen((prev) => !prev);
  };

  useEffect(() => {
    if (!open) return;
    const handleOutside = (event: MouseEvent | TouchEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(event.target as Node)) {
        close();
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        close();
      }
    };
    document.addEventListener("mousedown", handleOutside);
    document.addEventListener("touchstart", handleOutside);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handleOutside);
      document.removeEventListener("touchstart", handleOutside);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open, close]);

  return { open, openUp, wrapRef, close, toggleOpen };
}

interface CdpShellProps extends ShopReportPickerProps {
  displayLabel: string;
  hasValue: boolean;
  headerTitle: string;
  prevAriaLabel: string;
  nextAriaLabel: string;
  onPrev: () => void;
  onNext: () => void;
  popover: ReturnType<typeof useCdpPopover>;
  children: ReactNode;
}

/** Shared compact `.cdp` trigger and calendar popup matching `CustomDatePicker`. */
function CdpShell({
  id,
  value,
  onChange,
  ariaLabel,
  disabled,
  displayLabel,
  hasValue,
  headerTitle,
  prevAriaLabel,
  nextAriaLabel,
  onPrev,
  onNext,
  popover,
  children
}: CdpShellProps) {
  const { open, openUp, wrapRef, toggleOpen } = popover;

  return (
    <div
      ref={wrapRef}
      className={`cdp${open ? " cdp--open" : ""}${openUp ? " cdp--up" : ""}${hasValue ? " cdp--filled" : ""}${disabled ? " cdp--disabled" : ""}`}
    >
      <input
        id={id}
        type="text"
        className="visually-hidden"
        value={value}
        aria-label={ariaLabel}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        tabIndex={-1}
      />
      <button
        type="button"
        className="cdp__trigger"
        aria-haspopup="dialog"
        aria-expanded={open}
        disabled={disabled}
        onClick={toggleOpen}
      >
        <span className="cdp__trigger-text">{displayLabel}</span>
        <svg className="cdp__cal-icon" viewBox="0 0 20 20" fill="none" aria-hidden="true">
          <rect x="3" y="4" width="14" height="13" rx="2" stroke="currentColor" strokeWidth="1.5" />
          <path d="M7 2v4M13 2v4M3 8h14" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
      </button>

      {open && (
        <div className="cdp__popup" role="dialog" aria-label={ariaLabel}>
          <div className="cdp__header">
            <button
              type="button"
              className="cdp__nav"
              aria-label={prevAriaLabel}
              onClick={onPrev}
            >
              <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M10 12L6 8l4-4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>
            <span className="cdp__month-label">{headerTitle}</span>
            <button
              type="button"
              className="cdp__nav"
              aria-label={nextAriaLabel}
              onClick={onNext}
            >
              <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M6 4l4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>
          </div>
          {children}
        </div>
      )}
    </div>
  );
}

/** Custom calendar date picker (`YYYY-MM-DD`) matching `CustomDatePicker` (`.cdp`). */
export function ShopReportDatePicker({
  id,
  value,
  onChange,
  ariaLabel,
  disabled
}: ShopReportPickerProps) {
  const t = useT();
  const formatDate = useFormatDate();
  const popover = useCdpPopover(disabled);

  const todayYmd = businessToday();
  const [todayYear, todayMonth, todayDay] = parseYmd(todayYmd) ?? [2026, 0, 1];
  const selectedParts = parseYmd(value);

  const [viewYear, setViewYear] = useState<number>(() =>
    selectedParts ? selectedParts[0] : todayYear
  );
  const [viewMonth, setViewMonth] = useState<number>(() =>
    selectedParts ? selectedParts[1] : todayMonth
  );

  useEffect(() => {
    const parts = parseYmd(value);
    if (parts) {
      setViewYear(parts[0]);
      setViewMonth(parts[1]);
    }
  }, [value]);

  const prevMonth = () => {
    if (viewMonth === 0) {
      setViewYear((y) => y - 1);
      setViewMonth(11);
    } else {
      setViewMonth((m) => m - 1);
    }
  };

  const nextMonth = () => {
    if (viewMonth === 11) {
      setViewYear((y) => y + 1);
      setViewMonth(0);
    } else {
      setViewMonth((m) => m + 1);
    }
  };

  const pick = (day: number) => {
    if (disabled) return;
    onChange(`${viewYear}-${pad2(viewMonth + 1)}-${pad2(day)}`);
    popover.close();
  };

  const firstDay = new Date(Date.UTC(viewYear, viewMonth, 1)).getUTCDay();
  const daysInMonth = daysInMonthUtc(viewYear, viewMonth);
  const cells: (number | null)[] = [
    ...Array(firstDay).fill(null),
    ...Array.from({ length: daysInMonth }, (_, i) => i + 1)
  ];
  while (cells.length % 7 !== 0) {
    cells.push(null);
  }

  const displayLabel = selectedParts ? formatDate(value) : "";
  const monthName = t(`reporting.shop_reports.month.${viewMonth + 1}`).text;

  return (
    <CdpShell
      id={id}
      value={value}
      onChange={onChange}
      ariaLabel={ariaLabel}
      disabled={disabled}
      displayLabel={displayLabel}
      hasValue={Boolean(selectedParts)}
      headerTitle={`${monthName} ${viewYear}`}
      prevAriaLabel={t("reporting.shop_reports.picker.prev_month").text}
      nextAriaLabel={t("reporting.shop_reports.picker.next_month").text}
      onPrev={prevMonth}
      onNext={nextMonth}
      popover={popover}
    >
      <div className="cdp__grid">
        {WEEKDAY_INDICES.map((idx) => (
          <span key={idx} className="cdp__day-name">
            {t(`reporting.shop_reports.weekday.${idx}`).text}
          </span>
        ))}
        {cells.map((day, idx) => {
          if (day === null) {
            return <span key={`e${idx}`} />;
          }
          const isSelected = Boolean(
            selectedParts &&
              selectedParts[0] === viewYear &&
              selectedParts[1] === viewMonth &&
              selectedParts[2] === day
          );
          const isToday =
            todayYear === viewYear && todayMonth === viewMonth && todayDay === day;

          let cls = "cdp__day";
          if (isSelected) cls += " cdp__day--selected";
          else if (isToday) cls += " cdp__day--today";

          return (
            <button
              key={day}
              type="button"
              className={cls}
              onClick={() => pick(day)}
              aria-pressed={isSelected}
            >
              {day}
            </button>
          );
        })}
      </div>
    </CdpShell>
  );
}

/** Custom month picker (`YYYY-MM`) styled with `.cdp` to match `CustomDatePicker`. */
export function ShopReportMonthPicker({
  id,
  value,
  onChange,
  ariaLabel,
  disabled
}: ShopReportPickerProps) {
  const t = useT();
  const popover = useCdpPopover(disabled);

  const todayYm = businessToday().slice(0, 7);
  const [todayYear, todayMonth] = parseYm(todayYm) ?? [2026, 0];
  const selectedParts = parseYm(value);

  const [viewYear, setViewYear] = useState<number>(() =>
    selectedParts ? selectedParts[0] : todayYear
  );

  useEffect(() => {
    const parts = parseYm(value);
    if (parts) {
      setViewYear(parts[0]);
    }
  }, [value]);

  const pickMonth = (monthIndex: number) => {
    if (disabled) return;
    onChange(`${viewYear}-${pad2(monthIndex + 1)}`);
    popover.close();
  };

  const displayLabel = selectedParts
    ? `${t(`reporting.shop_reports.month.${selectedParts[1] + 1}`).text} ${selectedParts[0]}`
    : "";

  return (
    <CdpShell
      id={id}
      value={value}
      onChange={onChange}
      ariaLabel={ariaLabel}
      disabled={disabled}
      displayLabel={displayLabel}
      hasValue={Boolean(selectedParts)}
      headerTitle={String(viewYear)}
      prevAriaLabel={t("reporting.shop_reports.picker.prev_year").text}
      nextAriaLabel={t("reporting.shop_reports.picker.next_year").text}
      onPrev={() => setViewYear((y) => y - 1)}
      onNext={() => setViewYear((y) => y + 1)}
      popover={popover}
    >
      <div className="cdp__month-grid">
        {MONTH_NUMBERS.map((monthNum) => {
          const monthIndex = monthNum - 1;
          const isSelected = Boolean(
            selectedParts &&
              selectedParts[0] === viewYear &&
              selectedParts[1] === monthIndex
          );
          const isCurrent = todayYear === viewYear && todayMonth === monthIndex;
          const fullLabel = t(`reporting.shop_reports.month.${monthNum}`).text;
          const shortLabel = t(`reporting.shop_reports.month_short.${monthNum}`).text;

          let cls = "cdp__day cdp__day--month";
          if (isSelected) cls += " cdp__day--selected";
          else if (isCurrent) cls += " cdp__day--today";

          return (
            <button
              key={monthNum}
              type="button"
              className={cls}
              aria-label={fullLabel}
              aria-pressed={isSelected}
              onClick={() => pickMonth(monthIndex)}
            >
              {shortLabel}
            </button>
          );
        })}
      </div>
    </CdpShell>
  );
}
