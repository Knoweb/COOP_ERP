package lk.coopfed.knoweb.m2catalogue.api;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** The answer to AttachImage: the new image's id and where to PUT its bytes, until when. */
public record ImageUpload(UUID imageId, URI uploadUrl, Instant expiresAt) {}
