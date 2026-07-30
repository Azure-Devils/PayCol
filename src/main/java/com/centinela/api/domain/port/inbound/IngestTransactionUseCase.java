package com.centinela.api.domain.port.inbound;

import com.centinela.api.domain.model.IngestionResult;
import com.centinela.api.domain.model.ReceiptPhotoUpload;
import com.centinela.api.domain.model.Transaction;

public interface IngestTransactionUseCase {

    IngestionResult ingest(Transaction transaction, ReceiptPhotoUpload photoUpload);
}
