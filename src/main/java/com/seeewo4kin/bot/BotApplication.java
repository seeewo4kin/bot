package com.seeewo4kin.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling; // <-- ДОБАВЬТЕ ЭТО

@SpringBootApplication
@EnableScheduling // <-- И ЭТУ АННОТАЦИЮ
public class BotApplication {

    public static void main(String[] args) {
        SpringApplication.run(BotApplication.class, args);
    }

}