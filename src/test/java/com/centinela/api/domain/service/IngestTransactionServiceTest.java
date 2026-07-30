package com.centinela.api.domain.service;

import com.centinela.api.domain.model.Location;
import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.model.TransactionScore;
import com.centinela.api.domain.port.outbound.MessageQueuePort;
import com.centinela.api.domain.port.outbound.TransactionReceiptStoragePort;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IngestTransactionServiceTest {

    private static Transaction tx(String id) {
        Location location = new Location(1L, BigDecimal.valueOf(4.7), BigDecimal.valueOf(-74.0), "test");
        return new Transaction(id, "acc1", 10_000, "COP",
                Instant.parse("2026-07-24T10:00:00Z"), null, location, "m1", "5411");
    }

    @Test
    void alIngestarGeneraYAlmacenaElComprobante() {
        InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
        RecordingMessageQueue messageQueue = new RecordingMessageQueue();
        RecordingReceiptStorage receiptStorage = new RecordingReceiptStorage();
        IngestTransactionService service = new IngestTransactionService(
                repository, messageQueue, new TransactionReceiptGenerator(), receiptStorage);

        Transaction persisted = service.ingest(tx("tx-1"));

        assertThat(persisted.transactionId()).isEqualTo("tx-1");
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
        IngestTransactionService service = new IngestTransactionService(
                repository, messageQueue, new TransactionReceiptGenerator(), failingStorage);

        Transaction persisted = service.ingest(tx("tx-2"));

        assertThat(persisted.transactionId()).isEqualTo("tx-2");
        assertThat(repository.findById("tx-2")).isPresent();
    }

    @Test
    void esIdempotenteYNoRegeneraElComprobanteParaUnaTransaccionYaIngerida() {
        InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
        RecordingMessageQueue messageQueue = new RecordingMessageQueue();
        RecordingReceiptStorage receiptStorage = new RecordingReceiptStorage();
        IngestTransactionService service = new IngestTransactionService(
                repository, messageQueue, new TransactionReceiptGenerator(), receiptStorage);

        service.ingest(tx("tx-3"));
        service.ingest(tx("tx-3"));

        assertThat(messageQueue.published).hasSize(1);
        assertThat(receiptStorage.stored).hasSize(1);
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
}
