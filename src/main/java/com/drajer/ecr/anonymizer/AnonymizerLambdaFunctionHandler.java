package com.drajer.ecr.anonymizer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Composition.SectionComponent;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Enumerations.ResourceType;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.hl7.fhir.validation.ValidationEngine;
import org.springframework.util.ResourceUtils;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.event.S3EventNotification;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.util.StringUtils;
import com.drajer.ecr.anonymizer.service.AnonymizerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saxonica.config.ProfessionalConfiguration;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import net.sf.saxon.Transform;
import net.sf.saxon.lib.FeatureKeys;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XsltTransformer;

public class AnonymizerLambdaFunctionHandler implements RequestHandler<SQSEvent, String> {
	private AmazonS3 s3Client = AmazonS3ClientBuilder.standard().build();
	private final String LOINC_URL = "http://loinc.org";
	private String destPath = System.getProperty("java.io.tmpdir");
	public static final int DEFAULT_BUFFER_SIZE = 8192;

	private static AnonymizerLambdaFunctionHandler instance;
	private XsltTransformer transformer;
	private Processor processor;
	private static ValidationServcieImpl validationServcieImpl;
	private static ValidationEngine validationEngine;
	private static EmbeddedHapiFhirConfig embeddedHapiFhirConfig;
	
	public static AnonymizerLambdaFunctionHandler getInstance() throws IOException {
		if (instance == null) {

			synchronized (AnonymizerLambdaFunctionHandler.class) {
				if (instance == null) {
					instance = new AnonymizerLambdaFunctionHandler();
				}
			}
		}
		return instance;
	}

	public AnonymizerLambdaFunctionHandler() throws IOException {
		
		validationServcieImpl = new ValidationServcieImpl();
		embeddedHapiFhirConfig = new EmbeddedHapiFhirConfig();
		String bucketName = System.getenv("BUCKET_NAME");
		if (bucketName == null || bucketName.isEmpty()) {
			throw new IllegalArgumentException("S3 bucket name is not set in the environment variables.");
		}

		// Load the Saxon processor and transformer
		this.processor = createSaxonProcessor(bucketName);
		this.transformer = initializeTransformer();
		this.validationEngine = embeddedHapiFhirConfig.createValidationEngine();
		
	}

	private Processor createSaxonProcessor(String bucketName) throws IOException {
		String licenseFilePath = "/tmp/saxon-license.lic"; // Ensure temp path is used
		ProfessionalConfiguration configuration = new ProfessionalConfiguration();
		String key = "license/saxon-license.lic";

		// Attempt to retrieve the license file from S3
		S3Object licenseObj;
		try {
			licenseObj = s3Client.getObject(bucketName, key);
		} catch (AmazonS3Exception e) {
			throw new IOException("Failed to retrieve the license file from S3 bucket: " + bucketName, e);
		}

		// Read the license file
		try (S3ObjectInputStream s3InputStream = licenseObj.getObjectContent();
				FileOutputStream fos = new FileOutputStream(new File(licenseFilePath))) {

			byte[] readBuf = new byte[DEFAULT_BUFFER_SIZE];
			int readLen;
			while ((readLen = s3InputStream.read(readBuf)) > 0) {
				fos.write(readBuf, 0, readLen);
			}
		}

		// Check if the license file was saved correctly
		File licenseFile = ResourceUtils.getFile(licenseFilePath);
		if (!licenseFile.exists() || licenseFile.length() == 0) {
			throw new IOException("License file not found or is empty at: " + licenseFilePath);
		}

		String saxonLicenseAbsolutePath = licenseFile.getAbsolutePath();
		System.setProperty("http://saxon.sf.net/feature/licenseFileLocation", saxonLicenseAbsolutePath);
		configuration.setConfigurationProperty(FeatureKeys.LICENSE_FILE_LOCATION, saxonLicenseAbsolutePath);

		return new Processor(configuration);
	}

