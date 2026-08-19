package com.centinela.api.domain.service;

import com.centinela.api.domain.model.ReceiptPhoto;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReceiptPhotoDecoder {

    private static final Pattern DATA_URL_PATTERN =
            Pattern.compile("^data:image/[^;]+;base64,(.*)$", Pattern.DOTALL);
    private static final int MAX_DECODED_SIZE_BYTES = 5 * 1024 * 1024;

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF_MAGIC = {0x47, 0x49, 0x46, 0x38};
    private static final byte[] RIFF_MAGIC = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP_MAGIC = {0x57, 0x45, 0x42, 0x50};

    public ReceiptPhoto decode(String dataUrl) {
        Matcher matcher = DATA_URL_PATTERN.matcher(dataUrl == null ? "" : dataUrl);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("formato de Data URL inválido");
        }

        byte[] content;
        try {
            content = Base64.getDecoder().decode(matcher.group(1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("contenido Base64 inválido", e);
        }

        if (content.length > MAX_DECODED_SIZE_BYTES) {
            throw new IllegalArgumentException("la imagen supera el tamaño máximo permitido de 5MB");
        }

        return new ReceiptPhoto(content, detectContentType(content));
    }

    private String detectContentType(byte[] content) {
        if (matches(content, 0, PNG_MAGIC)) {
            return "image/png";
        }
        if (matches(content, 0, JPEG_MAGIC)) {
            return "image/jpeg";
        }
        if (matches(content, 0, GIF_MAGIC)) {
            return "image/gif";
        }
        if (matches(content, 0, RIFF_MAGIC) && matches(content, 8, WEBP_MAGIC)) {
            return "image/webp";
        }
        throw new IllegalArgumentException(
                "formato de imagen no soportado, se esperaba JPEG/PNG/WEBP/GIF");
    }

    private boolean matches(byte[] content, int offset, byte[] magic) {
        if (content.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
