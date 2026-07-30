package com.centinela.api.domain.service;

import com.centinela.api.domain.model.Location;
import com.centinela.api.domain.model.Transaction;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class TransactionReceiptGenerator {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final float MARGIN = 50f;
    private static final float LEADING = 18f;
    private static final float BODY_FONT_SIZE = 12f;
    private static final float TITLE_FONT_SIZE = 18f;

    public byte[] generate(Transaction transaction) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            PDFont titleFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDFont bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float cursorY = page.getMediaBox().getHeight() - MARGIN;

                cursorY = writeLine(content, titleFont, TITLE_FONT_SIZE, cursorY,
                        "Comprobante de transaccion - Centinela");
                cursorY -= LEADING;

                for (String line : lines(transaction)) {
                    cursorY = writeLine(content, bodyFont, BODY_FONT_SIZE, cursorY, line);
                }
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "No se pudo generar el comprobante PDF de la transaccion " + transaction.transactionId(), e);
        }
    }

    private float writeLine(PDPageContentStream content, PDFont font, float fontSize, float cursorY, String text)
            throws IOException {
        content.beginText();
        content.setFont(font, fontSize);
        content.newLineAtOffset(MARGIN, cursorY);
        content.showText(text);
        content.endText();
        return cursorY - LEADING;
    }

    private List<String> lines(Transaction transaction) {
        BigDecimal amount = BigDecimal.valueOf(transaction.amountCents(), 2);
        return List.of(
                "ID de transaccion: " + transaction.transactionId(),
                "Cliente: " + transaction.customerId(),
                "Monto: " + amount + " " + transaction.currency(),
                "Fecha de transaccion: " + TIMESTAMP_FORMAT.format(transaction.transactionTimestamp()),
                "Comercio: " + transaction.merchantId() + " (" + transaction.merchantCategory() + ")",
                "Ubicacion: " + locationLine(transaction.location())
        );
    }

    private String locationLine(Location location) {
        if (location == null) {
            return "N/D";
        }
        String description = location.description() == null || location.description().isBlank()
                ? ""
                : " - " + location.description();
        return location.latitude() + ", " + location.longitude() + description;
    }
}