	private XsltTransformer initializeTransformer() {
		try {
			File xsltFile = ResourceUtils
					.getFile("classpath:hl7-xml-transforms/transforms/cda2fhir-r4/NativeUUIDGen-cda2fhir.xslt");
			processor.setConfigurationProperty(FeatureKeys.ALLOW_MULTITHREADING, true);
			XsltCompiler compiler = processor.newXsltCompiler();

//			compiler.setJustInTimeCompilation(true);
			XsltExecutable executable = compiler.compile(new StreamSource(xsltFile));
			return executable.load();
		} catch (SaxonApiException | IOException e) {
			throw new RuntimeException("Failed to initialize XSLT Transformer", e);
		}
	}

	public void transform(File sourceXml, UUID outputFileName, Context context) {
		try {
			Source source = new StreamSource(sourceXml);
			Path outputPath = Paths.get("/tmp", outputFileName.toString() + ".xml");
			Files.createDirectories(outputPath.getParent());

			Serializer out = processor.newSerializer(outputPath.toFile());
			out.setOutputProperty(Serializer.Property.METHOD, "xml");

			transformer.setSource(source);
			transformer.setDestination(out);
			transformer.transform();

			context.getLogger().log("Transformation complete. Output saved to: " + outputPath);
		} catch (SaxonApiException e) {
			context.getLogger().log("ERROR: Transformation failed with exception: " + e.getMessage());
		} catch (IOException e) {
			context.getLogger().log("ERROR: Failed to create output directory or file: " + e.getMessage());
		} catch (Exception e) {
			context.getLogger().log("ERROR: Unexpected error occurred: " + e.getMessage());
		}
	}

	@Override
	public String handleRequest(SQSEvent event, Context context) {

		// call ccda -- to fhir object
		// convert fhir object to anonymizer
		// write anonymizer to bucket
		String key = null;
		String bucket = null;
		try {
			AnonymizerLambdaFunctionHandler handler = AnonymizerLambdaFunctionHandler.getInstance();
			SQSMessage message = event.getRecords().get(0);
			String messageBody = message.getBody();
			S3EventNotification s3EventNotification = S3EventNotification.parseJson(messageBody);
			S3EventNotification.S3EventNotificationRecord record = s3EventNotification.getRecords().get(0);

			bucket = record.getS3().getBucket().getName();
			key = record.getS3().getObject().getKey();

			context.getLogger().log("EventName:" + record.getEventName());
			context.getLogger().log("BucketName:" + bucket);
			context.getLogger().log("RR Key:" + key);
			String metaDataFileName = "";
			if (key.contains("RR")) {
				metaDataFileName = key.replace("RR", "METADATA");
			} else {
				metaDataFileName = key.replace("rr", "metadata");
			}
			context.getLogger().log("metaDataFileName : " + metaDataFileName);
			// get metadata json

			S3Object s3Object = s3Client.getObject(new GetObjectRequest(bucket, metaDataFileName));
			InputStream metadataInputStream = s3Object.getObjectContent();

			// get metdata map
			Map<String, Object> metaDataMap = streamToMap(metadataInputStream);
			s3Object.close();
			// process RR
			Bundle rrBundle = processEvent(bucket, key, context, metaDataMap, false);
			if (key.contains("RR")) {
				key = key.replace("RR", "EICR");
			} else {
				key = key.replace("rr", "eicr");
			}
			context.getLogger().log("EICR Key:" + key);
			context.getLogger().log("metaDataMap : " + metaDataMap.toString());
			// process EICR
			Bundle eicrBundle = processEvent(bucket, key, context, metaDataMap, true);
			AnonymizerService anonymizerService = new AnonymizerService();
			Bundle eicrRRBundle = anonymizerService.addReportabilityResponseInformationSection(eicrBundle, rrBundle,
					metaDataMap);

			String uniqueFilename = "OUTPUT-" + key;
			IParser parser = FhirContext.forR4().newXmlParser();

			String processedDataBundleXml = parser.setPrettyPrint(true).encodeResourceToString(eicrRRBundle);

			context.getLogger().log("Anonymizer file name : " + uniqueFilename);

			if (StringUtils.isNullOrEmpty(processedDataBundleXml)) {
				context.getLogger().log("Output not generated check logs ");
			} else {
				context.getLogger().log("Writing output file " + uniqueFilename);
				this.writeFile(processedDataBundleXml, bucket, uniqueFilename, context);
				context.getLogger().log("Output Generated  " + bucket + "/" + uniqueFilename);
			}
			
			context.getLogger().log("Before validation time " + new Date());
			List<ValidationMessage> validateBundle = validationServcieImpl.validateBundle(eicrRRBundle, validationEngine);
			context.getLogger().log("After validation time " + new Date());
			context.getLogger().log("Validation Done Succesfully");
			return "SUCCESS";
		} catch (Exception e) {
			context.getLogger().log(e.getMessage());
			e.printStackTrace();
			throw new RuntimeException("Lambda failure : Key : " + key + " , bucket : " + bucket + " , Error : ", e);
		} finally {
		}
	}

