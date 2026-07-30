package com.centinela.api.domain.service;

import com.centinela.api.domain.model.IngestionResult;
import com.centinela.api.domain.model.Location;
import com.centinela.api.domain.model.ReceiptPhotoUpload;
import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.model.TransactionScore;
import com.centinela.api.domain.port.outbound.MessageQueuePort;
import com.centinela.api.domain.port.outbound.ReceiptPhotoStoragePort;
import com.centinela.api.domain.port.outbound.TransactionReceiptStoragePort;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestTransactionServiceTest {

    private static final String VALID_PNG_DATA_URL =
            "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[] {
                    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02
            });

    private static Transaction tx(String id) {
        Location location = new Location(1L, BigDecimal.valueOf(4.7), BigDecimal.valueOf(-74.0), "test");
        return new Transaction(id, "acc1", 10_000, "COP",
                Instant.parse("2026-07-24T10:00:00Z"), null, location, "m1", "5411");
    }

    private static IngestTransactionService newService(TransactionRepositoryPort repository,
                                                         MessageQueuePort messageQueue,
                                                         TransactionReceiptStoragePort receiptStorage,
                                                         ReceiptPhotoStoragePort receiptPhotoStorage) {
        return new IngestTransactionService(repository, messageQueue, new TransactionReceiptGenerator(),
                receiptStorage, new ReceiptPhotoDecoder(), receiptPhotoStorage);
    }

    @Test
    void alIngestarGeneraYAlmacenaElComprobante() {
        InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
        RecordingMessageQueue messageQueue = new RecordingMessageQueue();
        RecordingReceiptStorage receiptStorage = new RecordingReceiptStorage();
        IngestTransactionService service = newService(
                repository, messageQueue, receiptStorage, new RecordingReceiptPhotoStorage());

        IngestionResult result = service.ingest(tx("tx-1"), null);

        assertThat(result.transaction().transactionId()).isEqualTo("tx-1");
        assertThat(result.receiptUploaded()).isFalse();
        assertThat(receiptStorage.stored).containsKey("tx-1");
        assertThat(receiptStorage.stored.get("tx-1")).isNotEmpty();
        assertThat(messageQueue.published).hasSize(1);
    }

    @Test
    void noFallaLaIngestaSiElAlmacenamientoDelComprobanteFalla() {
        InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
        RecordingMessageQueue messageQueue = new RecordingMessageQueue();
        TransactionReceiptStoragePort failingStorage = new TransactionReceiptStoragePort() {
            @Override
            public void store(String transactionId, byte[] pdfBytes) {
                throw new RuntimeException("almacenamiento no disponible");
            }

            @Override
            public Optional<byte[]> retrieve(String transactionId) {
                return Optional.empty();
            }
        };
        IngestTransactionService service = newService(
                repository, messageQueue, failingStorage, new RecordingReceiptPhotoStorage());

        IngestionResult result = service.ingest(tx("tx-2"), null);

        assertThat(result.transaction().transactionId()).isEqualTo("tx-2");
        assertThat(repository.findById("tx-2")).isPresent();
    }

    @Test
    void esIdempotenteYNoRegeneraElComprobanteParaUnaTransaccionYaIngerida() {
        InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
        RecordingMessageQueue messageQueue = new RecordingMessageQueue();
        RecordingReceiptStorage receiptStorage = new RecordingReceiptStorage();
        IngestTransactionService service = newService(
                repository, messageQueue, receiptStorage, new RecordingReceiptPhotoStorage());

        service.ingest(tx("tx-3"), null);
        IngestionResult second = service.ingest(tx("tx-3"), null);

        assertThat(messageQueue.published).hasSize(1);
        assertThat(receiptStorage.stored).hasSize(1);
        assertThat(second.receiptUploaded()).isFalse();
    }

    @Test
    void almacenaLaFotoDeComprobanteCuandoVieneUnaValida() {
        InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
        RecordingMessageQueue messageQueue = new RecordingMessageQueue();
        RecordingReceiptStorage receiptStorage = new RecordingReceiptStorage();
        RecordingReceiptPhotoStorage receiptPhotoStorage = new RecordingReceiptPhotoStorage();
        IngestTransactionService service = newService(
                repository, messageQueue, receiptStorage, receiptPhotoStorage);

        IngestionResult result = service.ingest(
                tx("tx-4"), new ReceiptPhotoUpload(VALID_PNG_DATA_URL, "comprobante.png"));

        assertThat(result.receiptUploaded()).isTrue();
        assertThat(receiptPhotoStorage.stored).containsKey("tx-4");
        assertThat(receiptPhotoStorage.contentTypes.get("tx-4")).isEqualTo("image/png");
    }

    @Test
    void rechazaTodaLaTransaccionSiLaFotoEsInvalidaYNoPersisteNada() {
        InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
        RecordingMessageQueue messageQueue = new RecordingMessageQueue();
        RecordingReceiptStorage receiptStorage = new RecordingReceiptStorage();
        RecordingReceiptPhotoStorage receiptPhotoStorage = new RecordingReceiptPhotoStorage();
        IngestTransactionService service = newService(
                repository, messageQueue, receiptStorage, receiptPhotoStorage);

        ReceiptPhotoUpload invalidPhoto = new ReceiptPhotoUpload("data:image/png;base64,no-es-base64", "foto.png");

        assertThatThrownBy(() -> service.ingest(tx("tx-5"), invalidPhoto))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(repository.findById("tx-5")).isEmpty();
        assertThat(messageQueue.published).isEmpty();
        assertThat(receiptPhotoStorage.stored).isEmpty();
    }

    @Test
    void noFallaLaIngestaSiElAlmacenamientoDeLaFotoFalla() {
        InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
        RecordingMessageQueue messageQueue = new RecordingMessageQueue();
        RecordingReceiptStorage receiptStorage = new RecordingReceiptStorage();
        ReceiptPhotoStoragePort failingPhotoStorage = new ReceiptPhotoStoragePort() {
            @Override
            public void store(String transactionId, byte[] content, String contentType) {
                throw new RuntimeException("almacenamiento de fotos no disponible");
            }
        };
        IngestTransactionService service = newService(
                repository, messageQueue, receiptStorage, failingPhotoStorage);

        IngestionResult result = service.ingest(
                tx("tx-6"), new ReceiptPhotoUpload(VALID_PNG_DATA_URL, "comprobante.png"));

        assertThat(result.receiptUploaded()).isFalse();
        assertThat(repository.findById("tx-6")).isPresent();
    }

    private static class InMemoryTransactionRepository implements TransactionRepositoryPort {
        private final List<Transaction> stored = new ArrayList<>();

        @Override
        public Optional<Transaction> findById(String transactionId) {
            return stored.stream().filter(t -> t.transactionId().equals(transactionId)).findFirst();
        }

        @Override
        public Transaction save(Transaction transaction) {
            stored.add(transaction);
            return transaction;
        }

        @Override
        public List<Transaction> findMostRecentByCustomer(String customerId, int limit) {
            return List.of();
        }

        @Override
        public void saveScore(TransactionScore score) {
        }

        @Override
        public Optional<TransactionScore> findScore(String transactionId) {
            return Optional.empty();
        }
    }

    private static class RecordingMessageQueue implements MessageQueuePort {
        private final List<Transaction> published = new ArrayList<>();

        @Override
        public void publish(Transaction transaction) {
            published.add(transaction);
        }
    }

    private static class RecordingReceiptStorage implements TransactionReceiptStoragePort {
        private final java.util.Map<String, byte[]> stored = new java.util.HashMap<>();

        @Override
        public void store(String transactionId, byte[] pdfBytes) {
            stored.put(transactionId, pdfBytes);
        }

        @Override
        public Optional<byte[]> retrieve(String transactionId) {
            return Optional.ofNullable(stored.get(transactionId));
        }
    }

    private static class RecordingReceiptPhotoStorage implements ReceiptPhotoStoragePort {
        private final java.util.Map<String, byte[]> stored = new java.util.HashMap<>();
        private final java.util.Map<String, String> contentTypes = new java.util.HashMap<>();

        @Override
        public void store(String transactionId, byte[] content, String contentType) {
            stored.put(transactionId, content);
            contentTypes.put(transactionId, contentType);
        }
    }
}
