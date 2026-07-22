package com.centinela.api.infrastructure.adapter.outbound.database;

import com.azure.spring.data.cosmos.repository.CosmosRepository;

/**
 * Repositorio Spring Data para {@link CosmosTransactionDocument}. Es el equivalente a un
 * {@code JpaRepository}: Spring genera la implementación en tiempo de arranque, nosotros
 * solo declaramos la interfaz.
 *
 * <p>{@code findById(String id)} (heredado de {@link CosmosRepository}) busca por
 * transactionId SIN conocer el customerId (partition key). Como el dominio
 * ({@link com.centinela.api.domain.port.outbound.TransactionRepositoryPort#findById}) tampoco
 * recibe el customerId, esta búsqueda es necesariamente <b>cross-partition</b>: Cosmos
 * consulta todas las particiones en paralelo hasta encontrar el documento, lo cual consume
 * más RU que un point-read dirigido a una sola partición. Para el volumen de una demo de
 * Semana 1 esto es aceptable; si el costo importara en producción, la alternativa sería que
 * el llamador siempre provea también el customerId (rediseñando el puerto de dominio), o
 * mantener un índice secundario propio.
 */
public interface CosmosTransactionRepository extends CosmosRepository<CosmosTransactionDocument, String> {
}
