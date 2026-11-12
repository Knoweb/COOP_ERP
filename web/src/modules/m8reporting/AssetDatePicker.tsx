import { useCallback, useEffect, useRef, useState } from "react";
import type { MouseEvent as ReactMouseEvent } from "react";
import { createPortal } from "react-dom";
import { useIntl } from "react-intl";
import { businessToday, useFormatDate } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";

export interface AssetDatePickerProps {
  id: string;
  label: string;
  value: string;
  onChange: (nextValue: string) => void;
  portal?: boolean;
}

interface PortalCoords {
  top?: number;
  bottom?: number;
  left: number;
}

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

/** Custom COOPFED-themed date picker with optional document-body portaling for modals. */
export function AssetDatePicker({ id, label, value, onChange, portal = false }: AssetDatePickerProps) {
  const t = useT();
  const intl = useIntl();
  const formatDate = useFormatDate();
  const containerRef = useRef<HTMLDivElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);

  const todayStr = businessToday();
  const todayParsed = parseIsoDate(todayStr) ?? { year: 2026, month: 1, day: 1 };
  const selectedParsed = parseIsoDate(value);

  const [open, setOpen] = useState(false);
  const [openUp, setOpenUp] = useState(false);
  const [portalCoords, setPortalCoords] = useState<PortalCoords | null>(null);
  const [viewYear, setViewYear] = useState<number>(selectedParsed?.year ?? todayParsed.year);
  const [viewMonth, setViewMonth] = useState<number>(selectedParsed?.month ?? todayParsed.month);

  const updatePlacement = useCallback(() => {
    if (!containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const spaceBelow = window.innerHeight - rect.bottom;
    const spaceAbove = rect.top;
    const shouldOpenUp = spaceBelow < 320 && spaceAbove > spaceBelow;
    setOpenUp(shouldOpenUp);
    if (portal) {
      const safeLeft = Math.max(0, rect.left);
      if (shouldOpenUp) {
        setPortalCoords({
          bottom: window.innerHeight - rect.top,
          left: safeLeft
        });
      } else {
        setPortalCoords({
          top: rect.bottom,
          left: safeLeft
        });
      }
    }
  }, [portal]);

  useEffect(() => {
    if (!open) return;

    const handleClickOutside = (event: MouseEvent | TouchEvent) => {
      const target = event.target as Node;
      if (
        containerRef.current &&
        !containerRef.current.contains(target) &&
        (!popoverRef.current || !popoverRef.current.contains(target))
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
      if (popoverRef.current && event.target instanceof Node && popoverRef.current.contains(event.target)) {
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

  const handleToggle = (e: ReactMouseEvent<HTMLButtonElement>) => {
    e.preventDefault();
    e.stopPropagation();
    if (!open) {
      setViewYear(selectedParsed?.year ?? todayParsed.year);
      setViewMonth(selectedParsed?.month ?? todayParsed.month);
      updatePlacement();
    }
    setOpen((prev) => !prev);
  };

  const days = buildCalendarDays(viewYear, viewMonth);

  const popoverNode = open && (
    <div
      ref={portal ? popoverRef : undefined}
      className={`assets-calendar-popover ${portal ? "assets-calendar-portal" : ""} ${openUp ? "assets-calendar-popover--up" : ""}`.trim()}
      role="dialog"
      aria-label={label}
      style={portal && portalCoords ? portalCoords : undefined}
    >
      <div className="assets-calendar-popover__header">
        <button
          type="button"
          className="assets-calendar-popover__nav"
          aria-label={t("reporting.assets.picker.prev_month").text}
          onClick={() => shiftMonth(-1)}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="m15 18-6-6 6-6" />
          </svg>
        </button>

        <span className="assets-calendar-popover__title">{headerLabel}</span>

        <button
          type="button"
          className="assets-calendar-popover__nav"
          aria-label={t("reporting.assets.picker.next_month").text}
          onClick={() => shiftMonth(1)}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="m9 18 6-6-6-6" />
          </svg>
        </button>
      </div>

      <div className="assets-date-weekdays" aria-hidden="true">
        {WEEKDAY_OFFSETS.map((offset) => (
          <span key={offset} className="assets-date-weekdays__cell">
            {intl.formatDate(new Date(Date.UTC(2026, 0, 4 + offset)), {
              weekday: "narrow",
              timeZone: "UTC"
            })}
          </span>
        ))}
      </div>

      <div className="assets-date-grid" role="grid" aria-label={headerLabel}>
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
              className={`assets-date-grid__btn ${cell.outside ? "assets-date-grid__btn--outside" : ""} ${isSelected ? "assets-date-grid__btn--selected" : ""} ${!isSelected && isToday ? "assets-date-grid__btn--today" : ""}`.trim()}
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

      <div className="assets-calendar-popover__footer">
        <button
          type="button"
          className="assets-calendar-popover__clear"
          onClick={() => {
            onChange("");
            setOpen(false);
          }}
        >
          {t("reporting.assets.picker.clear").text}
        </button>
        <button
          type="button"
          className="assets-calendar-popover__today"
          onClick={() => {
            onChange(todayStr);
            setViewYear(todayParsed.year);
            setViewMonth(todayParsed.month);
            setOpen(false);
          }}
        >
          {t("reporting.assets.picker.today").text}
        </button>
      </div>
    </div>
  );

  return (
    <div
      className={`assets-calendar ${open ? "assets-calendar--open" : ""}`.trim()}
      ref={containerRef}
    >
      <input
        id={id}
        type="date"
        className="visually-hidden"
        aria-label={label}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        tabIndex={-1}
      />

      <button
        type="button"
        className="assets-calendar__trigger"
        onClick={handleToggle}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={`${label}: ${displayText}`}
      >
        <span className="assets-calendar__value">{displayText}</span>
        <span className="assets-calendar__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" focusable="false">
            <rect x="3" y="4" width="18" height="18" rx="2" />
            <line x1="16" y1="2" x2="16" y2="6" />
            <line x1="8" y1="2" x2="8" y2="6" />
            <line x1="3" y1="10" x2="21" y2="10" />
          </svg>
        </span>
      </button>

      {portal && typeof document !== "undefined" ? createPortal(popoverNode, document.body) : popoverNode}
    </div>
  );
}

