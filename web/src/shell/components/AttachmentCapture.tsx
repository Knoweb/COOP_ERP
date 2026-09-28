import type { ChangeEvent } from "react";

/**
 * The camera strip of a document that carries evidence (doc 30 section 2.2; 25A section 8, the
 * damage and expiry register: "camera strip (required badge when policy says so)"): what is
 * attached so far with its state, and a file picker that takes photographs on a phone. The upload
 * itself is the screen's (it asks its module for a signed URL and PUTs the bytes, 19A section 9);
 * this component only offers the file and shows the list.
 *
 * Every word arrives already translated, as for ApprovalBar: the component knows no module.
 */
export type CapturedAttachment = {
  id: string;
  /** What to show for it, translated: "Verified", "Uploading, not verified yet". */
  label: string;
};

type AttachmentCaptureProps = {
  attachments: CapturedAttachment[];
  /** The label of the file picker, for example t("inventory.writeoff.photo.add").text. */
  addLabel: string;
  /** Shown while nothing is attached. */
  emptyLabel: string;
  /** Shown as a badge when the policy requires evidence; left out when it does not. */
  requiredLabel?: string;
  onAdd: (file: File) => void;
  /** No new file while an upload runs or once the document is past its draft. */
  disabled?: boolean;
  accept?: string;
};

export function AttachmentCapture({
  attachments,
  addLabel,
  emptyLabel,
  requiredLabel,
  onAdd,
  disabled,
  accept = "image/*"
}: AttachmentCaptureProps) {
  const choose = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) {
      onAdd(file);
    }
    event.target.value = "";
  };

  return (
    <div className="attachment-capture">
      {requiredLabel && <p className="attachment-capture__required">{requiredLabel}</p>}
      {attachments.length === 0 ? (
        <p>{emptyLabel}</p>
      ) : (
        <ol className="attachment-capture__list">
          {attachments.map((attachment) => (
            <li key={attachment.id}>{attachment.label}</li>
          ))}
        </ol>
      )}
      <label className="attachment-capture__add">
        {addLabel}
        <input type="file" accept={accept} capture="environment" disabled={disabled} onChange={choose} />
      </label>
    </div>
  );
}
