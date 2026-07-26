package com.marketshop.infrastructure.storage;

import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageProofSanitizerTest {

    private final ImageProofSanitizer sanitizer = new ImageProofSanitizer();

    @Test
    void detectsAndReencodesPngFromActualBytes() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(2, 3, Color.RED.getRGB());
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        ImageIO.write(image, "png", source);

        var result = sanitizer.sanitize(source.toByteArray());

        assertThat(result.mediaType()).isEqualTo("image/png");
        assertThat(result.extension()).isEqualTo("png");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(result.bytes()))).isNotNull();
    }

    @Test
    void rejectsPdfEvenWhenCallerCouldClaimItIsAnImage() {
        byte[] renamedPdf = "%PDF-1.7 fake image".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> sanitizer.sanitize(renamedPdf))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("JPG、PNG 或 WebP");
    }

    @Test
    void rejectsTruncatedJpeg() {
        byte[] truncated = {(byte) 0xff, (byte) 0xd8, 0x00, (byte) 0xff, (byte) 0xd9};

        assertThatThrownBy(() -> sanitizer.sanitize(truncated))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("损坏");
    }

    @Test
    void stripsWebpMetadataAndClearsMetadataFeatureFlags() {
        byte[] source = webp(
                chunk("VP8X", new byte[]{
                        0x2c, 0, 0, 0,
                        0, 0, 0,
                        0, 0, 0
                }),
                chunk("EXIF", "meta".getBytes(StandardCharsets.US_ASCII)),
                chunk("VP8 ", new byte[]{
                        0, 0, 0,
                        (byte) 0x9d, 0x01, 0x2a,
                        0x01, 0,
                        0x01, 0
                })
        );

        var result = sanitizer.sanitize(source);

        assertThat(result.mediaType()).isEqualTo("image/webp");
        assertThat(new String(result.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("EXIF");
        assertThat(result.bytes()[20] & 0x2c).isZero();
    }

    @Test
    void rejectsWebpWithFakeVp8Payload() {
        byte[] source = webp(chunk("VP8 ", new byte[10]));

        assertThatThrownBy(() -> sanitizer.sanitize(source))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("损坏");
    }

    private static byte[] webp(byte[]... chunks) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            body.writeBytes(chunk);
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        result.writeBytes(littleEndian(body.size() + 4));
        result.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        result.writeBytes(body.toByteArray());
        return result.toByteArray();
    }

    private static byte[] chunk(String type, byte[] payload) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
        result.writeBytes(littleEndian(payload.length));
        result.writeBytes(payload);
        if ((payload.length & 1) != 0) {
            result.write(0);
        }
        return result.toByteArray();
    }

    private static byte[] littleEndian(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }
}
