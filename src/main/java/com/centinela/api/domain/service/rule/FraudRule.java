package com.centinela.api.domain.service.rule;

import com.centinela.api.domain.model.RuleActivation;
import com.centinela.api.domain.model.Transaction;

import java.util.List;
import java.util.Optional;

/**
 * Contrato de una regla de detección de fraude (sección 2.3 del TDD de Semana 2).
 * Puro dominio: nada de Spring/Azure aquí, para que cada regla sea testeable en
 * aislamiento con JUnit plano.
 */
public interface FraudRule {

    /**
     * Evalúa la transacción actual contra el historial reciente de la MISMA cuenta
     * (ya acotado y ordenado del más nuevo al más viejo por el llamador — ver
     * {@code TransactionRepositoryPort#findMostRecentByCustomer}). El historial NO
     * incluye la transacción actual.
     *
     * @return la activación con los valores concretos observados, o vacío si la
     *         regla no se disparó.
     */
    Optional<RuleActivation> evaluate(Transaction current, List<Transaction> recentHistory);
}
