package com.cms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CmsPremiumApplication {

    public static void main(String[] args) {
        SpringApplication.run(CmsPremiumApplication.class, args);
    }

}
