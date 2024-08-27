
package com.drajer.ecranonymizer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ca.uhn.fhir.context.FhirContext;

@Configuration
public class SpringConfiguaration{
	 public static final FhirContext ctx = FhirContext.forR4();
	
	 @Bean
	  public FhirContext fhirContext() {
	    return ctx;
	  }
	 
	
}