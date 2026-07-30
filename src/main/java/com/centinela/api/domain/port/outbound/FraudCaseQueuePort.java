package com.centinela.api.domain.port.outbound;

import com.centinela.api.domain.model.FraudCaseEvent;

public interface FraudCaseQueuePort {

    void publishCaseOpened(FraudCaseEvent event);
}
