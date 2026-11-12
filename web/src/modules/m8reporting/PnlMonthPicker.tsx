import { useEffect, useRef, useState } from "react";
import type { MouseEvent as ReactMouseEvent } from "react";
import { useIntl } from "react-intl";
import { businessToday } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";

export interface PnlMonthPickerProps {
  id: string;
  label: string;
  value: string;
  onChange: (nextValue: string) => void;
}

const MONTH_INDICES = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11];

function parseYearMonth(value: string): { year: number; month: number } | null {
  const match = /^(\d{4})-(\d{2})$/.exec(value.trim());
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  if (month < 1 || month > 12) return null;
  return { year, month };
}

/** Custom COOPFED-themed month picker replacing the native browser chrome popup. */
export function PnlMonthPicker({ id, label, value, onChange }: PnlMonthPickerProps) {
  const t = useT();
  const intl = useIntl();
  const containerRef = useRef<HTMLDivElement>(null);

  const currentMonthStr = businessToday().slice(0, 7);
  const currentParsed = parseYearMonth(currentMonthStr) ?? { year: 2026, month: 1 };
  const selectedParsed = parseYearMonth(value);

  const [open, setOpen] = useState(false);
  const [viewYear, setViewYear] = useState<number>(selectedParsed?.year ?? currentParsed.year);

  useEffect(() => {
    if (!open) return;

    const handleClickOutside = (event: MouseEvent | TouchEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const handleKeyDown = (event: globalThis.KeyboardEvent) => {
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

  const triggerLabel = selectedParsed
    ? intl.formatDate(new Date(Date.UTC(selectedParsed.year, selectedParsed.month - 1, 1)), {
        month: "long",
        year: "numeric",
        timeZone: "UTC"
      })
    : "—";

  const handleToggle = (e: ReactMouseEvent<HTMLButtonElement>) => {
    e.preventDefault();
    e.stopPropagation();
    if (!open) {
      setViewYear(selectedParsed?.year ?? currentParsed.year);
    }
    setOpen((prev) => !prev);
  };

  const handlePickMonth = (monthIndexZeroBased: number) => {
    const nextMonth = `${viewYear}-${String(monthIndexZeroBased + 1).padStart(2, "0")}`;
    onChange(nextMonth);
    setOpen(false);
  };

  return (
    <div
      className={`pnl-month-picker ${open ? "pnl-month-picker--open" : ""}`.trim()}
      ref={containerRef}
    >
      <input
        id={id}
        type="month"
        className="visually-hidden"
        aria-label={label}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        tabIndex={-1}
      />

      <button
        type="button"
        className="pnl-month-picker__trigger"
        onClick={handleToggle}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={`${label}: ${triggerLabel}`}
      >
        <span className="pnl-month-picker__value">{triggerLabel}</span>
        <span className="pnl-month-picker__icon" aria-hidden="true">
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
          className="pnl-month-popover"
          role="dialog"
          aria-label={label}
        >
          <div className="pnl-month-popover__header">
            <button
              type="button"
              className="pnl-month-popover__nav"
              aria-label={t("reporting.profit_centers.month_picker.prev_year").text}
              onClick={() => setViewYear((y) => y - 1)}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="m15 18-6-6 6-6" />
              </svg>
            </button>

            <span className="pnl-month-popover__year">{viewYear}</span>

            <button
              type="button"
              className="pnl-month-popover__nav"
              aria-label={t("reporting.profit_centers.month_picker.next_year").text}
              onClick={() => setViewYear((y) => y + 1)}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="m9 18 6-6-6-6" />
              </svg>
            </button>
          </div>

          <div className="pnl-month-grid" role="grid" aria-label={String(viewYear)}>
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
                  className={`pnl-month-grid__btn ${isSelected ? "pnl-month-grid__btn--selected" : ""} ${!isSelected && isCurrent ? "pnl-month-grid__btn--current" : ""}`.trim()}
                  onClick={() => handlePickMonth(monthIdx)}
                >
                  {shortLabel}
                </button>
              );
            })}
          </div>

          <div className="pnl-month-popover__footer">
            <button
              type="button"
              className="pnl-month-popover__clear"
              onClick={() => {
                onChange("");
                setOpen(false);
              }}
            >
              {t("reporting.profit_centers.month_picker.clear").text}
            </button>
            <button
              type="button"
              className="pnl-month-popover__today"
              onClick={() => {
                onChange(currentMonthStr);
                setViewYear(currentParsed.year);
                setOpen(false);
              }}
            >
              {t("reporting.profit_centers.month_picker.this_month").text}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

