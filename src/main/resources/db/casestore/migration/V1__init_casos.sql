-- Centinela - Almacén de casos de fraude (Semana 2, esquema NUEVO y DISTINTO al de
-- transacciones). Ver especificaciones en docs/weeks/Semana2-Azure.md, sección 2.2.
--
-- Este esquema vive en un Postgres SEPARADO del legado de transacciones
-- (src/main/resources/db/migration/V1__init.sql, hoy aislado tras la migración a
-- Cosmos DB): la persistencia de transacciones/scores es Cosmos, este Postgres solo
-- gestiona el ciclo de vida de los casos de fraude ya abiertos (baja escritura,
-- integridad referencial, reportería/trazabilidad — perfil de carga totalmente
-- distinto al de ingesta).
--
-- Acceso: debe quedar restringido a la subred de aplicación (no alcanzable desde
-- internet) — mecanismo de red a cargo del equipo de DevOps/Infra, no de esta migración.

CREATE TABLE estados (
    estado_id VARCHAR(30) PRIMARY KEY, -- coincide 1:1 con el enum de dominio CaseStatus
    descripcion VARCHAR(255) NOT NULL
);

INSERT INTO estados (estado_id, descripcion) VALUES
    ('ABIERTO', 'Caso recién creado por el consumidor de la cola fraud-cases, sin asignar'),
    ('EN_REVISION', 'Un analista lo tomó y lo está evaluando'),
    ('CONFIRMADO_FRAUDE', 'Un analista confirmó que la transacción es fraudulenta'),
    ('DESCARTADO', 'Un analista determinó que la activación fue un falso positivo'),
    ('CERRADO', 'Caso resuelto y cerrado (estado terminal)');

CREATE TABLE casos (
    caso_id VARCHAR(36) PRIMARY KEY, -- UUID generado por la aplicación al abrir el caso
    transaction_id VARCHAR(100) NOT NULL UNIQUE, -- referencia a la transacción en Cosmos DB;
                                                  -- no hay FK real posible entre motores distintos
    customer_id VARCHAR(50) NOT NULL,
    score INT NOT NULL, -- puntos totales que superaron el umbral (snapshot al momento de abrir el caso)
    estado_id VARCHAR(30) NOT NULL REFERENCES estados(estado_id),
    fecha_apertura TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_casos_customer_id ON casos(customer_id);
CREATE INDEX idx_casos_estado_id ON casos(estado_id);

CREATE TABLE asignaciones (
    asignacion_id VARCHAR(36) PRIMARY KEY,
    caso_id VARCHAR(36) NOT NULL REFERENCES casos(caso_id),
    analista_id VARCHAR(100) NOT NULL, -- identificador del analista (username/email); sin catálogo
                                        -- propio de analistas por ahora, fuera del modelo mínimo del TDD
    fecha_asignacion TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_asignaciones_caso_id ON asignaciones(caso_id);

CREATE TABLE resoluciones (
    resolucion_id VARCHAR(36) PRIMARY KEY,
    caso_id VARCHAR(36) NOT NULL UNIQUE REFERENCES casos(caso_id), -- una resolución final por caso
    decision VARCHAR(30) NOT NULL, -- p.ej. CONFIRMADO_FRAUDE, DESCARTADO
    analista_id VARCHAR(100) NOT NULL,
    fecha_resolucion TIMESTAMPTZ NOT NULL DEFAULT now(),
    observaciones TEXT
);

-- Registro INMUTABLE de cada cambio de estado de un caso: qué cambió, quién y cuándo.
-- Deliberadamente no referencia la tabla `estados` con FK (los guarda como texto plano):
-- una auditoría debe seguir siendo legible tal cual ocurrió el cambio aunque el catálogo
-- de estados cambie en el futuro (por ejemplo, si se retira un estado).
CREATE TABLE auditoria_casos (
    auditoria_id VARCHAR(36) PRIMARY KEY,
    caso_id VARCHAR(36) NOT NULL REFERENCES casos(caso_id),
    estado_anterior VARCHAR(30), -- NULL en la primera fila de auditoría (apertura del caso)
    estado_nuevo VARCHAR(30) NOT NULL,
    cambiado_por VARCHAR(100) NOT NULL, -- 'system' para la apertura automática, username del analista si no
    cambiado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    detalle TEXT
);

CREATE INDEX idx_auditoria_casos_caso_id ON auditoria_casos(caso_id);

-- Recomendación de endurecimiento (no aplicada aquí, a coordinar con el equipo de DevOps/Infra si se
-- desea reforzar a nivel de motor): revocar UPDATE/DELETE sobre auditoria_casos para el rol
-- de aplicación, dejando solo INSERT, de forma que la inmutabilidad no dependa únicamente
-- de que el código nunca la actualice.
