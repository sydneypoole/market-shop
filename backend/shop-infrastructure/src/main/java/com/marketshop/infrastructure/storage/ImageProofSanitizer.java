package com.marketshop.infrastructure.storage;

import com.marketshop.application.proof.OrderProofPorts.ProofSanitizerPort;
import com.marketshop.application.proof.OrderProofPorts.SanitizedImage;
import com.marketshop.application.identity.IdentityAvatarSanitizerPort;
import com.marketshop.application.identity.IdentityAvatarSanitizerPort.SanitizedAvatar;
import com.marketshop.domain.shared.DomainException;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public class ImageProofSanitizer implements ProofSanitizerPort, IdentityAvatarSanitizerPort {

    private static final int MAX_DIMENSION = 12_000;
    private static final Set<String> WEBP_METADATA_CHUNKS = Set.of("EXIF", "XMP ", "ICCP");
    private static final int WEBP_METADATA_FLAGS = 0x20 | 0x08 | 0x04;

    @Override
    public SanitizedImage sanitize(byte[] bytes) {
        if (isJpeg(bytes)) {
            return reencode(bytes, "jpeg", "image/jpeg", "jpg");
        }
        if (isPng(bytes)) {
            return reencode(bytes, "png", "image/png", "png");
        }
        if (isWebp(bytes)) {
            return sanitizeWebp(bytes);
        }
        throw new DomainException("PROOF_TYPE_INVALID", "付款凭证真实文件类型仅支持 JPG、PNG 或 WebP");
    }

    @Override
    public SanitizedAvatar sanitizeAvatar(byte[] bytes) {
        SanitizedImage image = sanitize(bytes);
        return new SanitizedAvatar(image.mediaType(), image.extension(), image.bytes());
    }

    private SanitizedImage reencode(byte[] bytes, String format, String mediaType, String extension) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
            validateDimensions(source);
            BufferedImage output = source;
            if ("jpeg".equals(format) && source.getType() != BufferedImage.TYPE_INT_RGB) {
                output = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = output.createGraphics();
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, output.getWidth(), output.getHeight());
                graphics.drawImage(source, 0, 0, null);
                graphics.dispose();
            }
            ByteArrayOutputStream target = new ByteArrayOutputStream(bytes.length);
            if (!ImageIO.write(output, format, target)) {
                throw invalidImage();
            }
            return new SanitizedImage(mediaType, extension, target.toByteArray());
        } catch (IOException exception) {
            throw invalidImage();
        }
    }

    private SanitizedImage sanitizeWebp(byte[] bytes) {
        if (bytes.length < 20 || littleEndianInt(bytes, 4) + 8 != bytes.length) {
            throw invalidImage();
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(bytes.length);
        int offset = 12;
        boolean hasImagePayload = false;
        while (offset + 8 <= bytes.length) {
            String chunkType = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            int chunkSize = littleEndianInt(bytes, offset + 4);
            if (chunkSize < 0 || offset + 8L + chunkSize > bytes.length) {
                throw invalidImage();
            }
            int paddedSize = chunkSize + (chunkSize & 1);
            if (offset + 8L + paddedSize > bytes.length) {
                throw invalidImage();
            }
            if (chunkType.equals("VP8 ") || chunkType.equals("VP8L") || chunkType.equals("ANMF")) {
                hasImagePayload = true;
                validateWebpImageChunk(bytes, offset + 8, chunkSize, chunkType);
            }
            if (!WEBP_METADATA_CHUNKS.contains(chunkType)) {
                byte[] chunk = java.util.Arrays.copyOfRange(bytes, offset, offset + 8 + paddedSize);
                if ("VP8X".equals(chunkType)) {
                    validateVp8x(chunk, chunkSize);
                    chunk[8] = (byte) (chunk[8] & ~WEBP_METADATA_FLAGS);
                }
                body.writeBytes(chunk);
            }
            offset += 8 + paddedSize;
        }
        if (offset != bytes.length || !hasImagePayload) {
            throw invalidImage();
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream(body.size() + 12);
        result.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        result.writeBytes(littleEndianBytes(body.size() + 4));
        result.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        result.writeBytes(body.toByteArray());
        return new SanitizedImage("image/webp", "webp", result.toByteArray());
    }

    private static void validateWebpImageChunk(byte[] bytes, int payload, int size, String type) {
        if ("VP8 ".equals(type)) {
            if (size < 10 || (bytes[payload + 3] & 0xff) != 0x9d
                    || (bytes[payload + 4] & 0xff) != 0x01
                    || (bytes[payload + 5] & 0xff) != 0x2a) {
                throw invalidImage();
            }
            validateWebpDimensions(
                    littleEndianShort(bytes, payload + 6) & 0x3fff,
                    littleEndianShort(bytes, payload + 8) & 0x3fff
            );
            return;
        }
        if ("VP8L".equals(type)) {
            if (size < 5 || (bytes[payload] & 0xff) != 0x2f) {
                throw invalidImage();
            }
            int width = 1 + (bytes[payload + 1] & 0xff)
                    + ((bytes[payload + 2] & 0x3f) << 8);
            int height = 1 + ((bytes[payload + 2] & 0xc0) >> 6)
                    + ((bytes[payload + 3] & 0xff) << 2)
                    + ((bytes[payload + 4] & 0x0f) << 10);
            validateWebpDimensions(width, height);
            return;
        }
        if (size < 24) {
            throw invalidImage();
        }
        int width = 1 + littleEndian24(bytes, payload + 6);
        int height = 1 + littleEndian24(bytes, payload + 9);
        validateWebpDimensions(width, height);
        String nestedType = new String(bytes, payload + 16, 4, StandardCharsets.US_ASCII);
        int nestedSize = littleEndianInt(bytes, payload + 20);
        if ((!nestedType.equals("VP8 ") && !nestedType.equals("VP8L"))
                || nestedSize < 0 || 24L + nestedSize > size) {
            throw invalidImage();
        }
        validateWebpImageChunk(bytes, payload + 24, nestedSize, nestedType);
    }

    private static void validateVp8x(byte[] chunk, int chunkSize) {
        if (chunkSize != 10) {
            throw invalidImage();
        }
        validateWebpDimensions(
                1 + littleEndian24(chunk, 12),
                1 + littleEndian24(chunk, 15)
        );
    }

    private static void validateWebpDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw invalidImage();
        }
    }

    private static void validateDimensions(BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0
                || image.getWidth() > MAX_DIMENSION || image.getHeight() > MAX_DIMENSION) {
            throw invalidImage();
        }
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 4 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[bytes.length - 2] & 0xff) == 0xff && (bytes[bytes.length - 1] & 0xff) == 0xd9;
    }

    private static boolean isPng(byte[] bytes) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (bytes[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
                && "RIFF".equals(new String(bytes, 0, 4, StandardCharsets.US_ASCII))
                && "WEBP".equals(new String(bytes, 8, 4, StandardCharsets.US_ASCII));
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int littleEndianShort(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
    }

    private static int littleEndian24(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16);
    }

    private static byte[] littleEndianBytes(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static DomainException invalidImage() {
        return new DomainException("PROOF_IMAGE_INVALID", "付款凭证图片损坏、尺寸异常或格式不受支持");
    }
}
