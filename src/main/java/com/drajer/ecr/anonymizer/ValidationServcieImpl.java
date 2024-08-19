package com.drajer.ecr.anonymizer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r5.elementmodel.Manager.FhirFormat;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.hl7.fhir.validation.ValidationEngine;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;


public class ValidationServcieImpl  {

	ExecutorService executorService = Executors.newFixedThreadPool(32);


	public List<ValidationMessage> validateBundle(Bundle bundle ,ValidationEngine validationEngine) throws IOException {
		IParser jsonParser =  FhirContext.forR4().newJsonParser();
		System.out.println("Before validation time " + new Date());
		try {

			List<ValidationMessage> allMessages = Collections.synchronizedList(new ArrayList<>());

			List<CompletableFuture<Void>> futures = new ArrayList<>();

			validationEngine.setDisplayWarnings(false);
			for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
				CompletableFuture<Void> future = validateEntry(entry, jsonParser, validationEngine, allMessages,
						executorService);
				futures.add(future);
			}

			// Wait for all futures to complete
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

			System.out.println("After validation time " + new Date());

			return allMessages;
		} catch (DataFormatException e) {

			String errorMessage = "Failed to parse XML: " + e.getMessage();

		} catch (Exception e) {

			String errorMessage = "An unexpected error occurred while processing the XML bundle: " + e.getMessage();
		
		}
		return null;
	}

	


	private CompletableFuture<Void> validateEntry(Bundle.BundleEntryComponent entry, IParser jsonParser,
			ValidationEngine validationEngine, List<ValidationMessage> allMessages, Executor taskExecutor) {
		return CompletableFuture.runAsync(() -> {
			try {
				IBaseResource resource = entry.getResource();
				String resourceJson = jsonParser.encodeResourceToString(resource);
				List<ValidationMessage> messages = new ArrayList<>();
				if (resource.getMeta() != null && resource.getMeta().getProfile() != null
						&& !resource.getMeta().getProfile().isEmpty()) {
					String profile = resource.getMeta().getProfile().get(0).getValueAsString();
					ArrayList<String> profiles = new ArrayList<>(Collections.singletonList(profile));
					validationEngine.validate(resourceJson.getBytes(), FhirFormat.FML.JSON, profiles, messages);

					synchronized (allMessages) {
						allMessages.addAll(messages);
					}
				}
			} catch (Exception e) {
				System.err.println("Validation error: " + e.getMessage());
			}
		}, taskExecutor);
	}


	
}
