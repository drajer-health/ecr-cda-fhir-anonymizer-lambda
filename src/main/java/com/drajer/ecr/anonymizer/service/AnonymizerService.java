package com.drajer.ecr.anonymizer.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpStatus;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Resource;

import com.fasterxml.jackson.databind.JsonNode;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;

public class AnonymizerService {

	public String processBundleXml(String bundleXml) throws IOException {
		FilterDataService filterDataService = new FilterDataService();
		FhirContext fhirContext = FhirContext.forR4();
		IParser parser = fhirContext.newXmlParser();
		IParser jsonParser = fhirContext.newJsonParser();
		List<BundleEntryComponent> UpdatedResourcedList = new ArrayList<>();
		try {
			Bundle bundle = parser.parseResource(Bundle.class, bundleXml);

			for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
				IBaseResource resource = entry.getResource();
				BundleEntryComponent bundleEntryComponent = new BundleEntryComponent();

				String resourceType = fhirContext.getResourceType(resource);

				String resourceJson = jsonParser.encodeResourceToString(resource);
				JsonNode updatedResource = filterDataService.processJson(resourceJson, resourceType);

				bundleEntryComponent.setFullUrl(entry.getFullUrl());

				Resource updatedResourceObj = (Resource) jsonParser.parseResource(updatedResource.toString());

				bundleEntryComponent.setResource(updatedResourceObj);
				UpdatedResourcedList.add(bundleEntryComponent);

			}

			bundle.setEntry(UpdatedResourcedList);

			return parser.setPrettyPrint(true).encodeResourceToString(bundle);
		} catch (DataFormatException e) {
			return "Failed to parse XML: " + e.getMessage();
		} catch (Exception e) {
			return "An unexpected error occurred while processing the XML bundle: " + e.getMessage();
		}
	}

}
