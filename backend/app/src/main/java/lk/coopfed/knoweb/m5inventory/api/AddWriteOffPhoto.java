package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/**
 * A photograph for a draft write-off: the kernel authorises one upload against the WOF document
 * and the write-off remembers the attachment (19A section 9).
 *
 * @param contentType   what will be uploaded, for example image/jpeg
 * @param contentLength the exact size in bytes, or null
 */
public record AddWriteOffPhoto(UUID writeOffId, String contentType, Long contentLength) {}
