package com.drajer.ecr.anonymizer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Composition.SectionComponent;
import org.hl7.fhir.r4.model.Enumerations.ResourceType;
import org.springframework.util.ResourceUtils;

import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.util.StringUtils;
import com.drajer.ecr.anonymizer.service.AnonymizerService;
import com.fasterxml.jackson.databind.ObjectMapper;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import net.sf.saxon.Transform;

public class AnonymizerLocal {

	private String destPath = "D://ecr-anonymizer";
	public static final int DEFAULT_BUFFER_SIZE = 8192;
	private static final String LOINC_URL = "http://loinc.org";

	public static void main(String[] args) {

		String desktop = System.getProperty("user.home");
		String rrKey = "CCD_RR.xml";
		String metaDataFileName = "CCD_METADATA";
		String eicrDataFileName = "CCD_EICR.xml";
		System.out.println("metaDataFileName : " + metaDataFileName);

		// get metdata map
		try {
			// get metadata json
			InputStream metadataInputStream = new FileInputStream(metaDataFileName);

//		    ClassLoader classLoader = AnonymizerLocal.class.getClassLoader();
//		    File file = new File(classLoader.getResource("Metadata_CH_ORG_WI_05272024.json").getFile());
//		    String data = FileUtils.readFileToString(file, "UTF-8");

			Map<String, Object> metaDataMap = streamToMap(metadataInputStream);

			System.out.println("Start time RR ::" + new Date());

			Bundle rrBundle = processEvent(rrKey,metaDataMap,false);
			System.out.println("End Time RR ::" + new Date());

			System.out.println("Start time EICR ::" + new Date());
			Bundle eicrBundle = processEvent(eicrDataFileName, metaDataMap,true);
			System.out.println("End Time EICR ::" + new Date());
			System.out.println("metaDataMap toString : " + metaDataMap.toString());

			AnonymizerService anonymizerService = new AnonymizerService();
			Bundle eicrRRBundle = anonymizerService.addReportabilityResponseInformationSection(eicrBundle, rrBundle, metaDataMap);

			String uniqueFilename = "OUTPUT-"+ eicrDataFileName;
			IParser parser = FhirContext.forR4().newXmlParser();

			String processedDataBundleXml = parser.setPrettyPrint(true).encodeResourceToString(eicrRRBundle);

			System.out.println("Anonymizer file name : "+uniqueFilename);

			if (StringUtils.isNullOrEmpty(processedDataBundleXml)) {
				System.out.println("Output not generated check logs ");
			} else {
				System.out.println("Writing output file "+uniqueFilename);
				writeFileLocal(processedDataBundleXml, uniqueFilename);
				System.out.println("Output Generated  " + uniqueFilename);
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	private static Bundle processEvent(String key, Map<String, Object> metaDataMap,boolean addAgeObservationEntry) throws Exception{
		InputStream input = null;
		File outputFile = null;
		try {
			System.out.println("filePath ::::::" + key);
//			S3Object s3Object = s3Client.getObject(new GetObjectRequest(bucket, key));
			input = new FileInputStream(key); // s3Object.getObjectContent();
			outputFile = new File("C://tmp//" + key);

			outputFile.setWritable(true);

			System.out.println("Output File----" + outputFile.getAbsolutePath());
			System.out.println("Output File -- CanWrite?:" + outputFile.canWrite());
			System.out.println("Output File -- Length:" + outputFile.length());

			try (FileOutputStream outputStream = new FileOutputStream(outputFile, false)) {
				int read;
				byte[] bytes = new byte[DEFAULT_BUFFER_SIZE];
				while ((read = input.read(bytes)) != -1) {
					outputStream.write(bytes, 0, read);
				}
				outputStream.close();
			}

			System.out.println("Output File -- Length:" + outputFile.length());
//			System.out.println("---- s3Object-Content....:" + s3Object.getObjectMetadata().getContentType());

			UUID randomUUID = UUID.randomUUID();
//			File xsltFile = ResourceUtils.getFile("classpath:hl7-xml-transforms/transforms/cda2fhir-r4/cda2fhir.xslt");
			File xsltFile = ResourceUtils
					.getFile("classpath:hl7-xml-transforms/transforms/cda2fhir-r4/NativeUUIDGen-cda2fhir.xslt");

			System.out.println("--- Before Transformation XSLT---::" + xsltFile.getAbsolutePath());
			System.out.println("--- Before Transformation OUTPUT---::" + outputFile.getAbsolutePath());
			System.out.println("--- Before Transformation UUID---::" + randomUUID);

			xsltTransformation(xsltFile.getAbsolutePath(), outputFile.getAbsolutePath(), randomUUID);

			String responseXML = getFileContentAsStringLocal(randomUUID);

			String fileName = key;
			System.out.println("Before file name : " + fileName);
			System.out.println("key toLowerCase contains rr: " + key.toLowerCase().contains("rr"));

			if (key.toLowerCase().contains("rr")) {
				fileName = key.replace("RR", "FHIR_RR");
			} else {
				fileName = key.replace("EICR", "FHIR_EICR");
			}

			System.out.println("FHIR file name : " + fileName);
			// writing fhir xml
			if (StringUtils.isNullOrEmpty(responseXML)) {
				System.out.println("Output not generated check logs ");
			} else {
				System.out.println("Writing FHIR output file " + fileName);
				writeFileLocal(responseXML, fileName);
				System.out.println("FHIR Output Generated  " + fileName);
			}
			if (addAgeObservationEntry) {
				responseXML = addAgeObservationBundleEntry(responseXML);
			}
			AnonymizerService anonymizerService = new AnonymizerService();
			return anonymizerService.processBundleXml(responseXML, metaDataMap);
		} catch (Exception e) {
			System.out.println("Error ::::"+e.getMessage());
			e.printStackTrace();
			throw new RuntimeException ("Error:  ",e);
		} finally {
			try {
				input.close();
			} catch (Exception e) {}
	}
	}
	public static void xsltTransformation(String xslFilePath, String sourceXml, UUID outputFileName) {

		try {

			String[] commandLineArguments = new String[3];

			commandLineArguments[0] = "-xsl:" + xslFilePath;
			commandLineArguments[1] = "-s:" + sourceXml;
			// commandLineArguments[2] = "-license:on";
			commandLineArguments[2] = "-o:" + "/tmp/" + outputFileName + ".xml";

			Transform.main(commandLineArguments);

			System.out.println("Transformation Complete");

		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("ERROR: Transformation Failed with exception " + e.getMessage());
		}
	}

	public static String getFileContentAsStringLocal(UUID fileName) {
		File outputFile = null;
		try {
			outputFile = ResourceUtils.getFile("/tmp/" + fileName + ".xml");
			String absolutePath = outputFile.getAbsolutePath();
			byte[] readAllBytes = Files.readAllBytes(Paths.get(absolutePath));
			Charset encoding = Charset.defaultCharset();
			String string = new String(readAllBytes, encoding);
			return string;
		} catch (FileNotFoundException e) {
			System.out.println("ERROR: output file not found " + e.getMessage());
		} catch (IOException e) {
			System.out.println("ERROR: IO Exception while reading output file " + e.getMessage());
		} catch (Exception ee) {
			System.out.println("ERROR: Exception for output " + ee.getMessage());
		}
		return null;
	}

	public static Map<String, Object> streamToMap(InputStream inputStream) throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
		return objectMapper.readValue(inputStream, Map.class);
	}

	public static void writeFileLocal(String fileContent, String keyPrefix) {
		try {
			byte[] contentAsBytes = fileContent.getBytes("UTF-8");
			ByteArrayInputStream is = new ByteArrayInputStream(contentAsBytes);
			ObjectMetadata meta = new ObjectMetadata();
			meta.setContentLength(contentAsBytes.length);
			meta.setContentType("text/xml");

			System.out.println("meta ::::" + keyPrefix + meta.toString());

			// Uploading to S3 destination bucket
//			s3Client.putObject(bucketName, keyPrefix , is, meta);
			IOUtils.copy(is, new FileOutputStream("/tmp/" + keyPrefix));

			is.close();
		} catch (Exception e) {
			System.out.println("ERROR:" + e.getMessage());
			e.printStackTrace();
		}
	}
	public static String addAgeObservationBundleEntry(String bundleXml) {
		IParser xmlParser = FhirContext.forR4().newXmlParser();
		
		AnonymizerService anonymizerService = new AnonymizerService();

			try {
				Bundle bundle = xmlParser.parseResource(Bundle.class, bundleXml);

				Composition eicrComposition = (Composition) anonymizerService.getResourceByType(bundle, "Composition");
				SectionComponent socialHistorySection = null;
				if (eicrComposition != null) {
					List<SectionComponent> section = eicrComposition.getSection();

					socialHistorySection = anonymizerService.findSectionByCode(section, "29762-2", LOINC_URL);
				}

				BundleEntryComponent patientEntry = getBundleEntryByType(bundle, ResourceType.PATIENT.toCode());
				Patient patient = patientEntry != null ? (Patient) patientEntry.getResource() : null;

				if (patient != null && patient.hasBirthDateElement()) {
					BundleEntryComponent observationEntry = new BundleEntryComponent();
					observationEntry.setFullUrl("urn:uuid:" + getGuid());
					Observation ageObservation = createAgeObservationResource(patient, patientEntry.getFullUrl());
					anonymizerService.addProfile(ageObservation, "caculated-age");
					observationEntry.setResource(ageObservation);
					bundle.addEntry(observationEntry);

					if (socialHistorySection != null) {
						Reference reference = new Reference();

						reference.setReference(observationEntry.getFullUrl());
						reference.setDisplay(null);
						if (!socialHistorySection.hasEntry()) {
							socialHistorySection.setEntry(new ArrayList<>());
						}
						socialHistorySection.getEntry().add(reference);
					}
				}

				return xmlParser.setPrettyPrint(true).encodeResourceToString(bundle);

		} catch (DataFormatException e) {
			String errorMessage = "Failed to parse XML: " + e.getMessage();
			throw new RuntimeException( errorMessage, e);
		} catch (Exception e) {
			String errorMessage = "An unexpected error occurred while processing the XML bundle: " + e.getMessage();
			throw new RuntimeException( errorMessage, e);
		}
	}

	public static BundleEntryComponent getBundleEntryByType(Bundle bundle, String resourceType) {

		// Iterate through the entries in the bundle
		for (BundleEntryComponent entry : bundle.getEntry()) {
			Resource resource = entry.getResource();
			if (resource.getResourceType().name().equals(resourceType)) {

				return entry;
			}
		}

		return null;
	}

	public static Observation createAgeObservationResource(Patient patient, String patientUrl) {
		Observation observation = new Observation();
		observation.setIdentifier(Collections.singletonList(generateIdentifier()));
		observation.setStatus(Observation.ObservationStatus.FINAL);

		Coding coding = new Coding();
		coding.setSystem(LOINC_URL);
		coding.setCode("29553-5");
		coding.setDisplay("Age calculated");
		observation.getCode().addCoding(coding);

		observation.setEffective(new DateTimeType(new Date()));

		Reference subjectReference = new Reference(patientUrl);
		observation.setSubject(subjectReference);

		Quantity ageQuantity = new Quantity();
		ageQuantity.setCode("a");
		ageQuantity.setValue(calculateAge(patient.getBirthDateElement()));
		ageQuantity.setUnit("yr");
		ageQuantity.setSystem("http://unitsofmeasure.org");

		observation.setValue(ageQuantity);
		return observation;
	}

	private static Identifier generateIdentifier() {
		Identifier identifier = new Identifier();
		identifier.setSystem("urn:ietf:rfc:3986");
		identifier.setValue("urn:uuid:" + getGuid());
		return identifier;
	}

	public static String getGuid() {
		return java.util.UUID.randomUUID().toString();
	}

	private static int calculateAge(DateType birthDate) {
		LocalDate birthLocalDate = birthDate.getValueAsCalendar().toInstant().atZone(java.time.ZoneId.systemDefault())
				.toLocalDate();
		return Period.between(birthLocalDate, LocalDate.now()).getYears();
	}
}