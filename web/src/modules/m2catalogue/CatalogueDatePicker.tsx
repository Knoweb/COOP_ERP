import { useEffect, useRef, useState } from "react";
import { useT } from "../../shell/i18n/useT";
import { businessToday } from "../../shell/i18n/formats";

type CatalogueDatePickerProps = {
  id?: string;
  label?: string;
  value: string; // YYYY-MM-DD
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  required?: boolean;
};

const MONTH_NAMES = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December"
];

const WEEKDAY_NAMES = ["SU", "MO", "TU", "WE", "TH", "FR", "SA"];

function pad2(n: number): string {
  return n < 10 ? `0${n}` : `${n}`;
}

function formatIso(year: number, monthIndex: number, day: number): string {
  return `${year}-${pad2(monthIndex + 1)}-${pad2(day)}`;
}

export function CatalogueDatePicker({
  label,
  value,
  onChange,
  disabled,
  required,
  placeholder
}: CatalogueDatePickerProps) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  // Parse current value or fallback to today
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

  // Update view when value changes from outside
  useEffect(() => {
    if (!value) return;
    const parts = value.split("-").map(Number);
    if (parts.length === 3 && !isNaN(parts[0]) && !isNaN(parts[1]) && !isNaN(parts[2])) {
      setViewYear(parts[0]);
      setViewMonth(parts[1] - 1);
    }
  }, [value]);

  // Click outside and escape key handling
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

  // Navigation handlers
  const handlePrevMonth = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (viewMonth === 0) {
      setViewMonth(11);
      setViewYear((y) => y - 1);
    } else {
      setViewMonth((m) => m - 1);
    }
  };

  const handleNextMonth = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (viewMonth === 11) {
      setViewMonth(0);
      setViewYear((y) => y + 1);
    } else {
      setViewMonth((m) => m + 1);
    }
  };

  // Select day
  const handleSelectDay = (day: number) => {
    const iso = formatIso(viewYear, viewMonth, day);
    onChange(iso);
    setOpen(false);
  };

  // Clear date
  const handleClear = (e: React.MouseEvent) => {
    e.stopPropagation();
    onChange("");
    setOpen(false);
  };

  // Today
  const handleToday = (e: React.MouseEvent) => {
    e.stopPropagation();
    onChange(todayStr);
    setOpen(false);
  };

  // Calendar math using UTC so local timezone cannot shift dates
  const daysInMonth = new Date(Date.UTC(viewYear, viewMonth + 1, 0)).getUTCDate();
  const firstDayOfWeek = new Date(Date.UTC(viewYear, viewMonth, 1)).getUTCDay(); // 0 = Sunday

  return (
    <div className="catalogue-datepicker-wrap" ref={containerRef}>
      {label && (
        <span className="catalogue-datepicker-label">
          <span>{label}</span>
          {required && <span className="register-field-required">*</span>}
        </span>
      )}

      {/* Hidden input to support form requirement check */}
      {required && (
        <input
          type="text"
          value={value}
          required={required}
          readOnly
          tabIndex={-1}
          style={{
            position: "absolute",
            width: 0,
            height: 0,
            opacity: 0,
            pointerEvents: "none"
          }}
        />
      )}

      {/* Trigger Button */}
      <button
        type="button"
        className={`catalogue-datepicker-trigger ${open ? "catalogue-datepicker-trigger--open" : ""}`}
        disabled={disabled}
        onClick={() => setOpen(!open)}
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        <span className={value ? "catalogue-datepicker-trigger__value" : "catalogue-datepicker-trigger__placeholder"}>
          {value || placeholder}
        </span>
        <span className="catalogue-datepicker-trigger__icon" aria-hidden="true">
          <svg
            viewBox="0 0 24 24"
            width="18"
            height="18"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
            <line x1="16" y1="2" x2="16" y2="6" />
            <line x1="8" y1="2" x2="8" y2="6" />
            <line x1="3" y1="10" x2="21" y2="10" />
          </svg>
        </span>
      </button>

      {/* Calendar Dialog Popup */}
      {open && (
        <div className="catalogue-calendar-popup" role="dialog" aria-modal="true">
          {/* Header */}
          <div className="catalogue-calendar-header">
            <button
              type="button"
              className="catalogue-calendar-nav-btn"
              onClick={handlePrevMonth}
              aria-label={t("catalogue.datepicker.prev_month").text}
            >
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="15 18 9 12 15 6" />
              </svg>
            </button>

            <span className="catalogue-calendar-title">
              {MONTH_NAMES[viewMonth]} {viewYear}
            </span>

            <button
              type="button"
              className="catalogue-calendar-nav-btn"
              onClick={handleNextMonth}
              aria-label={t("catalogue.datepicker.next_month").text}
            >
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="9 18 15 12 9 6" />
              </svg>
            </button>
          </div>

          {/* Weekday Names */}
          <div className="catalogue-calendar-weekdays">
            {WEEKDAY_NAMES.map((day) => (
              <span key={day} className="catalogue-calendar-weekday">
                {day}
              </span>
            ))}
          </div>

          {/* Days Grid */}
          <div className="catalogue-calendar-grid">
            {/* Empty offset slots */}
            {Array.from({ length: firstDayOfWeek }).map((_, idx) => (
              <div key={`empty-${idx}`} className="catalogue-calendar-empty-slot" />
            ))}

            {/* Days of Month */}
            {Array.from({ length: daysInMonth }).map((_, idx) => {
              const day = idx + 1;
              const isSelected =
                parsedDate &&
                parsedDate.year === viewYear &&
                parsedDate.month === viewMonth &&
                parsedDate.day === day;

              return (
                <button
                  key={day}
                  type="button"
                  className={`catalogue-calendar-day ${isSelected ? "catalogue-calendar-day--selected" : ""}`}
                  onClick={() => handleSelectDay(day)}
                >
                  {day}
                </button>
              );
            })}
          </div>

          {/* Footer (Clear and Today) */}
          <div className="catalogue-calendar-footer">
            <button type="button" className="catalogue-calendar-action" onClick={handleClear}>
              {t("catalogue.datepicker.clear").text}
            </button>
            <button type="button" className="catalogue-calendar-action" onClick={handleToday}>
              {t("catalogue.datepicker.today").text}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
