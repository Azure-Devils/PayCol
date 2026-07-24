package com.centinela.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} habilita los consumidores de cola de la Semana 2
 * ({@code TransactionEventConsumer}, {@code FraudCaseQueueConsumer}), que hacen
 * polling vía {@code @Scheduled} — ver el javadoc de esas clases para la
 * justificación de implementarlas in-process en vez de como Azure Functions.
 */
@EnableScheduling
@SpringBootApplication
public class CentinelaApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CentinelaApiApplication.class, args);
    }
}
