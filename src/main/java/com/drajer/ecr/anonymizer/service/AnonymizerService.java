package com.drajer.ecr.anonymizer.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.codec.Charsets;
import org.apache.commons.io.IOUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Composition.SectionComponent;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Identifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;

public class AnonymizerService {

	private final String RR_COMP_SECTION_CODE = "88085-6";
	private final String LOINC_URL = "http://loinc.org";
	private final String RR_SUMMARY_SECTION_CODE = "55112-7";
	private final String RR_CONDITION_OBS_CODE = "75323-6";
	private final String REPORTABILITY_RESPONSE_SECTION_TITLE = "ReportabilityResponseInformationSection";
	
	public Bundle processBundleXml(String bundleXml,Map<String, Object> metaDataMap) throws Exception {
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
				JsonNode updatedResource = filterDataService.processJson(resourceJson, metaDataMap, resourceType);

				bundleEntryComponent.setFullUrl(entry.getFullUrl());

				Resource updatedResourceObj = (Resource) jsonParser.parseResource(updatedResource.toString());

				bundleEntryComponent.setResource(updatedResourceObj);
				UpdatedResourcedList.add(bundleEntryComponent);

			}

			bundle.setEntry(UpdatedResourcedList);

			return bundle;
		} catch (DataFormatException e) {
			throw new RuntimeException( "Failed to parse XML: " + e);
		} catch (Exception e) {
			throw new RuntimeException( "An unexpected error occurred while processing the XML bundle:" + e);
		}
	}

	public Bundle addReportabilityResponseInformationSection(Bundle eicrBundle, Bundle rrBundle,
			Map<String, Object> metaDataMap) {

		IParser jsonParser = FhirContext.forR4().newJsonParser();

		Composition eicrComposition = (Composition) getResourceByType(eicrBundle, "Composition");

		Composition rrComposition = (Composition) getResourceByType(rrBundle, "Composition");

		if (eicrComposition != null && rrComposition != null
				&& isCompositionValid(rrComposition, RR_COMP_SECTION_CODE, LOINC_URL)) {

			List<SectionComponent> section = rrComposition.getSection();

			SectionComponent rrSummarySection = findSectionByCode(section, RR_SUMMARY_SECTION_CODE, LOINC_URL);

			if (rrSummarySection != null) {

				List<Reference> references = rrSummarySection.getEntry();

				List<Map<String, Object>> resourceList = new ArrayList<>();
				List<Map<String, Object>> subResourceList = new ArrayList<>();
				List<Map<String, Object>> perfomerResourceList = new ArrayList<>();

				processReferences(rrBundle, references, resourceList, RR_CONDITION_OBS_CODE, LOINC_URL, true);

				if (resourceList != null && !resourceList.isEmpty()) {

					List<Reference> rrRelevantReportableObsReferenceList = createReferenceList(resourceList);

					addSection(eicrComposition, rrRelevantReportableObsReferenceList, RR_COMP_SECTION_CODE, LOINC_URL,
							REPORTABILITY_RESPONSE_SECTION_TITLE);

					for (Map<String, Object> resourceMap : resourceList) {

						Resource resource = (Resource) resourceMap.get("resource");

						if (resource instanceof Observation) {
							Observation observation = (Observation) resource;

							processReferences(rrBundle, observation.getHasMember(), subResourceList, null, null, false);

						}
					}

					for (Map<String, Object> resourceMap : subResourceList) {

						Resource resource = (Resource) resourceMap.get("resource");

						List<Map<String, Object>> performerList = new ArrayList<>();
						if (resource instanceof Observation) {
							Observation observation = (Observation) resource;

							processPerformerReferences(rrBundle, observation.getPerformer(), performerList, null, null,
									false);
							FilterDataService filterDataService = new FilterDataService();
							List<Map<String, Object>> filterOrganizationsByJurisdictions = filterDataService
									.filterOrganizationsByJurisdictions(performerList, metaDataMap,
											"jurisdictionsOrganization");

							List<Reference> validPerformer = createReferenceList(filterOrganizationsByJurisdictions);
							observation.setPerformer(validPerformer);
							perfomerResourceList.addAll(filterOrganizationsByJurisdictions);
						}
					}

					addResourceMapToBundle(eicrBundle, resourceList);
					addResourceMapToBundle(eicrBundle, subResourceList);
					addResourceMapToBundle(eicrBundle, perfomerResourceList);

				}

			}

		}
		generateBundleGuid(eicrBundle);
		return eicrBundle;

	}

	public SectionComponent findSectionByCode(List<SectionComponent> sections, String code, String system) {
		for (SectionComponent sectionComponent : sections) {
			if (isCodeExistValid(sectionComponent.getCode(), code, system)) {
				return sectionComponent;
			}
		}
		return null;
	}

	public Resource getResourceByType(Bundle bundle, String resourceType) {

		// Iterate through the entries in the bundle
		for (BundleEntryComponent entry : bundle.getEntry()) {
			Resource resource = entry.getResource();
			if (resource.getResourceType().name().equals(resourceType)) {

				return resource;
			}
		}

		return null;
	}

	public Resource getResourceByTypeAndId(Bundle bundle, String resourceType, String id, IIdType fullurl) {

		// Iterate through the entries in the bundle
		for (BundleEntryComponent entry : bundle.getEntry()) {
			Resource resource = entry.getResource();

			if (resource.getResourceType().equals(resourceType) && resource.hasId()
					&& resource.getId().equals(resource)) {

				return resource;
			} else if (resource.hasId() && resource.getId().equals(id)) {
				return resource;
			} else if (fullurl != null && entry.hasFullUrl()) {
				String entryFullUrl = entry.getFullUrl();
				String fullUrlString = fullurl.getValue();
				if (entryFullUrl.equals(fullUrlString)) {
					return resource;
				}
			}
		}

		return null;

	}

	public boolean isCompositionValid(Composition composition, String code, String system) {
		CodeableConcept type = composition.getType();

		if (type != null && type.hasCoding()) {
			Coding coding = getCodingBycode(code, system, type.getCoding());
			if (coding != null) {
				return true;
			}
		}
		return false;
	}

	public boolean isCodeExistValid(CodeableConcept cd, String code, String system) {

		if (cd != null && cd.hasCoding()) {
			Coding coding = getCodingBycode(code, system, cd.getCoding());
			if (coding != null) {
				return true;
			}
		}
		return false;
	}

	public Coding getCodingBycode(String code, String system, List<Coding> codings) {

		for (Coding coding : codings) {

			if (coding.hasCode() && coding.getCode().equals(code) && coding.hasSystem()
					&& coding.getSystem().equals(system)) {

				return coding;
			}
		}
		return null;
	}

	public void processReferences(Bundle bundle, List<Reference> references, List<Map<String, Object>> resourceList,
			String code, String system, boolean codCheckRequired) {
		for (Reference reference : references) {
			if (reference.hasReferenceElement() && reference.getReferenceElement().hasIdPart()) {
				String resourceType = reference.getReferenceElement().getResourceType();
				String id = reference.getReferenceElement().getIdPart();
				IIdType fullurl = reference.getReferenceElement();

				Resource resource = getResourceByTypeAndId(bundle, resourceType, id, fullurl);

				if (resource instanceof Observation) {
					Observation observation = (Observation) resource;
					if (!codCheckRequired || isCodeExistValid(observation.getCode(), code, system)) {
						Map<String, Object> resourceMap = new HashMap<>();
						resourceMap.put("fullurl", fullurl.getValue());
						resourceMap.put("id", id);
						resourceMap.put("resource", resource);
						resourceList.add(resourceMap);
					}
				}
			}
		}
	}

	public void processPerformerReferences(Bundle bundle, List<Reference> references,
			List<Map<String, Object>> resourceList, String code, String system, boolean codCheckRequired) {
		for (Reference reference : references) {
			if (reference.hasReferenceElement() && reference.getReferenceElement().hasIdPart()) {
				String resourceType = reference.getReferenceElement().hasResourceType()
						? reference.getReferenceElement().getResourceType()
						: null;
				String id = reference.getReferenceElement().getIdPart();

				IIdType fullurl = reference.getReferenceElement();
				Resource resource = getResourceByTypeAndId(bundle, resourceType, id, fullurl);

				if (resource instanceof Organization) {

					Map<String, Object> resourceMap = new HashMap<>();
					resourceMap.put("fullurl", fullurl.getValue());
					resourceMap.put("id", id);
					resourceMap.put("resource", resource);
					resourceList.add(resourceMap);
				}
			}

		}
	}

	public void addSection(Composition composition, List<Reference> references, String sectionCode, String system,
			String sectionTitle) {

		Composition.SectionComponent section = new Composition.SectionComponent();

		section.setTitle(sectionTitle);
		Coding code = new Coding().setSystem(system).setCode(sectionCode);
		CodeableConcept sectionCodeableConcept = new CodeableConcept().addCoding(code);
		section.setCode(sectionCodeableConcept);
		Extension dataAbsentReasonExtension = new Extension();

		dataAbsentReasonExtension.setUrl("http://hl7.org/fhir/StructureDefinition/data-absent-reason");
		dataAbsentReasonExtension.setValue(new CodeType("masked"));

		section.getText().addExtension(dataAbsentReasonExtension);

		section.setEntry(references);

		composition.addSection(section);
	}

	public static List<Reference> createReferenceList(List<Map<String, Object>> resourceSet) {
		List<Reference> referenceList = new ArrayList<>();

		for (Map<String, Object> resourceMap : resourceSet) {
			String fullurl = (String) resourceMap.get("fullurl");
			String id = (String) resourceMap.get("id");
			Resource resource = (Resource) resourceMap.get("resource");

			if (resource != null) {
				Reference reference = new Reference();

				if (id != null) {
					if (id.contains("urn:uuid:")) {
						reference.setReference(id);
					} else {
						reference.setReference(resource.fhirType() + "/" + resource.getIdElement().getIdPart());
					}
				} else if (fullurl != null) {
					reference.setReference(fullurl);
				}

				referenceList.add(reference);
			}
		}
		return referenceList;
	}

	private void addResourcesToBundle(Bundle bundle, List<Resource> resources) {
		for (Resource resource : resources) {
			BundleEntryComponent bundleEntryComponent = new BundleEntryComponent();
			bundleEntryComponent.setResource(resource);
			bundle.addEntry(bundleEntryComponent);
		}
	}

	private void addResourceMapToBundle(Bundle bundle, List<Map<String, Object>> resourcesList) {
		for (Map<String, Object> resourceMap : resourcesList) {

			Resource resource = (Resource) resourceMap.get("resource");
			String fullurl = (String) resourceMap.get("fullurl");
			BundleEntryComponent bundleEntryComponent = new BundleEntryComponent();
			bundleEntryComponent.setFullUrl(fullurl);
			bundleEntryComponent.setResource(resource);
			bundle.addEntry(bundleEntryComponent);
		}
	}

	/**
	 * method is used to convert xml to String
	 *
	 * @param inputStream
	 * @return String
	 * @throws IOException
	 */

	public String convertXmlToString(InputStream inputStream) throws IOException {
		return IOUtils.toString(inputStream, Charsets.toCharset(StandardCharsets.UTF_8));
	}

	public static Map<String, Object> streamToMap(InputStream inputStream) throws IOException {

		ObjectMapper objectMapper = new ObjectMapper();

		return objectMapper.readValue(inputStream, Map.class);
	}

	private String createUniqueFilename(String prefix) {

		return prefix + "_" + UUID.randomUUID().toString() + ".xml";
	}
	
	public static String getGuid() {
		return java.util.UUID.randomUUID().toString();
	}

	public void generateBundleGuid(Bundle eicrBundle) {
		if (eicrBundle.hasIdentifier()) {
			Identifier identifier = eicrBundle.getIdentifier();
			identifier.setValue("urn:uuid:" + getGuid());
		}
	}	
}
