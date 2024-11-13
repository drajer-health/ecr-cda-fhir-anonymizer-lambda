package com.drajer.ecr.anonymizer.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.drajer.ecr.anonymizer.TestUtils;
import com.drajer.ecr.anonymizer.utils.FileUtils;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class AnonymizerServiceTest {

    private static final String RR_BUNDLE_FILE_LOC = "FHIR_BUNDLE/RR.json";
    private static final String EICR_BUNDLE_FILE_LOC = "FHIR_BUNDLE/EICR.xml";
    private static final String RR_WITH_MULTIPLE_ORG_FILE_LOC = "FHIR_BUNDLE/RR_With_Multiple_Org.json";
    private static final String RR_WITH_MULTIPLE_ORG_INVALID_JUD_FILE_LOC = "FHIR_BUNDLE/RR_With_Multiple_Org_WithValidJurisdiction.json";

    private static final FhirContext fhirContext = FhirContext.forR4();
    private static final IParser jsonParser = fhirContext.newJsonParser();
    private static final IParser xmlParser = fhirContext.newXmlParser();

    private final AnonymizerService anonymizerService = new AnonymizerService();

    private Bundle rrBundle;
    private Bundle eicrBundle;
    private Map<String, Object> metadata;

    @Before
    public void setUp() {
        eicrBundle = xmlParser.parseResource(Bundle.class, TestUtils.getFileContentAsString(EICR_BUNDLE_FILE_LOC));
        metadata = new HashMap<>();
        metadata.put("jurisdictionsToRetain", "chi");
    }

    @Test
    public void testAddRRInformationSection_ValidRouteEntityOrg() {
        rrBundle = jsonParser.parseResource(Bundle.class, TestUtils.getFileContentAsString(RR_BUNDLE_FILE_LOC));
        Bundle updatedEicrBundle = anonymizerService.addReportabilityResponseInformationSection(eicrBundle, rrBundle, metadata);

        assertNotNull(updatedEicrBundle);
        FileUtils.saveDataToFile(jsonParser.setPrettyPrint(true).encodeResourceToString(updatedEicrBundle), "updatedFhir.json");
    }

    @Test
    public void testAddRRInformationSection_NoJurisdictionsToRetain() {
        rrBundle = jsonParser.parseResource(Bundle.class, TestUtils.getFileContentAsString(RR_WITH_MULTIPLE_ORG_INVALID_JUD_FILE_LOC));
        Bundle updatedEicrBundle = anonymizerService.addReportabilityResponseInformationSection(eicrBundle, rrBundle, metadata);

        assertNull(updatedEicrBundle);
    }

    @Test
    public void testAddRRInformationSection_MultipleRouteEntityOrg() {
        rrBundle = jsonParser.parseResource(Bundle.class, TestUtils.getFileContentAsString(RR_WITH_MULTIPLE_ORG_FILE_LOC));
        Bundle updatedEicrBundle = anonymizerService.addReportabilityResponseInformationSection(eicrBundle, rrBundle, metadata);

        assertNotNull(updatedEicrBundle);
        FileUtils.saveDataToFile(jsonParser.setPrettyPrint(true).encodeResourceToString(updatedEicrBundle), "updatedFhir1.json");
    }
}
