package com.drajer.ecranonymizer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan("com.drajer.ecranonymizer.*")
public class EcrAnonymizerApplication {

	public static void main(String[] args) {
		SpringApplication.run(EcrAnonymizerApplication.class, args);
	}

}
