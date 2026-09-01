package com.ssafy.keeping.qr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication(exclude = KafkaAutoConfiguration.class)
public class QrServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(QrServiceApplication.class, args);
  }
}
