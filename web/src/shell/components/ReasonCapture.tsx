import type { FormEvent } from "react";
import { useEffect, useRef, useState } from "react";
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
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !pending) {
        if (open) {
          setOpen(false);
        } else {
          onCancel();
        }
      }
    };
    const handleClickOutside = (e: MouseEvent | TouchEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    if (open) {
      document.addEventListener("mousedown", handleClickOutside);
      document.addEventListener("touchstart", handleClickOutside);
    }
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("touchstart", handleClickOutside);
    };
  }, [onCancel, pending, open]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!code) {
      return;
    }
    onConfirm(code, text.trim() === "" ? null : text.trim());
  };

  const selectedItem = codes.find((item) => item.code === code);
  const triggerText = selectedItem?.label ?? t("shell.reason.choose").text;

  return (
    <div
      className="reason-capture-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget && !pending) {
          onCancel();
        }
      }}
    >
      <form
        className="reason-capture"
        role="dialog"
        aria-modal="true"
        aria-labelledby="reason-capture-title"
        onSubmit={submit}
      >
        <h2 id="reason-capture-title" className="reason-capture__title">
          {title}
        </h2>

        <div className="reason-capture__field reason-capture__field--select">
          <label htmlFor="reason-capture-code" className="reason-capture__label">
            {t("shell.reason.code").text}
          </label>
          <div className={`custom-dropdown reason-capture__dropdown ${open ? "custom-dropdown--open" : ""}`} ref={containerRef}>
            {/* Native select kept as the single accessible combobox for form validation and Playwright automation */}
            <select
              id="reason-capture-code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              required
              className="reason-capture__native-select"
              tabIndex={-1}
            >
              <option value="">{t("shell.reason.choose").text}</option>
              {codes.map((item) => (
                <option key={item.code} value={item.code}>
                  {item.label}
                </option>
              ))}
            </select>

            {/* Custom interactive trigger button matching the system standard dropdowns */}
            <button
              type="button"
              className="custom-dropdown__trigger reason-dropdown-trigger"
              onClick={() => setOpen((prev) => !prev)}
              aria-haspopup="listbox"
              aria-expanded={open}
            >
              <span className={`custom-dropdown__value ${!code ? "custom-dropdown__value--placeholder" : ""}`}>
                {triggerText}
              </span>
              <svg
                className={`custom-dropdown__arrow ${open ? "custom-dropdown__arrow--open" : ""}`}
                viewBox="0 0 24 24"
                aria-hidden="true"
              >
                <path d="m6 9 6 6 6-6" />
              </svg>
            </button>

            {open && (
              <ul className="custom-dropdown__menu reason-dropdown-menu" role="listbox">
                {codes.map((item) => {
                  const isSelected = item.code === code;
                  return (
                    <li
                      key={item.code}
                      role="option"
                      aria-selected={isSelected}
                      className={`custom-dropdown__item ${isSelected ? "custom-dropdown__item--selected" : ""}`}
                      onClick={() => {
                        setCode(item.code);
                        setOpen(false);
                      }}
                    >
                      <span className="custom-dropdown__item-label">{item.label}</span>
                      {isSelected && (
                        <svg className="custom-dropdown__checkmark" viewBox="0 0 24 24" aria-hidden="true">
                          <polyline points="20 6 9 17 4 12" />
                        </svg>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>

        <div className="reason-capture__field">
          <label htmlFor="reason-capture-text" className="reason-capture__label">
            {t("shell.reason.text").text}
          </label>
          <textarea
            id="reason-capture-text"
            value={text}
            onChange={(event) => setText(event.target.value)}
            rows={3}
            className="reason-capture__textarea"
          />
        </div>

        <div className="reason-capture__actions">
          <button
            type="submit"
            className="approval-bar__button approval-bar__button--primary"
            disabled={pending || !code}
          >
            {t("shell.reason.confirm").text}
          </button>
          <button
            type="button"
            className="approval-bar__button"
            onClick={onCancel}
            disabled={pending}
          >
            {t("shell.reason.cancel").text}
          </button>
        </div>
      </form>
    </div>
  );
}
