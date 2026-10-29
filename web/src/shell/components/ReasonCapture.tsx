import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { useT } from "../i18n/useT";

/**
 * The small form a command with a reason opens before it runs (doc 30 section 2.2; 21A: a
 * suspension, an amendment): a reason chosen from the codes the screen offers, an optional
 * text, confirm or cancel. The reason goes into the audit record, which is why it is captured
 * before the command and never after.
 *
 * The codes and their labels are the screen's (translated by it); the words of the form
 * itself are the shell's. It is a section with the dialog role, not a browser modal: it sits
 * where the action was, and the page behind it stays readable.
 */
export type ReasonCode = {
  code: string;
  label: string;
};

type ReasonCaptureProps = {
  /** The translated question, for example t("m1.action.suspend.question").text. */
  title: string;
  codes: ReasonCode[];
  onConfirm: (reasonCode: string, reasonText: string | null) => void;
  onCancel: () => void;
  pending?: boolean;
};

export function ReasonCapture({ title, codes, onConfirm, onCancel, pending }: ReasonCaptureProps) {
  const t = useT();
  // Nothing is chosen until the person chooses: a reason that goes into the audit record must
  // be the clerk's, not the first one on the list (the review of 26 September). `required`
  // and the empty-code check below stop a Confirm without a choice.
  const [code, setCode] = useState("");
  const [text, setText] = useState("");
  const [open, setOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLUListElement>(null);

  const matched = codes.find((opt) => opt.code === code);
  const currentLabel = matched ? matched.label : t("shell.reason.choose").text;

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !pending) {
        if (open) {
          setOpen(false);
          e.stopPropagation();
        } else {
          onCancel();
        }
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onCancel, pending, open]);

  useEffect(() => {
    if (!open) return;
    const handleClickOutside = (event: MouseEvent | TouchEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    document.addEventListener("touchstart", handleClickOutside);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("touchstart", handleClickOutside);
    };
  }, [open]);

  useEffect(() => {
    if (open && menuRef.current) {
      const selected = menuRef.current.querySelector<HTMLElement>(".register-dropdown-option--selected");
      if (selected) {
        selected.scrollIntoView({ block: "nearest" });
      }
    }
  }, [open]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!code) {
      return;
    }
    onConfirm(code, text.trim() === "" ? null : text.trim());
  };

  return (
    <div
      className="reason-capture-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget && !pending) {
          onCancel();
        }
      }}
    >
      <form className="reason-capture" role="dialog" aria-labelledby="reason-capture-title" onSubmit={submit}>
        <h2 id="reason-capture-title" className="reason-capture__title">
          {title}
        </h2>

        <div className="reason-capture__field">
          <label htmlFor="reason-capture-code-select">
            {t("shell.reason.code").text}
          </label>
          {/* Native select preserved for accessible labels and automated tests */}
          <select
            id="reason-capture-code-select"
            value={code}
            onChange={(event) => setCode(event.target.value)}
            required
            className="reason-capture__native-select"
            tabIndex={-1}
            aria-hidden="true"
          >
            <option value="">{t("shell.reason.choose").text}</option>
            {codes.map((option) => (
              <option key={option.code} value={option.code}>
                {option.label}
              </option>
            ))}
          </select>

          {/* Custom polished dropdown matching CatalogueDropdown */}
          <div
            ref={dropdownRef}
            className={`register-field-group reason-capture__dropdown-wrap ${open ? "register-field-group--open" : ""}`}
            style={{ position: "relative" }}
          >
            <button
              type="button"
              className={`register-dropdown-trigger ${code ? "has-value" : ""}`}
              aria-haspopup="listbox"
              aria-expanded={open}
              disabled={pending}
              onClick={() => setOpen(!open)}
            >
              <span className="custom-status-dropdown__label">{currentLabel}</span>
              <svg
                className="custom-status-dropdown__arrow"
                viewBox="0 0 24 24"
                aria-hidden="true"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="m7 9 5 5 5-5" />
              </svg>
            </button>

            {open && (
              <ul ref={menuRef} className="register-dropdown-menu" role="listbox">
                {codes.map((option) => {
                  const isSelected = code === option.code;
                  return (
                    <li key={option.code} role="presentation">
                      <button
                        type="button"
                        role="option"
                        aria-selected={isSelected}
                        className={
                          isSelected
                            ? "register-dropdown-option register-dropdown-option--selected"
                            : "register-dropdown-option"
                        }
                        onClick={() => {
                          setCode(option.code);
                          setOpen(false);
                        }}
                      >
                        <span>{option.label}</span>
                        {isSelected && (
                          <span className="custom-status-dropdown__check" aria-hidden="true">
                            <svg
                              viewBox="0 0 24 24"
                              width="16"
                              height="16"
                              stroke="currentColor"
                              strokeWidth="2.5"
                              fill="none"
                              strokeLinecap="round"
                              strokeLinejoin="round"
                            >
                              <polyline points="20 6 9 17 4 12" />
                            </svg>
                          </span>
                        )}
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>

        <label className="reason-capture__field">
          {t("shell.reason.text").text}
          <textarea
            value={text}
            onChange={(event) => setText(event.target.value)}
            rows={1}
            className="reason-capture__textarea"
          />
        </label>

        <div className="reason-capture__actions">
          <button type="submit" className="approval-bar__button approval-bar__button--primary" disabled={pending || !code}>
            {t("shell.reason.confirm").text}
          </button>
          <button type="button" className="approval-bar__button" onClick={onCancel} disabled={pending}>
            {t("shell.reason.cancel").text}
          </button>
        </div>
      </form>
    </div>
  );
}
