package lk.coopfed.knoweb.kernel.api;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Photographs and files on a document (doc 18, table document_attachment; 19A section 9):
 * evidence on a discrepancy, a claim or a write-off, images on a product. The bytes live in
 * object storage behind the S3 protocol; the row lives with the document and inherits its
 * visibility.
 *
 * <p>The flow: the owning module's handler calls {@link #presignUpload} inside its transaction
 * and hands the URL to the client, which PUTs the bytes straight to the store. The row is
 * PENDING until the verifier job ({@code attachment-verify}, every five minutes) finds the
 * object, checks its hash and sets COMPLETE, publishing {@code attachment.completed.v1}; a
 * decision that depends on evidence (a write-off approval) waits for COMPLETE. An object that
 * never arrives, or arrives with another hash, makes the row FAILED with a REVIEW record.
 *
 * <p>A read URL comes through the document's row-level security: a user who cannot read the
 * document cannot obtain it.
 */
public interface Attachments {

    /**
     * @param url        where the client PUTs the bytes, with the content type given here
     * @param expiresAt  after this the URL is refused by the store; ask again
     * @param objectKey  {@code attachments/{entity}/{documentId}/{attachmentId}}
     */
    record PresignedUpload(UUID attachmentId, URI url, Instant expiresAt, String objectKey) {}

    /**
     * Authorises one upload for a document the caller owns and records the attachment as
     * PENDING. Asking again with the same attachment id (the URL expired before the upload
     * finished) renews the URL and the window of the PENDING row; a row already COMPLETE or
     * FAILED, or one of another document, is refused. The row is settled by the verifier only
     * after the URL has expired, so what was verified cannot be replaced.
     *
     * @param attachmentId  the id the client chose (UUIDv7), or null for a new one
     * @param contentType   what the client will upload, one of the register's
     *                      {@code attachment.content_types}, for example {@code image/jpeg}
     * @param contentLength the exact size in bytes, signed into the URL so the store takes no
     *                      other, or null when the client does not know it yet; at most the
     *                      register's {@code attachment.max_bytes} either way (the verifier fails
     *                      a larger object)
     * @param sha256Hex     the hash the client computed over the bytes, or null when the store
     *                      is trusted to tell it; the verifier compares when given
     * @throws ProblemException {@code attachment.document_not_found} for a document the caller
     *                          cannot see; {@code attachment.document_not_owned} for one it can
     *                          see but does not own; {@code attachment.content_type_invalid};
     *                          {@code attachment.content_type_not_allowed};
     *                          {@code attachment.too_large}; {@code attachment.hash_invalid};
     *                          {@code attachment.not_pending}; {@code attachment.document_mismatch}
     */
    PresignedUpload presignUpload(
            UUID documentId,
            UUID attachmentId,
            String contentType,
            Long contentLength,
            String sha256Hex,
            ScopeContext ctx);

    /**
     * A pre-signed GET for an attachment of a document the caller can read, or empty when the
     * caller cannot see it or it is not COMPLETE.
     */
    Optional<URI> presignDownload(UUID attachmentId, ScopeContext ctx);

    /** The status of an attachment the caller can see: PENDING, COMPLETE or FAILED. */
    Optional<String> status(UUID attachmentId);
}
