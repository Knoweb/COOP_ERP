package lk.coopfed.knoweb.m2catalogue.internal.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** The thumbnail of doc 22 section 3.4 in plain Java: fitted, never enlarged, a JPEG in its budget, bounded. */
class ThumbnailerTest {

    /** The register's m2.image.thumbnail_max_kb (10), in bytes. */
    private static final int BUDGET = 10 * 1024;

    @Test
    void aLandscapePhotographFitsTheSquareAndKeepsItsShape() throws Exception {
        byte[] jpeg = image(800, 400, "jpg");

        BufferedImage thumb = read(Thumbnailer.thumbnail(jpeg, 128, 40_000_000, BUDGET));

        assertThat(thumb.getWidth()).isEqualTo(128);
        assertThat(thumb.getHeight()).isEqualTo(64);
    }

    @Test
    void aPortraitPngFitsByItsHeight() throws Exception {
        byte[] png = image(300, 900, "png");

        BufferedImage thumb = read(Thumbnailer.thumbnail(png, 128, 40_000_000, BUDGET));

        assertThat(thumb.getHeight()).isEqualTo(128);
        assertThat(thumb.getWidth()).isEqualTo(43);
    }

    @Test
    void aSmallImageIsNeverEnlarged() throws Exception {
        byte[] png = image(40, 20, "png");

        BufferedImage thumb = read(Thumbnailer.thumbnail(png, 128, 40_000_000, BUDGET));

        assertThat(thumb.getWidth()).isEqualTo(40);
        assertThat(thumb.getHeight()).isEqualTo(20);
    }

    @Test
    void theOutputIsAJpeg() throws Exception {
        byte[] thumb = Thumbnailer.thumbnail(image(200, 200, "png"), 64, 40_000_000, BUDGET);

        // The JPEG start-of-image marker: 0xFF 0xD8.
        assertThat(thumb[0] & 0xff).isEqualTo(0xff);
        assertThat(thumb[1] & 0xff).isEqualTo(0xd8);
        assertThat(Thumbnailer.CONTENT_TYPE).isEqualTo("image/jpeg");
    }

    @Test
    void aPhotographKeepsWithinTheBudgetOfDoc22() throws Exception {
        // Doc 22 section 7: 128 px and 10 KB, for a photograph of a product (shading and grain).
        byte[] thumb = Thumbnailer.thumbnail(photograph(1200, 900), 128, 40_000_000, BUDGET);

        assertThat(thumb.length).isLessThanOrEqualTo(BUDGET);
        assertThat(read(thumb).getWidth()).isEqualTo(128);
    }

    @Test
    void aBudgetNoQualityMeetsStillGivesTheSmallestThumbnail() throws Exception {
        byte[] best = Thumbnailer.thumbnail(noise(400, 400), 128, 40_000_000, 1 << 20);
        byte[] smallest = Thumbnailer.thumbnail(noise(400, 400), 128, 40_000_000, 1);

        assertThat(smallest.length).isLessThan(best.length);
        assertThat(read(smallest)).isNotNull();
    }

    @Test
    void aTransparentPngIsLaidOnWhite() throws Exception {
        BufferedImage clear = new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(clear, "png", out);

        BufferedImage thumb = read(Thumbnailer.thumbnail(out.toByteArray(), 128, 40_000_000, BUDGET));

        Color corner = new Color(thumb.getRGB(0, 0));
        assertThat(corner.getRed()).isGreaterThan(245);
        assertThat(corner.getGreen()).isGreaterThan(245);
        assertThat(corner.getBlue()).isGreaterThan(245);
    }

    @Test
    void somethingThatIsNotAnImageIsRefused() {
        assertThatThrownBy(() ->
                        Thumbnailer.thumbnail("%PDF-1.7 a file".getBytes(StandardCharsets.US_ASCII), 128, 1000, BUDGET))
                .isInstanceOf(Thumbnailer.NotAnImage.class)
                .hasMessageContaining("not an image");
        assertThatThrownBy(() -> Thumbnailer.thumbnail(new byte[0], 128, 1000, BUDGET))
                .isInstanceOf(Thumbnailer.NotAnImage.class);
    }

    @Test
    void anImageOfTooManyPixelsIsRefusedFromItsHeader() throws Exception {
        byte[] png = image(1000, 1000, "png");

        assertThatThrownBy(() -> Thumbnailer.thumbnail(png, 128, 999_999, BUDGET))
                .isInstanceOf(Thumbnailer.NotAnImage.class)
                .hasMessageContaining("1000 x 1000");
    }

    /** A stand-in for a product photograph: shading across the frame, a shape, and grain. */
    private static byte[] photograph(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int grain = random.nextInt(17) - 8;
                int r = clamp(60 + 150 * x / width + grain);
                int g = clamp(90 + 120 * y / height + grain);
                int b = clamp(180 - 100 * x / width + grain);
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(230, 230, 210));
        g.fillOval(width / 4, height / 5, width / 2, height * 3 / 5);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /** Random pixels, which compress worst. */
    private static byte[] noise(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] image(int width, int height, String format) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLUE);
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
}
