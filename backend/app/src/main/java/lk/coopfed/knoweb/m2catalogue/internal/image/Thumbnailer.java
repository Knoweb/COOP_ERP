package lk.coopfed.knoweb.m2catalogue.internal.image;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * The thumbnail of doc 22 section 3.4, in plain Java (javax.imageio and java.awt, no native
 * library): the image fitted into a square of {@code maxSide} pixels, never enlarged, as PNG.
 * Doc 22 names a 128 px WebP; the JDK writes no WebP, so the thumbnail is PNG (module README,
 * Deviations). The size is the register's {@code m2.image.thumbnail_px}.
 *
 * <p>The dimensions are read from the header before anything is decoded, and an image of more
 * than {@code maxPixels} is refused there: a small file can declare a huge image (a
 * decompression bomb), and decoding it would take the job's memory.
 */
final class Thumbnailer {

    static final String CONTENT_TYPE = "image/png";

    /** Why an upload cannot become a thumbnail; the message is the cause the image is FAILED with. */
    static final class NotAnImage extends Exception {
        NotAnImage(String message) {
            super(message);
        }
    }

    private Thumbnailer() {}

    static byte[] thumbnail(byte[] bytes, int maxSide, long maxPixels) throws NotAnImage {
        BufferedImage source = decode(bytes, maxPixels);

        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(1.0, (double) maxSide / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage current = toArgb(source);
        // Halve in steps down to twice the target, then one last bilinear pass: a single pass from
        // a large photograph to 128 px skips most of its pixels and looks jagged.
        while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
            current = resize(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        if (current.getWidth() != targetWidth || current.getHeight() != targetHeight) {
            current = resize(current, targetWidth, targetHeight);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(current, "png", out)) {
                throw new IllegalStateException("The JDK has no PNG writer");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not write a PNG to memory", e);
        }
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] bytes, long maxPixels) throws NotAnImage {
        if (bytes == null || bytes.length == 0) {
            throw new NotAnImage("empty upload");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (input == null || !readers.hasNext()) {
                throw new NotAnImage("not an image this job reads (JPEG or PNG)");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > maxPixels) {
                    throw new NotAnImage("image of " + reader.getWidth(0) + " x " + reader.getHeight(0)
                            + " pixels, more than the " + maxPixels + " allowed");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new NotAnImage("the image could not be decoded");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw new NotAnImage(
                    "the image could not be decoded: " + e.getClass().getSimpleName());
        }
    }

    private static BufferedImage toArgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        return resize(source, source.getWidth(), source.getHeight());
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }
}
