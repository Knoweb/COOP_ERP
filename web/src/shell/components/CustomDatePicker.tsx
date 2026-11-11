import { useState, useRef, useEffect, useCallback } from "react";
import { businessToday, useFormatDate } from "../i18n/formats";
import { useT } from "../i18n/useT";

export interface CustomDatePickerProps {
  value: string; // "YYYY-MM-DD" or ""
  onChange: (value: string) => void;
  ariaLabel?: string;
  required?: boolean;
  min?: string; // "YYYY-MM-DD"
  max?: string; // "YYYY-MM-DD"
  disabled?: boolean;
  id?: string;
}

const DAYS = ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"];

const MONTH_NAMES_FALLBACK = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December"
];

function parseYMD(val: string): [number, number, number] | null {
  if (!val || !/^\d{4}-\d{2}-\d{2}$/.test(val)) return null;
  const parts = val.split("-").map(Number);
  return [parts[0], parts[1] - 1, parts[2]];
}

function toYMD(year: number, month: number, day: number): string {
  const y = year;
  const m = String(month + 1).padStart(2, "0");
  const d = String(day).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

export function CustomDatePicker({
  value,
  onChange,
  ariaLabel,
  required,
  min,
  max,
  disabled,
  id
}: CustomDatePickerProps) {
  const t = useT();
  const formatDate = useFormatDate();

  const todayYmd = businessToday();
  const todayParts = parseYMD(todayYmd) ?? [2026, 0, 1];
  const [todayYear, todayMonth, todayDay] = todayParts;

  const selectedParts = parseYMD(value);

  const [open, setOpen] = useState(false);
  const [openUp, setOpenUp] = useState(false);
  const [viewYear, setViewYear] = useState<number>(() => (selectedParts ? selectedParts[0] : todayYear));
  const [viewMonth, setViewMonth] = useState<number>(() => (selectedParts ? selectedParts[1] : todayMonth));

  // Sync calendar view when value updates externally
  useEffect(() => {
    const parts = parseYMD(value);
    if (parts) {
      setViewYear(parts[0]);
      setViewMonth(parts[1]);
    }
  }, [value]);

  const wrapRef = useRef<HTMLDivElement>(null);

  const toggleOpen = () => {
    if (disabled) return;
    if (!open && wrapRef.current) {
      const rect = wrapRef.current.getBoundingClientRect();
      const spaceBelow = window.innerHeight - rect.bottom;
      const spaceAbove = rect.top;
      setOpenUp(spaceBelow < 300 && spaceAbove > spaceBelow);
    }
    setOpen((o) => !o);
  };

  const close = useCallback(() => setOpen(false), []);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) close();
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [open, close]);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") close();
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [open, close]);

  const prevMonth = () => {
    setViewMonth((m) => {
      if (m === 0) {
        setViewYear((y) => y - 1);
        return 11;
      }
      return m - 1;
    });
  };

  const nextMonth = () => {
    setViewMonth((m) => {
      if (m === 11) {
        setViewYear((y) => y + 1);
        return 0;
      }
      return m + 1;
    });
  };

  const isDayDisabled = (day: number) => {
    const ymd = toYMD(viewYear, viewMonth, day);
    return Boolean((min && ymd < min) || (max && ymd > max));
  };

  const pick = (day: number) => {
    if (disabled || isDayDisabled(day)) return;
    const pickedYmd = toYMD(viewYear, viewMonth, day);
    onChange(pickedYmd);
    close();
  };

  const firstDay = new Date(Date.UTC(viewYear, viewMonth, 1)).getUTCDay();
  const daysInMonth = new Date(Date.UTC(viewYear, viewMonth + 1, 0)).getUTCDate();
  const cells: (number | null)[] = [
    ...Array(firstDay).fill(null),
    ...Array.from({ length: daysInMonth }, (_, i) => i + 1)
  ];
  while (cells.length % 7 !== 0) cells.push(null);

  const displayLabel = value ? formatDate(value) : "";
  const translatedMonth = t(`party.month.${viewMonth + 1}`).text;
  const monthName = translatedMonth.startsWith("party.month.")
    ? MONTH_NAMES_FALLBACK[viewMonth]
    : translatedMonth;

  return (
    <div
      className={`cdp${open ? " cdp--open" : ""}${openUp ? " cdp--up" : ""}${value ? " cdp--filled" : ""}${disabled ? " cdp--disabled" : ""}`}
      ref={wrapRef}
    >
      <input
        type="text"
        className="visually-hidden"
        id={id}
        value={value}
        required={required}
        aria-label={ariaLabel}
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
            <button type="button" className="cdp__nav" onClick={prevMonth}>
              <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M10 12L6 8l4-4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>
            <span className="cdp__month-label">
              {monthName} {viewYear}
            </span>
            <button type="button" className="cdp__nav" onClick={nextMonth}>
              <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M6 4l4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>
          </div>

          <div className="cdp__grid">
            {DAYS.map((d) => (
              <span key={d} className="cdp__day-name">
                {d}
              </span>
            ))}
            {cells.map((day, i) => {
              if (day === null) return <span key={`e${i}`} />;
              const isSelected = selectedParts && selectedParts[0] === viewYear && selectedParts[1] === viewMonth && selectedParts[2] === day;
              const isToday = todayYear === viewYear && todayMonth === viewMonth && todayDay === day;
              const dayDisabled = isDayDisabled(day);
              let cls = "cdp__day";
              if (isSelected) cls += " cdp__day--selected";
              else if (isToday) cls += " cdp__day--today";
              if (dayDisabled) cls += " cdp__day--disabled";
              return (
                <button
                  key={day}
                  type="button"
                  className={cls}
                  onClick={() => pick(day)}
                  disabled={dayDisabled}
                  aria-pressed={!!isSelected}
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
