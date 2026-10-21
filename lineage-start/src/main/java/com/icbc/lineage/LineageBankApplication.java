package com.icbc.lineage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.icbc.lineage")
public class LineageBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(LineageBankApplication.class, args);
    }
}
