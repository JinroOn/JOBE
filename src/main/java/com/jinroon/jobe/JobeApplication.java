package com.jinroon.jobe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class JobeApplication {

	public static void main(String[] args) {
		SpringApplication.run(JobeApplication.class, args);
	}

}
