package com.spendwise;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpendWiseApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpendWiseApplication.class, args);
        System.out.println("=================================================");
        System.out.println(" SpendWise AI Backend Started on http://localhost:8080");
        System.out.println(" API Health: http://localhost:8080/api/v1/health");
        System.out.println("=================================================");
    }
}
