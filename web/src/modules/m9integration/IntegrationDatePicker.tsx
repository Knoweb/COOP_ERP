import { useCallback, useEffect, useRef, useState } from "react";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";

export interface IntegrationDatePickerProps {
  id: string;
  value: string; // YYYY-MM-DD or ""
  onChange: (value: string) => void;
  ariaLabel: string;
  required?: boolean;
  disabled?: boolean;
}

const WEEKDAY_INDICES = [0, 1, 2, 3, 4, 5, 6] as const;

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

function pad2(n: number): string {
  return String(n).padStart(2, "0");
}

/** Custom compact `.cdp` calendar date picker (`YYYY-MM-DD`, displayed via `useFormatDate`). */
export function IntegrationDatePicker({
  id,
  value,
  onChange,
  ariaLabel,
  required,
  disabled
}: IntegrationDatePickerProps) {
  const t = useT();
  const formatDate = useFormatDate();

  const todayYmd = businessToday();
  const [todayYear, todayMonth, todayDay] = parseYmd(todayYmd) ?? [2026, 0, 1];
  const selectedParts = parseYmd(value);

  const [open, setOpen] = useState(false);
  const [openUp, setOpenUp] = useState(false);
  const [viewYear, setViewYear] = useState<number>(() =>
    selectedParts ? selectedParts[0] : todayYear
  );
  const [viewMonth, setViewMonth] = useState<number>(() =>
    selectedParts ? selectedParts[1] : todayMonth
  );

  const wrapRef = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);

  useEffect(() => {
    const parts = parseYmd(value);
    if (parts) {
      setViewYear(parts[0]);
      setViewMonth(parts[1]);
    }
  }, [value]);

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

  const toggleOpen = () => {
    if (disabled) return;
    if (!open && wrapRef.current) {
      const rect = wrapRef.current.getBoundingClientRect();
      const spaceBelow = window.innerHeight - rect.bottom;
      const spaceAbove = rect.top;
      setOpenUp(spaceBelow < 300 && spaceAbove > spaceBelow);
      if (selectedParts) {
        setViewYear(selectedParts[0]);
        setViewMonth(selectedParts[1]);
      } else {
        setViewYear(todayYear);
        setViewMonth(todayMonth);
      }
    }
    setOpen((prev) => !prev);
  };

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
    close();
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
  const monthName = t(`integration.month.${viewMonth + 1}`).text;

  return (
    <div
      ref={wrapRef}
      className={`cdp${open ? " cdp--open" : ""}${openUp ? " cdp--up" : ""}${selectedParts ? " cdp--filled" : ""}${disabled ? " cdp--disabled" : ""}`}
    >
      <input
        id={id}
        type="text"
        className="visually-hidden"
        value={value}
        aria-label={ariaLabel}
        required={required}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        tabIndex={-1}
      />
      <button
        type="button"
        className="cdp__trigger"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={displayLabel ? `${ariaLabel}: ${displayLabel}` : ariaLabel}
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
              aria-label={t("integration.datepicker.prev_month").text}
              onClick={prevMonth}
            >
              <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M10 12L6 8l4-4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>
            <span className="cdp__month-label">
              {monthName} {viewYear}
            </span>
            <button
              type="button"
              className="cdp__nav"
              aria-label={t("integration.datepicker.next_month").text}
              onClick={nextMonth}
            >
              <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M6 4l4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>
          </div>

          <div className="cdp__grid">
            {WEEKDAY_INDICES.map((idx) => (
              <span key={idx} className="cdp__day-name">
                {t(`integration.weekday.${idx}`).text}
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
        </div>
      )}
    </div>
  );
}

