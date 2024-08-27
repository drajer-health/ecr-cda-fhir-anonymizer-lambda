package com.drajer.ecranonymizer.controller;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.drajer.ecranonymizer.service.ValidationServcie;

@RestController
public class validatorController {

	@Autowired
	ValidationServcie validationServcie;

	@PostMapping("/api/validator")
	public String localValidator(@RequestPart MultipartFile eicrData) throws IOException {
		return validationServcie.validateBundle(eicrData);

	}
	
	@PostMapping("/api/fhir/validator")
	public String fhirValidator(@RequestParam String keyName) throws IOException {
		return validationServcie.validateS3Bundle(keyName);

	}

}
