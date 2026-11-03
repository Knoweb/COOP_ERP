import type { FormEvent } from "react";
import { useState } from "react";
import { useT } from "../../shell/i18n/useT";


export type ReasonCode = {
  code: string;
  label: string;
};

type CatalogueReasonCaptureProps = {
  title: string;
  codes: ReasonCode[];
  onConfirm: (reasonCode: string, reasonText: string | null) => void;
  onCancel: () => void;
  pending?: boolean;
};

export function CatalogueReasonCapture({
  title,
  codes,
  onConfirm,
  onCancel,
  pending
}: CatalogueReasonCaptureProps) {
  const t = useT();
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
        <span>{t("shell.reason.code").text}</span>
        <select
          value={code}
          onChange={(e) => setCode(e.target.value)}
          required
        >
          <option value="">{t("shell.reason.choose").text}</option>
          {codes.map((c) => (
            <option key={c.code} value={c.code}>
              {c.label}
            </option>
          ))}
        </select>
      </label>

      <label className="reason-capture__field">
        <span>{t("shell.reason.text").text}</span>
        <textarea
          value={text}
          onChange={(event) => setText(event.target.value)}
          rows={2}
          className="reason-capture__textarea"
        />
      </label>

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
  );
}

