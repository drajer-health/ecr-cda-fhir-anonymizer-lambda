package com.drajer.ecranonymizer.service.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.mail.Message;

import org.apache.commons.codec.Charsets;
import org.apache.commons.io.IOUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r5.elementmodel.Manager.FhirFormat;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.hl7.fhir.validation.ValidationEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.drajer.ecranonymizer.config.S3StorageService;
import com.drajer.ecranonymizer.service.ValidationServcie;
import com.drajer.ecranonymizer.utils.FileUtils;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

@Service
public class ValidationServcieImpl implements ValidationServcie {

	private FhirContext fhirContext;

	ValidationEngine validationEngine;

	ExecutorService executorService = Executors.newFixedThreadPool(32);

	FhirValidator validator;

	S3StorageService s3StorageService;

	@Value("${ecr.anonymizer.cache.file}")
	private String ecrAnonymizerCacheFile;

	@Autowired
	Environment environment;

	public ValidationServcieImpl(FhirContext fhirContext, ValidationEngine validationEngine,
			S3StorageService s3StorageService) {
		this.fhirContext = fhirContext;
		this.validationEngine = validationEngine;
		this.s3StorageService = s3StorageService;

	}

	@Override
	public String validateS3Bundle(String keyName) throws IOException {

		IParser parser = fhirContext.newXmlParser();
		IParser jsonParser = fhirContext.newJsonParser();
		validator = fhirContext.newValidator();
		
		String dataXml;

	
		try (ResponseInputStream<GetObjectResponse> s3File = s3StorageService.getS3File(keyName)) {
		    // Use the InputStream directly from the ResponseInputStream
		    try (InputStream inputStream = s3File) {
		        dataXml = convertXmlToString(inputStream);
		    }
		}
		System.out.println("Before validation time " + new Date());
		try {
			Bundle bundle = parser.parseResource(Bundle.class, dataXml);

			List<String> allMessages = Collections.synchronizedList(new ArrayList<>());

			List<CompletableFuture<Void>> futures = new ArrayList<>();

			for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
				CompletableFuture<Void> future = validateEntry(entry, jsonParser, validationEngine, allMessages,
						executorService);
				futures.add(future);
			}

			// Wait for all futures to complete
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

			System.out.println("After validation time " + new Date());

			Path validatorCachePath = Paths.get(ecrAnonymizerCacheFile + "/" + "validator-output");

			try {
				Files.createDirectories(validatorCachePath);
				System.out.println("Directory created or already exists: " + validatorCachePath.toString());
			} catch (IOException e) {
				System.err.println("Failed to create directory: " + e.getMessage());
			}

			Path validationStoragePath = Paths.get(validatorCachePath + "/" + "validation_errors.txt");

			FileUtils.writeErrorsToFile(allMessages, validationStoragePath);

			Path s3Path = Paths.get(keyName).getParent();
			Path ValidationfilekeyName = Paths.get(s3Path + "/validation_errors.txt");
			s3StorageService.uploadFile(ValidationfilekeyName.toString(), new File(validationStoragePath.toString()));

		} catch (DataFormatException e) {

			String errorMessage = "Failed to parse XML: " + e.getMessage();

			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage, e);
		} catch (Exception e) {

			String errorMessage = "An unexpected error occurred while processing the XML bundle: " + e.getMessage();
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, errorMessage, e);
		}
		return "Validation completed";
	}

	@Override
	public String validateBundle(MultipartFile eicr) throws IOException {
		IParser parser = fhirContext.newXmlParser();
		IParser jsonParser = fhirContext.newJsonParser();
		validator = fhirContext.newValidator();
		boolean isValidExtension = FileUtils.validateFileExtension(eicr, "xml");
		if (!isValidExtension) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Invalid file format: " + eicr.getOriginalFilename() + ". Please upload an XML file.");
		}
		String dataXml;

		try (InputStream inputStream = eicr.getInputStream()) {
			dataXml = convertXmlToString(inputStream);
		}

		System.out.println("Before validation time " + new Date());
		try {
			Bundle bundle = parser.parseResource(Bundle.class, dataXml);

			List<String> allMessages = Collections.synchronizedList(new ArrayList<>());

			List<CompletableFuture<Void>> futures = new ArrayList<>();

			for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
				CompletableFuture<Void> future = validateEntry(entry, jsonParser, validationEngine, allMessages,
						executorService);
				futures.add(future);
			}

			// Wait for all futures to complete
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

			System.out.println("After validation time " + new Date());

			FileUtils.writeErrorsToFile(allMessages, Paths.get("D://validationError//validation_errors.txt"));

		} catch (DataFormatException e) {

			String errorMessage = "Failed to parse XML: " + e.getMessage();

			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage, e);
		} catch (Exception e) {

			String errorMessage = "An unexpected error occurred while processing the XML bundle: " + e.getMessage();
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, errorMessage, e);
		}
		return "Validation completed";
	}

	public String convertXmlToString(InputStream inputStream) throws IOException {
		return IOUtils.toString(inputStream, Charsets.toCharset(StandardCharsets.UTF_8));
	}

	private CompletableFuture<Void> validateEntry(Bundle.BundleEntryComponent entry, IParser jsonParser,
			ValidationEngine validationEngine, List<String> allMessages, Executor taskExecutor) {
		return CompletableFuture.runAsync(() -> {
			try {
				IBaseResource resource = entry.getResource();
				String resourceJson = jsonParser.encodeResourceToString(resource);
				List<ValidationMessage> messages = new ArrayList<>();
				List<String> validationLogs = new ArrayList<>();

				if (resource.getMeta() != null && resource.getMeta().getProfile() != null
						&& !resource.getMeta().getProfile().isEmpty()) {
					String profile = resource.getMeta().getProfile().get(0).getValueAsString();
					ArrayList<String> profiles = new ArrayList<>(Collections.singletonList(profile));
					validationEngine.validate(resourceJson.getBytes(), FhirFormat.FML.JSON, profiles, messages);
				} else {
					ValidationResult validateWithResult = validator.validateWithResult(resourceJson);
					validationLogs.addAll(getValidationMessages(validateWithResult, resource, entry.getFullUrl()));
				}

				validationLogs.addAll(getValidationMessages(messages, resource, entry.getFullUrl()));

				synchronized (allMessages) {
					allMessages.addAll(validationLogs);
				}
			} catch (Exception e) {
				String errorMessage = String.format("Validation error in resource %s with ID %s: %s",
						entry.getResource().fhirType(), entry.getResource().getIdElement().getIdPart(), e.getMessage());
				System.err.println(errorMessage);
			}
		}, taskExecutor);
	}

	private List<String> getValidationMessages(List<ValidationMessage> messages, IBaseResource resource,
			String entryFullUrl) {
		List<String> validationLogs = new ArrayList<>();
		StringBuilder logBuilder = new StringBuilder();

		for (ValidationMessage message : messages) {
			if (message.getLevel() == ValidationMessage.IssueSeverity.ERROR) {
				validationLogs.add(formatValidationMessage(logBuilder, message, resource, entryFullUrl));
			}
		}
		return validationLogs;
	}

	private List<String> getValidationMessages(ValidationResult result, IBaseResource resource, String entryFullUrl) {
		List<String> validationLogs = new ArrayList<>();
		StringBuilder logBuilder = new StringBuilder();

		for (SingleValidationMessage message : result.getMessages()) {
			if (message.getSeverity() == ResultSeverityEnum.ERROR) {
				validationLogs.add(formatValidationMessage(logBuilder, message, resource, entryFullUrl));
			}
		}
		return validationLogs;
	}

	private String formatValidationMessage(StringBuilder logBuilder, ValidationMessage message, IBaseResource resource,
			String entryFullUrl) {
		logBuilder.setLength(0); // Reset the StringBuilder
		return logBuilder.append("ValidationMessage[line=").append(message.getLine()).append(", col=")
				.append(message.getCol()).append(", resource=").append(resource.fhirType()).append(", resourceId=")
				.append(resource.getIdElement().getIdPart()).append(", entry=").append(entryFullUrl)
				.append(", location=").append(message.getLocation()).append(":- ").append(", message=")
				.append(message.getMessage()).append("]").toString();
	}

	private String formatValidationMessage(StringBuilder logBuilder, SingleValidationMessage message,
			IBaseResource resource, String entryFullUrl) {
		logBuilder.setLength(0); // Reset the StringBuilder
		return logBuilder.append("ValidationMessage[line=").append(message.getLocationLine()).append(", col=")
				.append(message.getLocationCol()).append(", resource=").append(resource.fhirType())
				.append(", resourceId=").append(resource.getIdElement().getIdPart()).append(", entry=")
				.append(entryFullUrl).append(", location=").append(message.getLocationString()).append(":- ")
				.append(", message=").append(message.getMessage()).append("]").toString();
	}

}
