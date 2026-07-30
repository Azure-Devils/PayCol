package com.centinela.api.domain.port.inbound;

import com.centinela.api.domain.model.Transaction;

public interface IngestTransactionUseCase {

    Transaction ingest(Transaction transaction);
}
