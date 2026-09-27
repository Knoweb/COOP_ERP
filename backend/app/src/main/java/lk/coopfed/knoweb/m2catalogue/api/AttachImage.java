package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

/**
 * Asks to attach a product image to a SKU (22A section 6, AttachImage; doc 22 section 3.4): the
 * owner's image of its own SKU, or an entity's local override of a SHARED one. The answer is a
 * pre-signed PUT; the image is shown once the thumbnail job has verified the upload.
 *
 * @param barcode       the pack the image shows, when the SKU has several; null for the item
 * @param contentLength the exact size in bytes, signed into the URL, or null
 * @param sha256Hex     the SHA-256 the client computed over the bytes (doc 22 section 4: "hash")
 */
public record AttachImage(UUID skuId, String barcode, String contentType, Long contentLength, String sha256Hex) {}
