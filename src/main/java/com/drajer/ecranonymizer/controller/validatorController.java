package com.drajer.ecranonymizer.controller;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.drajer.ecranonymizer.config.S3StorageService;
import com.drajer.ecranonymizer.service.ValidationServcie;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

@RestController
public class validatorController {

	@Autowired
	ValidationServcie validationServcie;
	
	@Autowired
	S3StorageService s3StorageService;

	@PostMapping("/api/validator")
	public String localValidator(@RequestPart MultipartFile eicrData) throws IOException {
		return validationServcie.validateBundle(eicrData);

	}
	
	@PostMapping("/api/fhir/validator")
	public String fhirValidator(@RequestBody ValidationRequestDto validationRequestDto) throws IOException {
		
		return validationServcie.validateS3Bundle(validationRequestDto.getKeyName());
		
	

	}

}
