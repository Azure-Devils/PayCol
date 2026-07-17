-- Centinela - API de Ingesta de Transacciones (Semana 1)
-- Modelo de datos relacional normalizado en 3FN (ver especificaciones.md, sección 2)

CREATE TABLE customers (
    customer_id VARCHAR(50) PRIMARY KEY, -- Ej. 'acc_992384710'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) DEFAULT 'ACTIVE' -- ACTIVE, SUSPENDED, BLOCKED
);

CREATE TABLE locations (
    location_id SERIAL PRIMARY KEY,
    latitude NUMERIC(9,6) NOT NULL, -- Precisión exacta sin errores de float
    longitude NUMERIC(9,6) NOT NULL,
    description VARCHAR(255),
    CONSTRAINT unique_coordinates UNIQUE (latitude, longitude)
);

CREATE TABLE transactions (
    transaction_id VARCHAR(100) PRIMARY KEY, -- ID provisto por el emisor (idempotencia)
    customer_id VARCHAR(50) NOT NULL,
    amount BIGINT NOT NULL, -- Monto en centavos (Ej. 150.50 COP -> 15050)
    currency VARCHAR(3) NOT NULL, -- ISO 4217 (COP, USD)
    transaction_timestamp TIMESTAMP WITH TIME ZONE NOT NULL, -- Hora de la transacción
    ingestion_timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP, -- Hora del servidor
    location_id INT NOT NULL,
    merchant_id VARCHAR(50) NOT NULL,
    merchant_category VARCHAR(10) NOT NULL, -- MCC (Ej. 'mcc_5812')

    FOREIGN KEY (customer_id) REFERENCES customers(customer_id),
    FOREIGN KEY (location_id) REFERENCES locations(location_id)
);

-- Índice de velocidad optimizado para la Semana 2 (Reglas de velocidad)
CREATE INDEX idx_tx_customer_time ON transactions(customer_id, transaction_timestamp DESC);
