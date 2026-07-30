package com.centinela.api.domain.service;

import com.centinela.api.domain.model.IngestionResult;
import com.centinela.api.domain.model.ReceiptPhoto;
import com.centinela.api.domain.model.ReceiptPhotoUpload;
import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.port.inbound.IngestTransactionUseCase;
import com.centinela.api.domain.port.outbound.MessageQueuePort;
import com.centinela.api.domain.port.outbound.ReceiptPhotoStoragePort;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;
import com.centinela.api.domain.port.outbound.TransactionReceiptStoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public class IngestTransactionService implements IngestTransactionUseCase {

    private static final Logger log = LoggerFactory.getLogger(IngestTransactionService.class);

    private final TransactionRepositoryPort transactionRepository;
    private final MessageQueuePort messageQueue;
    private final TransactionReceiptGenerator receiptGenerator;
    private final TransactionReceiptStoragePort receiptStorage;
    private final ReceiptPhotoDecoder receiptPhotoDecoder;
    private final ReceiptPhotoStoragePort receiptPhotoStorage;

    public IngestTransactionService(TransactionRepositoryPort transactionRepository,
                                     MessageQueuePort messageQueue,
                                     TransactionReceiptGenerator receiptGenerator,
                                     TransactionReceiptStoragePort receiptStorage,
                                     ReceiptPhotoDecoder receiptPhotoDecoder,
                                     ReceiptPhotoStoragePort receiptPhotoStorage) {
        this.transactionRepository = transactionRepository;
        this.messageQueue = messageQueue;
        this.receiptGenerator = receiptGenerator;
        this.receiptStorage = receiptStorage;
        this.receiptPhotoDecoder = receiptPhotoDecoder;
        this.receiptPhotoStorage = receiptPhotoStorage;
    }

    @Override
    public IngestionResult ingest(Transaction transaction, ReceiptPhotoUpload photoUpload) {
        validate(transaction);

        Optional<Transaction> existing = transactionRepository.findById(transaction.transactionId());
        if (existing.isPresent()) {
            return new IngestionResult(existing.get(), false);
        }

        ReceiptPhoto receiptPhoto = decodePhotoIfPresent(photoUpload);

        Transaction withServerTimestamp = transaction.withIngestionTimestamp(Instant.now());
        Transaction persisted = transactionRepository.save(withServerTimestamp);
        messageQueue.publish(persisted);
        generateAndStoreReceipt(persisted);
        boolean receiptUploaded = storeReceiptPhoto(persisted, receiptPhoto);

        return new IngestionResult(persisted, receiptUploaded);
    }

    private ReceiptPhoto decodePhotoIfPresent(ReceiptPhotoUpload photoUpload) {
        if (photoUpload == null || photoUpload.photo() == null || photoUpload.photo().isBlank()) {
            return null;
        }
        return receiptPhotoDecoder.decode(photoUpload.photo());
    }

    private boolean storeReceiptPhoto(Transaction transaction, ReceiptPhoto receiptPhoto) {
        if (receiptPhoto == null) {
            return false;
        }
        try {
            receiptPhotoStorage.store(transaction.transactionId(), receiptPhoto.content(), receiptPhoto.contentType());
            return true;
        } catch (Exception e) {
            log.error("No se pudo almacenar la foto de comprobante de la transaccion {}: {}",
                    transaction.transactionId(), e.getMessage(), e);
            return false;
        }
    }

    private void generateAndStoreReceipt(Transaction transaction) {
        try {
            byte[] pdf = receiptGenerator.generate(transaction);
            receiptStorage.store(transaction.transactionId(), pdf);
        } catch (Exception e) {
            log.error("No se pudo generar/almacenar el comprobante PDF de la transaccion {}: {}",
                    transaction.transactionId(), e.getMessage(), e);
        }
    }

    private void validate(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction no puede ser null");
        require(transaction.transactionId() != null && !transaction.transactionId().isBlank(),
                "transactionId es obligatorio");
        require(transaction.customerId() != null && !transaction.customerId().isBlank(),
                "customerId es obligatorio");
        require(transaction.amountCents() > 0, "amountCents debe ser mayor que cero");
        require(transaction.currency() != null && transaction.currency().length() == 3,
                "currency debe ser un código ISO 4217 de 3 letras");
        require(transaction.transactionTimestamp() != null,
                "transactionTimestamp es obligatorio");
        require(transaction.location() != null, "location es obligatorio");
        require(transaction.merchantId() != null && !transaction.merchantId().isBlank(),
                "merchantId es obligatorio");
        require(transaction.merchantCategory() != null && !transaction.merchantCategory().isBlank(),
                "merchantCategory es obligatorio");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
