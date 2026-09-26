package lk.coopfed.knoweb.m2catalogue.internal.image;

import java.time.Duration;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The register's image items as a scope sees them. The thumbnail job runs outside a transaction
 * (it talks to the object store), so the values are read in a transaction of their own with the
 * scope applied: public, {@code @Transactional}, the scope an argument (the kernel's rule for
 * putting a scope on the connection).
 */
@Component
public class ImageSettings {

    static final String BATCH_SIZE = "m2.image.thumbnail.batch_size";
    static final String THUMBNAIL_PX = "m2.image.thumbnail_px";
    static final String MAX_PIXELS = "m2.image.max_pixels";
    static final String UPLOAD_WINDOW_HOURS = "m2.image.upload_window_hours";

    /** The job's limits, read together. */
    public record Limits(int batchSize, int thumbnailPx, long maxPixels, Duration uploadWindow) {}

    private final ConfigRegistry config;

    ImageSettings(ConfigRegistry config) {
        this.config = config;
    }

    @Transactional(readOnly = true)
    public Limits limits(ScopeContext scope) {
        return new Limits(
                config.getInt(BATCH_SIZE, scope, 100),
                config.getInt(THUMBNAIL_PX, scope, 128),
                config.getInt(MAX_PIXELS, scope, 40_000_000),
                Duration.ofHours(config.getInt(UPLOAD_WINDOW_HOURS, scope, 24)));
    }
}
