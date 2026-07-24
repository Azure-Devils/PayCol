package com.centinela.api.domain.port.outbound;

import com.centinela.api.domain.model.FraudCaseEvent;

/**
 * Puerto de salida hacia la cola de casos marcados ({@code fraud-cases}). A
 * diferencia de {@link MessageQueuePort} (que solo NOTIFICA que una transacción
 * fue ingerida, sin garantía de que alguien la procese), este mecanismo exige
 * que ningún caso se pierda si el consumidor está caído — ver la justificación
 * completa en docs/decisions/002-semana2-scoring-mensajeria-y-casos.md.
 */
public interface FraudCaseQueuePort {

    void publishCaseOpened(FraudCaseEvent event);
}
