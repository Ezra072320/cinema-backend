package edu.czu.cinema;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableFeignClients(basePackages = "edu.czu.cinema.remote")
@EnableScheduling
public class BenefitApplication {
  public static void main(String[] args) {
    SpringApplication.run(BenefitApplication.class, args);
  }
}
