package com.drajer.ecr.anonymizer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.codec.Charsets;
import org.apache.commons.io.IOUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;
import org.hl7.fhir.r5.elementmodel.Manager.FhirFormat;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.hl7.fhir.validation.ValidationEngine;

import com.fasterxml.jackson.databind.ObjectMapper;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;


public class ValidationServcieImpl  {

	ExecutorService executorService = Executors.newFixedThreadPool(32);
	private static final ObjectMapper objectMapper = new ObjectMapper();
	private FhirContext fhirContext;
	FhirValidator validator;


	public Object validateBundle(Bundle bundle ,ValidationEngine validationEngine) throws Exception {
		fhirContext=FhirContext.forR4();
		validator = fhirContext.newValidator();
		IParser jsonParser =  FhirContext.forR4().newJsonParser();
		System.out.println("Before validation time " + new Date());
		try {

			List<OperationOutcomeIssueComponent> allMessages = Collections.synchronizedList(new ArrayList<>());

			List<CompletableFuture<Void>> futures = new ArrayList<>();

			for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
				CompletableFuture<Void> future = validateEntry(entry, jsonParser, validationEngine, allMessages,
						executorService);
				futures.add(future);
			}

			// Wait for all futures to complete
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

			return createValidationResponse(allMessages);

		} catch (Exception e) {

			String errorMessage = "An unexpected error occurred while processing the XML bundle: " + e.getMessage();
			throw new Exception( errorMessage, e);
		}
	}

	public String convertXmlToString(InputStream inputStream) throws IOException {
		return IOUtils.toString(inputStream, Charsets.toCharset(StandardCharsets.UTF_8));
	}

	private CompletableFuture<Void> validateEntry(Bundle.BundleEntryComponent entry, IParser jsonParser,
			ValidationEngine validationEngine, List<OperationOutcomeIssueComponent> allMessages,
			Executor taskExecutor) {
		return CompletableFuture.runAsync(() -> {
			try {
				IBaseResource resource = entry.getResource();
				String resourceJson = jsonParser.encodeResourceToString(resource);
				List<ValidationMessage> messages = new ArrayList<>();
				List<OperationOutcomeIssueComponent> validationIssues = new ArrayList<>();

				if (resource.getMeta() != null && resource.getMeta().getProfile() != null
						&& !resource.getMeta().getProfile().isEmpty()) {
					String profile = resource.getMeta().getProfile().get(0).getValueAsString();
					ArrayList<String> profiles = new ArrayList<>(Collections.singletonList(profile));
					validationEngine.validate(resourceJson.getBytes(), FhirFormat.FML.JSON, profiles, messages);
				} else {
					ValidationResult validateWithResult = validator.validateWithResult(resourceJson);
					validationIssues.addAll(getValidationMessages(validateWithResult, resource, entry.getFullUrl()));
				}

				validationIssues.addAll(getValidationMessages(messages, resource, entry.getFullUrl()));

				synchronized (allMessages) {
					allMessages.addAll(validationIssues);
				}
			} catch (Exception e) {
				String errorMessage = String.format("Validation error in resource %s with ID %s: %s",
						entry.getResource().fhirType(), entry.getResource().getIdElement().getIdPart(), e.getMessage());
				System.err.println(errorMessage);
			}
		}, taskExecutor);
	}

	private List<OperationOutcomeIssueComponent> getValidationMessages(List<ValidationMessage> messages,
			IBaseResource resource, String entryFullUrl) {
		List<OperationOutcomeIssueComponent> validationIssues = new ArrayList<>();
		StringBuilder logBuilder = new StringBuilder();

		for (ValidationMessage message : messages) {
			if (message.getLevel() == ValidationMessage.IssueSeverity.ERROR) {
				String diagnosticsMessage = formatValidationMessage(logBuilder, message, resource, entryFullUrl);
				validationIssues.add(createIssue(message.getMessage(), message.getLocation(), diagnosticsMessage));
			}
		}
		return validationIssues;
	}

	private List<OperationOutcomeIssueComponent> getValidationMessages(ValidationResult result, IBaseResource resource,
			String entryFullUrl) {
		List<OperationOutcomeIssueComponent> validationIssues = new ArrayList<>();
		StringBuilder logBuilder = new StringBuilder();

		for (SingleValidationMessage message : result.getMessages()) {
			if (message.getSeverity() == ResultSeverityEnum.ERROR) {
				String diagnosticsMessage = formatValidationMessage(logBuilder, message, resource, entryFullUrl);
				validationIssues
						.add(createIssue(message.getMessage(), message.getLocationString(), diagnosticsMessage));
			}
		}
		return validationIssues;
	}

	private String formatValidationMessage(StringBuilder logBuilder, SingleValidationMessage message,
			IBaseResource resource, String entryFullUrl) {
		logBuilder.setLength(0); // Reset the StringBuilder
		return logBuilder.append("line=").append(message.getLocationLine()).append(", col=")
				.append(message.getLocationCol()).append(", resource=").append(resource.fhirType())
				.append(", resourceId=").append(resource.getIdElement().getIdPart()).append(", entry=")
				.append(entryFullUrl).append(", location=").append(message.getLocationString()).append(":- ")
				.append(", message=").append(message.getMessage()).append(System.lineSeparator()).

				toString();

	}

	private String formatValidationMessage(StringBuilder logBuilder, ValidationMessage message, IBaseResource resource,
			String entryFullUrl) {
		logBuilder.setLength(0); // Reset the StringBuilder
		return logBuilder.append("line=").append(message.getLine()).append(", col=").append(message.getCol())
				.append(", resource=").append(resource.fhirType()).append(", resourceId=")
				.append(resource.getIdElement().getIdPart()).append(", entry=").append(entryFullUrl)
				.append(", location=").append(message.getLocation()).append(":- ").append(", message=")
				.append(message.getMessage()).append(System.lineSeparator()).toString();

	}

	private OperationOutcomeIssueComponent createIssue(String message, String location, String diagnosticsMessage) {
		OperationOutcomeIssueComponent issue = new OperationOutcomeIssueComponent();
		issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        issue.setCode(OperationOutcome.IssueType.INVALID);
		issue.setDetails(new CodeableConcept().setText(message));
		issue.addLocation(location);
		issue.setDiagnostics(diagnosticsMessage);

		return issue;
	}

	private Object createValidationResponse(List<OperationOutcomeIssueComponent> allMessages) {
		if (allMessages.isEmpty()) {
			return createOperationOutcome("Validation completed successfully.",
					OperationOutcome.IssueSeverity.INFORMATION, OperationOutcome.IssueType.INFORMATIONAL);
		}
		OperationOutcome operationOutcome = new OperationOutcome();
		allMessages.forEach(operationOutcome::addIssue);
		return createOperationOutcomeFromOutcome(operationOutcome);
	}

	private Object createOperationOutcome(String message, OperationOutcome.IssueSeverity severity,
			OperationOutcome.IssueType type) {
		OperationOutcome operationOutcome = new OperationOutcome();
		operationOutcome.addIssue().setSeverity(severity).setCode(type)
				.setDetails(new CodeableConcept().setText(message));

		operationOutcome.setMeta(createCurrentMeta());

		return parseToJson(operationOutcome);
	}

	private Object createOperationOutcomeFromOutcome(OperationOutcome operationOutcome) {
		operationOutcome.setMeta(createCurrentMeta());
		return parseToJson(operationOutcome);
	}

	private Meta createCurrentMeta() {
		Meta meta = new Meta();
		meta.setLastUpdatedElement(new InstantType(new Date()));
		return meta;
	}

	private Object parseToJson(OperationOutcome operationOutcome) {
		try {
			String json = fhirContext.newJsonParser().encodeResourceToString(operationOutcome);
			return objectMapper.readTree(json);
		} catch (Exception e) {
			return null;
		}
	}
	
}
