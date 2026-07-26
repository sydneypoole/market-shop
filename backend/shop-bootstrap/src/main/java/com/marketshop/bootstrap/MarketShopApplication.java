package com.marketshop.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan("com.marketshop.infrastructure.persistence.mapper")
@SpringBootApplication(scanBasePackages = "com.marketshop")
public class MarketShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketShopApplication.class, args);
    }
}
