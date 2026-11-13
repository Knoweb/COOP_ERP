import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { CSSProperties } from "react";

const POPUP_WIDTH = 218;
const POPUP_ESTIMATED_HEIGHT = 272;
const VIEWPORT_PADDING = 8;
const POPUP_OFFSET = 4;

export function pad2(n: number): string {
  return n < 10 ? `0${n}` : `${n}`;
}

export function isValidIsoDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim());
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (month < 1 || month > 12 || day < 1 || day > 31) return false;
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day
  );
}

export function useCalendarPopup(portal: boolean) {
  const [open, setOpen] = useState(false);
  const [openUpward, setOpenUpward] = useState(false);
  const [popupStyle, setPopupStyle] = useState<CSSProperties>({});

  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const popupRef = useRef<HTMLDivElement>(null);

  const updatePosition = useCallback(() => {
    if (!portal || !triggerRef.current || typeof window === "undefined") return;
    const rect = triggerRef.current.getBoundingClientRect();
    const spaceBelow = window.innerHeight - rect.bottom;
    const shouldFlipUp = spaceBelow < POPUP_ESTIMATED_HEIGHT && rect.top > spaceBelow;
    const maxLeft = Math.max(VIEWPORT_PADDING, window.innerWidth - POPUP_WIDTH - VIEWPORT_PADDING);
    const left = Math.min(Math.max(VIEWPORT_PADDING, rect.left), maxLeft);

    setOpenUpward(shouldFlipUp);
    if (shouldFlipUp) {
      setPopupStyle({
        left,
        top: "auto",
        bottom: window.innerHeight - rect.top + POPUP_OFFSET
      });
    } else {
      setPopupStyle({
        left,
        top: rect.bottom + POPUP_OFFSET,
        bottom: "auto"
      });
    }
  }, [portal]);

  useLayoutEffect(() => {
    if (open && portal) {
      updatePosition();
    }
  }, [open, portal, updatePosition]);

  useEffect(() => {
    if (!open) return;

    const handleClickOutside = (event: MouseEvent | TouchEvent) => {
      const target = event.target as Node | null;
      if (!target) return;
      if (containerRef.current?.contains(target)) return;
      if (popupRef.current?.contains(target)) return;
      setOpen(false);
    };

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };

    document.addEventListener("mousedown", handleClickOutside);
    document.addEventListener("touchstart", handleClickOutside);
    document.addEventListener("keydown", handleKeyDown);

    if (portal) {
      window.addEventListener("resize", updatePosition);
      window.addEventListener("scroll", updatePosition, { capture: true, passive: true });
    }

    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("touchstart", handleClickOutside);
      document.removeEventListener("keydown", handleKeyDown);
      if (portal) {
        window.removeEventListener("resize", updatePosition);
        window.removeEventListener("scroll", updatePosition, { capture: true });
      }
    };
  }, [open, portal, updatePosition]);

  return {
    open,
    setOpen,
    openUpward,
    popupStyle,
    containerRef,
    triggerRef,
    popupRef
  };
}

export function CalendarIcon() {
  return (
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
        focusable="false"
      >
        <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
        <line x1="16" y1="2" x2="16" y2="6" />
        <line x1="8" y1="2" x2="8" y2="6" />
        <line x1="3" y1="10" x2="21" y2="10" />
      </svg>
    </span>
  );
}

export function ChevronLeftIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <polyline points="15 18 9 12 15 6" />
    </svg>
  );
}

export function ChevronRightIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <polyline points="9 18 15 12 9 6" />
    </svg>
  );
}

