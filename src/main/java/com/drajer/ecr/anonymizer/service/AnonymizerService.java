package com.drajer.ecr.anonymizer.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.codec.Charsets;
import org.apache.commons.io.IOUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Composition.SectionComponent;
import org.hl7.fhir.r4.model.Narrative.NarrativeStatus;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedPerson;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Narrative;

import com.drajer.ecr.anonymizer.utils.FileUtils;
import com.fasterxml.jackson.core.type.TypeReference;
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

	Map<String, Object> ecrAnonymizerProfileConfigMap;

	public AnonymizerService() {
		ecrAnonymizerProfileConfigMap = getAnonymizerProfileConfig();
	}

	public Bundle processBundleXml(String bundleXml, Map<String, Object> metaDataMap) throws Exception {

		FilterDataService filterDataService = new FilterDataService();

		FhirContext fhirContext = FhirContext.forR4();
		IParser parser = fhirContext.newXmlParser();
		IParser jsonParser = fhirContext.newJsonParser();
		List<Bundle.BundleEntryComponent> updatedResourceList = new ArrayList<>();

		try {
			Bundle bundle = parser.parseResource(Bundle.class, bundleXml);
//			List<Map<String, Object>> allCustodianOrganization = getAllCustodianOrganization(bundle);
			List<Map<String, Object>> getallEmployerList = getallOdhObsEmployerList(bundle);
			removeAllContactExposure(bundle);

			for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
				IBaseResource resource = entry.getResource();
				String resourceType = fhirContext.getResourceType(resource);
				addProfile(resource, resourceType);
				String resourceJson = jsonParser.encodeResourceToString(resource);
				JsonNode updatedResource;

				updatedResource = filterCustomResource(entry, resourceJson, null, getallEmployerList, jsonParser,
						filterDataService);

				if (null == updatedResource) {
					updatedResource = filterDataService.processJson(resourceJson, metaDataMap, resourceType);
				}

				Resource updatedResourceObj = (Resource) jsonParser.parseResource(updatedResource.toString());

				Bundle.BundleEntryComponent updatedEntry = new Bundle.BundleEntryComponent()
						.setFullUrl(entry.getFullUrl()).setResource(updatedResourceObj);

				updatedResourceList.add(updatedEntry);
			}

			bundle.setEntry(updatedResourceList);
			return bundle;

		} catch (DataFormatException e) {
			throw new RuntimeException("Failed to parse XML: " + e);
		} catch (Exception e) {
			throw new RuntimeException("An unexpected error occurred while processing the XML bundle:" + e);
		}
	}


	public Bundle addReportabilityResponseInformationSection(Bundle eicrBundle, Bundle rrBundle, Map<String, Object> metaDataMap) {

		IParser jsonParser = FhirContext.forR4().newJsonParser();

		Composition eicrComposition = (Composition) getResourceByType(eicrBundle, "Composition");
		Composition rrComposition = (Composition) getResourceByType(rrBundle, "Composition");


		if (eicrComposition == null || rrComposition == null ||
				!isCompositionValid(rrComposition, RR_COMP_SECTION_CODE, LOINC_URL)) {
			return null;
		}

		SectionComponent rrSummarySection = findSectionByCode(rrComposition.getSection(), RR_SUMMARY_SECTION_CODE, LOINC_URL);
		if (rrSummarySection == null) {
			return null;
		}

		List<Map<String, Object>> conditionObservations = new ArrayList<>();
		processReferences(rrBundle, rrSummarySection.getEntry(), conditionObservations, RR_CONDITION_OBS_CODE, LOINC_URL, true);

		List<Map<String, Object>> validRRCompConditionObs = new ArrayList<>();
		List<Map<String, Object>> rrReportableInformationObs = new ArrayList<>();
		List<Map<String, Object>> performerResources = new ArrayList<>();

		for (Map<String, Object> resourceMap : conditionObservations) {
			if (processConditionObservation(rrBundle, metaDataMap, resourceMap, rrReportableInformationObs, performerResources)) {
				validRRCompConditionObs.add(resourceMap);
			}
		}

		if (validRRCompConditionObs.isEmpty()) {
			return null;
		}

		List<Reference> rrRelevantReportableObsReferenceList = createReferenceList(validRRCompConditionObs);
		addSection(eicrComposition, rrRelevantReportableObsReferenceList, RR_COMP_SECTION_CODE, LOINC_URL, REPORTABILITY_RESPONSE_SECTION_TITLE);
		addResourceMapToBundle(eicrBundle, validRRCompConditionObs);
		addResourceMapToBundle(eicrBundle, rrReportableInformationObs);
		addResourceMapToBundle(eicrBundle, performerResources);


		return eicrBundle;
	}

	private boolean processConditionObservation(Bundle rrBundle, Map<String, Object> metaDataMap,
												Map<String, Object> resourceMap, List<Map<String, Object>> rrReportableInformationObs,
												List<Map<String, Object>> performerResources) {

		Observation observation = (Observation) resourceMap.get("resource");
		if (!(observation instanceof Observation)) {
			return false;
		}

		List<Map<String, Object>> rrReportibiltyInformationList = new ArrayList<>();
		processReferences(rrBundle, observation.getHasMember(), rrReportibiltyInformationList, null, null, false);

		List<Map<String, Object>> validRRReportibiltyInformationList = new ArrayList<>();
		for (Map<String, Object> rrReportibiltyInformationMap : rrReportibiltyInformationList) {
			if (processRRReportabilityObservation(rrBundle, metaDataMap, rrReportibiltyInformationMap, observation, performerResources)) {
				validRRReportibiltyInformationList.add(rrReportibiltyInformationMap);
			}
		}

		if (!validRRReportibiltyInformationList.isEmpty()) {
			observation.setHasMember(createReferenceList(validRRReportibiltyInformationList));
			rrReportableInformationObs.addAll(validRRReportibiltyInformationList);
			return true;
		}
		return false;
	}

	private boolean processRRReportabilityObservation(Bundle rrBundle, Map<String, Object> metaDataMap,
													  Map<String, Object> rrReportibiltyInformationMap,
													  Observation observation, List<Map<String, Object>> performerResources) {

		Observation rrObservation = (Observation) rrReportibiltyInformationMap.get("resource");
		if (!(rrObservation instanceof Observation)) {
			return false;
		}

		List<Map<String, Object>> performerList = new ArrayList<>();
		processPerformerReferences(rrBundle, rrObservation.getPerformer(), performerList, "RR7", "urn:oid:2.16.840.1.114222.4.5.232", true);

		FilterDataService filterDataService = new FilterDataService();
		List<Map<String, Object>> filteredPerformers = filterDataService.filterOrganizationsByJurisdictions(performerList, metaDataMap, "jurisdictionsOrganization");

		if (!filteredPerformers.isEmpty()) {
			rrObservation.setPerformer(createReferenceList(filteredPerformers));
			performerResources.addAll(filteredPerformers);
			return true;
		}
		return false;
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

				BundleEntryComponent entry = getEntryByTypeAndId(bundle, resourceType, id, fullurl);

				Resource resource = entry != null ? entry.getResource() : null;

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
				BundleEntryComponent entry = getEntryByTypeAndId(bundle, resourceType, id, fullurl);

				Resource resource = entry != null ? entry.getResource() : null;

				if (resource instanceof Organization) {

					if (!codCheckRequired) {
						Map<String, Object> resourceMap = new HashMap<>();
						resourceMap.put("fullurl", fullurl.getValue());
						resourceMap.put("id", id);
						resourceMap.put("resource", resource);
						resourceList.add(resourceMap);
					} else {
						Organization organization = (Organization) resource;

						List<CodeableConcept> codeableConcepts = organization.hasType() ? organization.getType() : null;

						for (CodeableConcept codeableConcept : codeableConcepts) {
							if (isCodeExistValid(codeableConcept, code, system)) {

								Map<String, Object> resourceMap = new HashMap<>();
								resourceMap.put("fullurl", fullurl.getValue());
								resourceMap.put("id", id);
								resourceMap.put("resource", resource);
								resourceList.add(resourceMap);
								break;
							}

						}
					}
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

		Narrative narrative = new Narrative();
		narrative.setStatus(Narrative.NarrativeStatus.EMPTY);
		narrative.setDivAsString("<div xmlns=\"http://www.w3.org/1999/xhtml\"><p>MASKED</p></div>");

		dataAbsentReasonExtension.setUrl("http://hl7.org/fhir/StructureDefinition/data-absent-reason");
		dataAbsentReasonExtension.setValue(new CodeType("masked"));

		narrative.addExtension(dataAbsentReasonExtension);

		section.setText(narrative);

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

	public JsonNode filterCustomResource(BundleEntryComponent entry, String resourceJson,
			List<Map<String, Object>> allCustodianOrganization, List<Map<String, Object>> getAllEmployerList,
			IParser jsonParser, FilterDataService filterDataService) throws IOException {

		Resource resource = entry.getResource();
		JsonNode updatedResource = null;

		if (resource instanceof Organization) {
			/*
			 * if (isResourceInList(entry, "Organization", allCustodianOrganization)) {
			 * 
			 * updatedResource = processResource(entry.getResource(), "anon_organization",
			 * jsonParser,filterDataService, "custodianOrganization");}
			 */

			if (isResourceInList(entry, "Organization", getAllEmployerList)) {

				updatedResource = processResource(entry.getResource(), "anon_organization", jsonParser,
						filterDataService, "odh_organization");
			}
		} else if (resource instanceof RelatedPerson) {
			if (isResourceInList(entry, "RelatedPerson", getAllEmployerList)) {

				updatedResource = processResource(entry.getResource(), "relatedperson", jsonParser, filterDataService,
						"odh_employer");

			}
		}

		return updatedResource;
	}

	public boolean isResourceInList(BundleEntryComponent entryComponent, String resourceType,
			List<Map<String, Object>> resourceList) {
		Resource resource = entryComponent.getResource();

		if (!resourceType.equals(resource.getResourceType().toString())) {
			return false;
		}

		String resourceId = resource.hasIdElement() && resource.getIdElement().hasIdPart()
				? resource.getIdElement().getIdPart()
				: null;
		String entryFullUrl = entryComponent.hasFullUrl() ? entryComponent.getFullUrl() : null;

		for (Map<String, Object> resourceMap : resourceList) {
			String id = (String) resourceMap.get("id");

			String fullUrl = (String) resourceMap.get("fullurl");

			if ((resourceId != null && resourceId.equals(id))
					|| (entryFullUrl != null && fullUrl != null && entryFullUrl.equals(fullUrl))) {
				return true;
			}
		}

		return false;
	}

	public List<Map<String, Object>> getAllCustodianOrganization(Bundle eicrBundle) {
		List<Map<String, Object>> custodainOrganizationList = new ArrayList<>();
		Composition eicrComposition = (Composition) getResourceByType(eicrBundle, "Composition");

	
processPerformerReferences(eicrBundle, Collections.singletonList(eicrComposition.getCustodian()),
				custodainOrganizationList, null, null, false);
		return custodainOrganizationList;
	}

	public List<Map<String, Object>> getallOdhObsEmployerList(Bundle bundle) {
		List<Map<String, Object>> odhList = new ArrayList<>();

		for (BundleEntryComponent entry : bundle.getEntry()) {
			Resource resource = entry.getResource();

			if (resource instanceof Observation) {

				Observation observation = (Observation) resource;
				if (isCodeExistValid(observation.getCode(), "11341-5", LOINC_URL)) {

					List<Extension> extension = observation.getExtension();

					for (Extension ext : extension) {

						if (ext.hasUrl()
								&& ext.getUrl().equalsIgnoreCase(
										"http://hl7.org/fhir/us/odh/StructureDefinition/odh-Employer-extension")
								&& ext.getValue() instanceof Reference)

						{

							addProfile(resource, "odh_observation");
							processReferences(bundle, Collections.singletonList((Reference) ext.getValue()), odhList);
						}

					}
				}
			}
		}

		return odhList;
	}

	public void processReferences(Bundle bundle, List<Reference> references, List<Map<String, Object>> resourceList) {
		for (Reference reference : references) {
			if (reference.hasReferenceElement() && reference.getReferenceElement().hasIdPart()) {
				String resourceType = reference.getReferenceElement().hasResourceType()
						? reference.getReferenceElement().getResourceType()
						: null;
				String id = reference.getReferenceElement().getIdPart();

				IIdType fullurl = reference.getReferenceElement();
				BundleEntryComponent entry = getEntryByTypeAndId(bundle, resourceType, id, fullurl);

				Resource resource = entry != null ? entry.getResource() : null;

				if (resource != null) {
					Map<String, Object> resourceMap = new HashMap<>();
					resourceMap.put("fullurl", fullurl.getValue());
					resourceMap.put("id", id);
					resourceMap.put("resource", resource);
					resourceList.add(resourceMap);

				}
			}

		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getAnonymizerProfileConfig() {
		if (ecrAnonymizerProfileConfigMap == null) {
			Map<String, Object> ecrAnonymizerProfileConfigMap = FileUtils
					.readFileContents("mappings/profile_mapping_config.json", new TypeReference<Map<String, Object>>() {
					});

			if (ecrAnonymizerProfileConfigMap == null || ecrAnonymizerProfileConfigMap.isEmpty()) {
				throw new IllegalArgumentException("No Profile Found");
			}

			return ecrAnonymizerProfileConfigMap;
		} else {
			return ecrAnonymizerProfileConfigMap;
		}
	}

	private JsonNode processResource(Resource resource, String type, IParser jsonParser,
			FilterDataService filterDataService, String filterKey) throws IOException {
		addProfile(resource, type);
		String resourceJson = jsonParser.encodeResourceToString(resource);
		return filterDataService.processJson(resourceJson, null, filterKey);
	}

	public void addProfile(IBaseResource ibaseResource, String type) {

		type = type != null ? type.toLowerCase() : null;

		String profile = (String) ecrAnonymizerProfileConfigMap.getOrDefault(type, "");
		if (!profile.isEmpty()) {
			if (ibaseResource instanceof Resource) {
				Resource resource = (Resource) ibaseResource;
				resource.setMeta(getMetaData(profile));
			}

		}

	}

	public Meta getMetaData(String profile) {

		Meta meta = new Meta();
		CanonicalType profileCanonicalType = new CanonicalType();
		profileCanonicalType.setValue(profile);
		meta.getProfile().add(profileCanonicalType);

		return meta;

	}

	public void removeAllContactExposure(Bundle bundle) {
		List<String> actClassCodes = Arrays.asList("EXPOS", "AEXPOS", "TEXPOS");
		List<BundleEntryComponent> exposureContactInfoList = new ArrayList<>();

		List<Reference> exposureContactInfoRefEntryList = new ArrayList<>();
		Composition eicrComposition = (Composition) getResourceByType(bundle, "Composition");

		SectionComponent socialHistorySection = null;
		if (eicrComposition != null) {
			socialHistorySection = findSectionByCode(eicrComposition.getSection(), "29762-2", LOINC_URL);
		}

		if (socialHistorySection != null) {
			Iterator<Reference> refIterator = socialHistorySection.getEntry().iterator();
			while (refIterator.hasNext()) {
				Reference reference = refIterator.next();

				if (reference.hasReferenceElement() && reference.getReferenceElement().hasIdPart()) {
					String resourceType = reference.getReferenceElement().getResourceType();
					String id = reference.getReferenceElement().getIdPart();

					BundleEntryComponent entry = getEntryByTypeAndId(bundle, resourceType, id,
							reference.getReferenceElement());

					Resource resource = entry != null ? entry.getResource() : null;

					if (resource instanceof Observation) {
						Observation observation = (Observation) resource;
						if (containsActClassCode(observation, actClassCodes)) {
							exposureContactInfoRefEntryList.add(reference);
							exposureContactInfoList.add(entry);
						}
					}
				}
			}

			socialHistorySection.getEntry().removeAll(exposureContactInfoRefEntryList);
			bundle.getEntry().removeAll(exposureContactInfoList);
		}

	}

	private boolean containsActClassCode(Observation observation, List<String> actClassCodes) {
		for (CodeableConcept category : observation.getCategory()) {
			for (Coding coding : category.getCoding()) {
				if ("http://terminology.hl7.org/CodeSystem/v3-ActClass".equals(coding.getSystem())
						&& actClassCodes.contains(coding.getCode())) {
					return true;
				}
			}
		}
		return false;
	}

	public BundleEntryComponent getEntryByTypeAndId(Bundle bundle, String resourceType, String id, IIdType fullurl) {

		// Iterate through the entries in the bundle
		for (BundleEntryComponent entry : bundle.getEntry()) {
			Resource resource = entry.getResource();

			if (resource.getResourceType().toString().equals(resourceType) && resource.hasIdElement()
					&& resource.getIdElement().hasIdPart() && resource.getIdElement().getIdPart().equals(id)) {

				return entry;
			} else if (resource.hasIdElement() && resource.getIdElement().hasIdPart()
					&& resource.getIdElement().getIdPart().equals(id)) {
				return entry;
			} else if (fullurl != null && entry.hasFullUrl()) {
				String entryFullUrl = entry.getFullUrl();
				String fullUrlString = fullurl.getValue();
				if (entryFullUrl.equals(fullUrlString)) {
					return entry;
				}
			}
		}

		return null;

	}
}
