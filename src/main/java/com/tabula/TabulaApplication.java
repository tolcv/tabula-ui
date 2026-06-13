package com.tabula;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class TabulaApplication {

    public static void main(String[] args) {
        SpringApplication.run(TabulaApplication.class, args);
    }

}
