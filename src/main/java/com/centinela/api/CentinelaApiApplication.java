package com.centinela.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CentinelaApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CentinelaApiApplication.class, args);
    }
}
