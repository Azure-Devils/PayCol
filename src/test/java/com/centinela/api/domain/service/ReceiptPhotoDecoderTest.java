package com.centinela.api.domain.service;

import com.centinela.api.domain.model.ReceiptPhoto;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptPhotoDecoderTest {

    private final ReceiptPhotoDecoder decoder = new ReceiptPhotoDecoder();

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03};
    private static final byte[] JPEG_BYTES =
            {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03};

    private static String dataUrl(String mimeType, byte[] content) {
        return "data:image/" + mimeType + ";base64," + Base64.getEncoder().encodeToString(content);
    }

    @Test
    void decodificaUnPngValido() {
        ReceiptPhoto photo = decoder.decode(dataUrl("png", PNG_BYTES));

        assertThat(photo.contentType()).isEqualTo("image/png");
        assertThat(photo.content()).isEqualTo(PNG_BYTES);
    }

    @Test
    void decodificaUnJpegValido() {
        ReceiptPhoto photo = decoder.decode(dataUrl("jpeg", JPEG_BYTES));

        assertThat(photo.contentType()).isEqualTo("image/jpeg");
        assertThat(photo.content()).isEqualTo(JPEG_BYTES);
    }

    @Test
    void detectaElContentTypeRealPorMagicBytesIgnorandoElDeclaradoPorElCliente() {
        ReceiptPhoto photo = decoder.decode(dataUrl("jpeg", PNG_BYTES));

        assertThat(photo.contentType()).isEqualTo("image/png");
    }

    @Test
    void rechazaImagenesQueSuperanElTamanoMaximoPermitido() {
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG_BYTES, 0, tooLarge, 0, PNG_BYTES.length);

        String tooLargeDataUrl = dataUrl("png", tooLarge);

        assertThatThrownBy(() -> decoder.decode(tooLargeDataUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5MB");
    }

    @Test
    void rechazaFormatosNoSoportadosPorMagicBytes() {
        byte[] notAnImage = "esto no es una imagen".getBytes();

        String dataUrl = dataUrl("png", notAnImage);

        assertThatThrownBy(() -> decoder.decode(dataUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no soportado");
    }

    @Test
    void rechazaUnaDataUrlMalformadaSinElPrefijoEsperado() {
        assertThatThrownBy(() -> decoder.decode("no-es-una-data-url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Data URL");
    }

    @Test
    void rechazaBase64Corrupto() {
        assertThatThrownBy(() -> decoder.decode("data:image/png;base64,%%%no-es-base64%%%"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base64");
    }
}
