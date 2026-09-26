import { useState } from "react";
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

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!code) {
      return;
    }
    onConfirm(code, text.trim() === "" ? null : text.trim());
  };

  return (
    <form className="reason-capture" role="dialog" aria-labelledby="reason-capture-title" onSubmit={submit}>
      <h2 id="reason-capture-title" className="reason-capture__title">
        {title}
      </h2>
      <label className="reason-capture__field">
        {t("shell.reason.code").text}
        <select value={code} onChange={(event) => setCode(event.target.value)} required>
          <option value="">{t("shell.reason.choose").text}</option>
          {codes.map((option) => (
            <option key={option.code} value={option.code}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
      <label className="reason-capture__field">
        {t("shell.reason.text").text}
        <textarea value={text} onChange={(event) => setText(event.target.value)} rows={3} />
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
  );
}
