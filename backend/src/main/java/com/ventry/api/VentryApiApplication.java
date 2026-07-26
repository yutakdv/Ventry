package com.ventry.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // SessionStore 만료 스윕 (BE 리뷰 D-16)
public class VentryApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(VentryApiApplication.class, args);
    }
}