	private Bundle processEvent(String bucket, String key, Context context, Map<String, Object> metaDataMap,
			boolean addAgeObservationEntry) throws Exception {
		instance = AnonymizerLambdaFunctionHandler.getInstance();
		InputStream input = null;
		File outputFile = null;
		String keyFileName = "";
		try {
			if (key != null && key.indexOf(File.separator) != -1) {
				keyFileName = key.substring(key.lastIndexOf(File.separator));
			} else {
				keyFileName = key;
			}

			context.getLogger().log("JVM - Temp Folder Path:::" + destPath);

			if (!this.isConverterBucket(bucket)) {
				context.getLogger().log(
						"BUCKET_NAME env null; Env BUCKET_NAME should match the bucket name created for converter ");
				throw new RuntimeException("Error: Different Bucket ");
			}

			S3Object s3Object = s3Client.getObject(new GetObjectRequest(bucket, key));
			input = s3Object.getObjectContent();
			outputFile = new File("/tmp/" + keyFileName);
			context.getLogger().log("---- s3Object-Content....:" + s3Object.getObjectMetadata().getContentType());

			outputFile.setWritable(true);

			context.getLogger()
					.log("Output File---- " + key + "  : , bucket : " + bucket + " " + outputFile.getAbsolutePath());
			context.getLogger().log(
					"Output File -- CanWrite?:  " + key + "  : , bucket : " + bucket + " " + outputFile.canWrite());
			context.getLogger()
					.log("Output File -- Length: " + key + "  : , bucket : " + bucket + " " + outputFile.length());

			try (FileOutputStream outputStream = new FileOutputStream(outputFile, false)) {
				int read;
				byte[] bytes = new byte[DEFAULT_BUFFER_SIZE];
				while ((read = input.read(bytes)) != -1) {
					outputStream.write(bytes, 0, read);
				}
				outputStream.close();
			}
			s3Object.close();
			context.getLogger().log("Output File -- Length:" + outputFile.length());

			UUID randomUUID = UUID.randomUUID();
//			File xsltFile = ResourceUtils
//					.getFile("classpath:hl7-xml-transforms/transforms/cda2fhir-r4/NativeUUIDGen-cda2fhir.xslt");

			// context.getLogger().log("--- Before Transformation XSLT---::" +
			// xsltFile.getAbsolutePath());
			context.getLogger().log("--- Before Transformation OUTPUT---::" + outputFile.getAbsolutePath());
			context.getLogger().log("--- Before Transformation UUID---::" + randomUUID);

			instance.transform(outputFile, randomUUID, context);

			String responseXML = getFileContentAsString(randomUUID, context);

			String fileName = key;
			context.getLogger().log("Before file name : " + fileName);
			context.getLogger().log("key toLowerCase contains rr: " + key.toLowerCase().contains("rr"));

			if (key.toLowerCase().contains("rr")) {
				fileName = key.replace("RR", "FHIR_RR");
			} else {
				fileName = key.replace("EICR", "FHIR_EICR");
			}

			context.getLogger().log("FHIR file name : " + fileName);
			// writing fhir xml
			if (StringUtils.isNullOrEmpty(responseXML)) {
				context.getLogger().log("Output not generated check logs ");
			} else {
				context.getLogger().log("Writing FHIR output file " + fileName);
				this.writeFile(responseXML, bucket, fileName, context);
				context.getLogger().log("FHIR Output Generated  " + bucket + "/" + fileName);
			}

			if (addAgeObservationEntry) {
				responseXML = addAgeObservationBundleEntry(responseXML);
			}

			AnonymizerService anonymizerService = new AnonymizerService();
			return anonymizerService.processBundleXml(responseXML, metaDataMap);

		} catch (Exception e) {
			context.getLogger().log(e.getMessage());
			e.printStackTrace();
			throw new RuntimeException("Error:  ", e);
		} finally {
			try {
				input.close();
			} catch (Exception e) {
			}
		}
	}

