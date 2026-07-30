package com.centinela.api.domain.service;

import com.centinela.api.domain.model.Location;
import com.centinela.api.domain.model.Transaction;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionReceiptGeneratorTest {

    private final TransactionReceiptGenerator generator = new TransactionReceiptGenerator();

    @Test
    void generaUnPdfConLosDatosDeLaTransaccion() throws IOException {
        Location location = new Location(1L, BigDecimal.valueOf(4.7), BigDecimal.valueOf(-74.0), "Bogota");
        Transaction transaction = new Transaction(
                "tx-1", "acc-1", 150_000, "COP",
                Instant.parse("2026-07-24T10:00:00Z"), Instant.parse("2026-07-24T10:00:01Z"),
                location, "merch-1", "5411");

        byte[] pdfBytes = generator.generate(transaction);

        assertThat(pdfBytes).isNotEmpty();

        String text;
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            text = new PDFTextStripper().getText(document);
        }

        assertThat(text)
                .contains("tx-1")
                .contains("acc-1")
                .contains("1500.00")
                .contains("COP")
                .contains("merch-1")
                .contains("5411")
                .contains("Bogota");
    }

    @Test
    void indicaNoDisponibleCuandoNoHayUbicacion() throws IOException {
        Transaction transaction = new Transaction(
                "tx-2", "acc-2", 1_000, "USD",
                Instant.parse("2026-07-24T10:00:00Z"), Instant.parse("2026-07-24T10:00:01Z"),
                null, "merch-2", "5732");

        byte[] pdfBytes = generator.generate(transaction);

        String text;
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            text = new PDFTextStripper().getText(document);
        }

        assertThat(text).contains("N/D");
    }
}
