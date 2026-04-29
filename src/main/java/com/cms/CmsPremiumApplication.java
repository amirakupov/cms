package com.cms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
@EnableScheduling
public class CmsPremiumApplication {

    public static void main(String[] args) {
        SpringApplication.run(CmsPremiumApplication.class, args);
        System.out.println(new BCryptPasswordEncoder().encode("alina123"));
    }

}
