package com.centinela.api.domain.port.outbound;

import com.centinela.api.domain.model.Transaction;

public interface MessageQueuePort {

    void publish(Transaction transaction);
}
