package com.drajer.ecranonymizer.service;

import java.io.IOException;

import org.hl7.fhir.r4.model.Bundle;
import org.springframework.web.multipart.MultipartFile;

public interface ValidationServcie {

	public String validateBundle(MultipartFile eicr) throws IOException;

	String validateS3Bundle(String KeyName) throws IOException;
}