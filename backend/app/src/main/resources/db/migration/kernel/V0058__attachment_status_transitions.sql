-- K-09 review (26 Sep): an attachment settles once, and the upload window is a fact of the row.
-- Lane C.
--
-- V0050 granted UPDATE (status, content_hash) to app_rw under the owner's policy, so any owning
-- session could set COMPLETE or change the hash and walk round the verifier. The trigger below
-- lets a row change only while it is PENDING: PENDING -> COMPLETE or FAILED (the verifier, 19A
-- section 9), or a PENDING row's upload window and announced hash renewed when the client asks
-- for its URL again. A COMPLETE or FAILED row never changes again; the object it names is what
-- was verified.
--
-- Two columns the review asked for: content_type, so that a read URL can tell the browser to
-- download anything that is not an image instead of rendering it from the store's origin; and
-- upload_expires_at, the end of the pre-signed PUT's validity, before which the verifier does
-- not settle the row, because until then the bytes may still be replaced with the same URL.

ALTER TABLE kernel.document_attachment
    ADD COLUMN content_type text,
    ADD COLUMN upload_expires_at timestamptz;

CREATE OR REPLACE FUNCTION kernel.document_attachment_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'attachment.immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_document_attachment_transition
    BEFORE UPDATE ON kernel.document_attachment
    FOR EACH ROW EXECUTE FUNCTION kernel.document_attachment_transition();

GRANT UPDATE (upload_expires_at) ON kernel.document_attachment TO app_rw;
