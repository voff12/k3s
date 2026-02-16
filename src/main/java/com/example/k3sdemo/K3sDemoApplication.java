package com.example.k3sdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class K3sDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(K3sDemoApplication.class, args);
    }

}
