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
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** The thumbnail of doc 22 section 3.4 in plain Java: fitted, never enlarged, PNG, bounded. */
class ThumbnailerTest {

    @Test
    void aLandscapePhotographFitsTheSquareAndKeepsItsShape() throws Exception {
        byte[] jpeg = image(800, 400, "jpg");

        BufferedImage thumb = read(Thumbnailer.thumbnail(jpeg, 128, 40_000_000));

        assertThat(thumb.getWidth()).isEqualTo(128);
        assertThat(thumb.getHeight()).isEqualTo(64);
    }

    @Test
    void aPortraitPngFitsByItsHeight() throws Exception {
        byte[] png = image(300, 900, "png");

        BufferedImage thumb = read(Thumbnailer.thumbnail(png, 128, 40_000_000));

        assertThat(thumb.getHeight()).isEqualTo(128);
        assertThat(thumb.getWidth()).isEqualTo(43);
    }

    @Test
    void aSmallImageIsNeverEnlarged() throws Exception {
        byte[] png = image(40, 20, "png");

        BufferedImage thumb = read(Thumbnailer.thumbnail(png, 128, 40_000_000));

        assertThat(thumb.getWidth()).isEqualTo(40);
        assertThat(thumb.getHeight()).isEqualTo(20);
    }

    @Test
    void theOutputIsAPng() throws Exception {
        byte[] thumb = Thumbnailer.thumbnail(image(200, 200, "jpg"), 64, 40_000_000);

        // The PNG signature: 0x89 'P' 'N' 'G'.
        assertThat(thumb[0] & 0xff).isEqualTo(0x89);
        assertThat(new String(thumb, 1, 3, StandardCharsets.US_ASCII)).isEqualTo("PNG");
    }

    @Test
    void somethingThatIsNotAnImageIsRefused() {
        assertThatThrownBy(
                        () -> Thumbnailer.thumbnail("%PDF-1.7 a file".getBytes(StandardCharsets.US_ASCII), 128, 1000))
                .isInstanceOf(Thumbnailer.NotAnImage.class)
                .hasMessageContaining("not an image");
        assertThatThrownBy(() -> Thumbnailer.thumbnail(new byte[0], 128, 1000))
                .isInstanceOf(Thumbnailer.NotAnImage.class);
    }

    @Test
    void anImageOfTooManyPixelsIsRefusedFromItsHeader() throws Exception {
        byte[] png = image(1000, 1000, "png");

        assertThatThrownBy(() -> Thumbnailer.thumbnail(png, 128, 999_999))
                .isInstanceOf(Thumbnailer.NotAnImage.class)
                .hasMessageContaining("1000 x 1000");
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