	public String addAgeObservationBundleEntry(String bundleXml) {
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
			throw new RuntimeException(errorMessage, e);
		} catch (Exception e) {
			String errorMessage = "An unexpected error occurred while processing the XML bundle: " + e.getMessage();
			throw new RuntimeException(errorMessage, e);
		}
	}

	public BundleEntryComponent getBundleEntryByType(Bundle bundle, String resourceType) {

		// Iterate through the entries in the bundle
		for (BundleEntryComponent entry : bundle.getEntry()) {
			Resource resource = entry.getResource();
			if (resource.getResourceType().name().equals(resourceType)) {

				return entry;
			}
		}

		return null;
	}

	public Observation createAgeObservationResource(Patient patient, String patientUrl) {
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

	private Identifier generateIdentifier() {
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

	/**
	 * Check if the S3 file processing is from the same S3 Converter bucket
	 * 
	 * @param theBucketName
	 * @return
	 */
	private boolean isConverterBucket(String theBucketName) {

		String envBucketName = System.getenv("BUCKET_NAME");
		if (envBucketName != null && theBucketName != null && theBucketName.equalsIgnoreCase(envBucketName)) {
			return true;
		}
		return false;
	}

	/**
	 * Below method is used to call the Saxon transform method (i.e main method)
	 * 
	 * @param xslFilePath
	 * @param sourceXml
	 * @param outputFileName
	 */
	private void xsltTransformation(String xslFilePath, String sourceXml, UUID outputFileName, Context context) {

		try {

			String[] commandLineArguments = new String[3];

			commandLineArguments[0] = "-xsl:" + xslFilePath;
			commandLineArguments[1] = "-s:" + sourceXml;
			// commandLineArguments[2] = "-license:on";
			commandLineArguments[2] = "-o:" + "/tmp/" + outputFileName + ".xml";

			Transform.main(commandLineArguments);

			context.getLogger().log("Transformation Complete");

		} catch (Exception e) {
			e.printStackTrace();
			context.getLogger().log("ERROR: Transformation Failed with exception " + e.getMessage());
		}
	}

	private String getFileContentAsString(UUID fileName, Context context) {
		File outputFile = null;
		try {
			outputFile = ResourceUtils.getFile("/tmp/" + fileName + ".xml");
			String absolutePath = outputFile.getAbsolutePath();
			byte[] readAllBytes = Files.readAllBytes(Paths.get(absolutePath));
			Charset encoding = Charset.defaultCharset();
			String string = new String(readAllBytes, encoding);
			return string;
		} catch (FileNotFoundException e) {
			context.getLogger().log("ERROR: output file not found " + e.getMessage());
		} catch (IOException e) {
			context.getLogger().log("ERROR: IO Exception while reading output file " + e.getMessage());
		} catch (Exception ee) {
			context.getLogger().log("ERROR: Exception for output " + ee.getMessage());
		}
		return null;
	}

	private void writeFile(String fileContent, String bucketName, String keyPrefix, Context context) {
		try {
			byte[] contentAsBytes = fileContent.getBytes("UTF-8");
			ByteArrayInputStream is = new ByteArrayInputStream(contentAsBytes);
			ObjectMetadata meta = new ObjectMetadata();
			meta.setContentLength(contentAsBytes.length);
			meta.setContentType("text/xml");

			context.getLogger().log("bucketName ::::" + bucketName);
			context.getLogger().log("meta ::::" + keyPrefix + meta.toString());

			// Uploading to S3 destination bucket
			s3Client.putObject(bucketName, keyPrefix, is, meta);
			is.close();
		} catch (Exception e) {
			context.getLogger().log("ERROR:" + e.getMessage());
			e.printStackTrace();
		}
	}

	private Map<String, Object> streamToMap(InputStream inputStream) throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
		return objectMapper.readValue(inputStream, Map.class);
	}

}