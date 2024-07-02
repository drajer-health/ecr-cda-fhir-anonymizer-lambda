package com.drajer.ecr.anonymizer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.Period;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
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
import org.springframework.util.ResourceUtils;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.event.S3EventNotification;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.util.StringUtils;
import com.drajer.ecr.anonymizer.service.AnonymizerService;
import com.fasterxml.jackson.databind.ObjectMapper;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import net.sf.saxon.Transform;

public class AnonymizerLambdaFunctionHandler implements RequestHandler<SQSEvent, String> {
	private AmazonS3 s3Client = AmazonS3ClientBuilder.standard().build();
	private final String LOINC_URL = "http://loinc.org";
	private String destPath = System.getProperty("java.io.tmpdir");
	public static final int DEFAULT_BUFFER_SIZE = 8192;

	@Override
	public String handleRequest(SQSEvent event, Context context) {
		// call ccda -- to fhir object
		// convert fhir object to anonymizer
		// write anonymizer to bucket
		String key = null;
		String bucket = null;
		try {
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
			}else {
				metaDataFileName = key.replace("rr", "metadata");
			}
			context.getLogger().log("metaDataFileName : " + metaDataFileName);
			//get metadata json
			InputStream metadataInputStream = getObject(bucket,metaDataFileName);
			// get metdata map
			Map<String, Object> metaDataMap = streamToMap(metadataInputStream);
			// process RR
			Bundle rrBundle = processEvent(bucket, key,context,metaDataMap,false);
			if (key.contains("RR")) {
				key = key.replace("RR", "EICR");
			}else {
				key = key.replace("rr", "eicr");
			}
			context.getLogger().log("EICR Key:" + key);
			context.getLogger().log("metaDataMap : " + metaDataMap.toString());
			//process EICR
			Bundle eicrBundle = processEvent(bucket, key,context,metaDataMap,true);
			AnonymizerService anonymizerService = new AnonymizerService();
			Bundle eicrRRBundle = anonymizerService.addReportabilityResponseInformationSection(eicrBundle, rrBundle, metaDataMap);

			String uniqueFilename = "OUTPUT-"+ key;
			IParser parser = FhirContext.forR4().newXmlParser();

			String processedDataBundleXml = parser.setPrettyPrint(true).encodeResourceToString(eicrRRBundle);

			context.getLogger().log("Anonymizer file name : "+uniqueFilename);
			
			if (StringUtils.isNullOrEmpty(processedDataBundleXml)) {
				context.getLogger().log("Output not generated check logs ");
			} else {
				context.getLogger().log("Writing output file "+uniqueFilename);
				this.writeFile(processedDataBundleXml, bucket, uniqueFilename, context);
				context.getLogger().log("Output Generated  " + bucket + "/" + uniqueFilename);
			}			
			return "SUCCESS";
		} catch (Exception e) {
			context.getLogger().log(e.getMessage());
			e.printStackTrace();
			throw new RuntimeException ("Lambda failiure : Key : "+key+" , bucket : "+bucket+" , Error : ",e);
		} finally {
		}
	}
	
	private Bundle processEvent(String bucket, String key, Context context,Map<String, Object> metaDataMap,boolean addAgeObservationEntry) throws Exception{
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
				throw new RuntimeException ("Error: Different Bucket ");
			}

			S3Object s3Object = s3Client.getObject(new GetObjectRequest(bucket, key));
			input = s3Object.getObjectContent();
			outputFile = new File("/tmp/" + keyFileName);
			context.getLogger().log("---- s3Object-Content....:" + s3Object.getObjectMetadata().getContentType());
			s3Object.close();
			outputFile.setWritable(true);

			context.getLogger().log("Output File---- " + key +"  : , bucket : "+bucket+" "+ outputFile.getAbsolutePath());
			context.getLogger().log("Output File -- CanWrite?:  "+ key +"  : , bucket : "+bucket+" "+outputFile.canWrite());
			context.getLogger().log("Output File -- Length: " + key +"  : , bucket : "+bucket+" "+outputFile.length());

			try (FileOutputStream outputStream = new FileOutputStream(outputFile, false)) {
				int read;
				byte[] bytes = new byte[DEFAULT_BUFFER_SIZE];
				while ((read = input.read(bytes)) != -1) {
					outputStream.write(bytes, 0, read);
				}
				outputStream.close();
			}

			context.getLogger().log("Output File -- Length:" + outputFile.length());

			UUID randomUUID = UUID.randomUUID();
			File xsltFile = ResourceUtils.getFile("classpath:hl7-xml-transforms/transforms/cda2fhir-r4/NativeUUIDGen-cda2fhir.xslt");

			context.getLogger().log("--- Before Transformation XSLT---::" + xsltFile.getAbsolutePath());
			context.getLogger().log("--- Before Transformation OUTPUT---::" + outputFile.getAbsolutePath());
			context.getLogger().log("--- Before Transformation UUID---::" + randomUUID);

			xsltTransformation(xsltFile.getAbsolutePath(), outputFile.getAbsolutePath(), randomUUID, context);

			String responseXML = getFileContentAsString(randomUUID, context);
			
			String fileName = key;
			context.getLogger().log("Before file name : "+fileName);
			context.getLogger().log("key toLowerCase contains rr: "  +key.toLowerCase().contains("rr"));
			
			if (key.toLowerCase().contains("rr")) {
				fileName = key.replace("RR","FHIR_RR");
			}else {
				fileName = key.replace("EICR","FHIR_EICR");
			}

			context.getLogger().log("FHIR file name : "+fileName);
			//writing fhir xml
			if (StringUtils.isNullOrEmpty(responseXML)) {
				context.getLogger().log("Output not generated check logs ");
			} else {
				context.getLogger().log("Writing FHIR output file "+fileName);
				this.writeFile(responseXML, bucket, fileName, context);
				context.getLogger().log("FHIR Output Generated  "+bucket+"/"+fileName);
			}
			
			if (addAgeObservationEntry) {
				responseXML = addAgeObservationBundleEntry(responseXML);
			}
			
			AnonymizerService anonymizerService = new AnonymizerService();
			return anonymizerService.processBundleXml(responseXML,metaDataMap);

		} catch (Exception e) {
			context.getLogger().log(e.getMessage());
			e.printStackTrace();
			throw new RuntimeException ("Error:  ",e);
		} finally {
			try {
				input.close();
			} catch (Exception e) {
			}
		}
	}

	public String addAgeObservationBundleEntry(String bundleXml) {
		IParser xmlParser = FhirContext.forR4().newXmlParser();

		try {
			Bundle bundle = xmlParser.parseResource(Bundle.class, bundleXml);

			BundleEntryComponent patientEntry = getBundleEntryByType(bundle, ResourceType.PATIENT.toCode());
			Patient patient = patientEntry != null ? (Patient) patientEntry.getResource() : null;

			if (patient != null && patient.hasBirthDateElement()) {
				BundleEntryComponent observationEntry = new BundleEntryComponent();
				observationEntry.setFullUrl("urn:uuid:" + getGuid());
				Observation ageObservation = createAgeObservationResource(patient, patientEntry.getFullUrl());
				observationEntry.setResource(ageObservation);
				bundle.addEntry(observationEntry);
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

			context.getLogger().log("bucketName ::::"+bucketName);
			context.getLogger().log("meta ::::"+keyPrefix + meta.toString());
			
			// Uploading to S3 destination bucket
			s3Client.putObject(bucketName, keyPrefix , is, meta);
			is.close();
		} catch (Exception e) {
			context.getLogger().log("ERROR:" + e.getMessage());
			e.printStackTrace();
		}
	}
	
	private InputStream getObject(String bucket, String key) throws IOException {
		S3Object s3Object = s3Client.getObject(new GetObjectRequest(bucket, key));
		InputStream inputStream = s3Object.getObjectContent();
		s3Object.close();
		return inputStream;
	}	
	
	private Map<String, Object> streamToMap(InputStream inputStream) throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
		return objectMapper.readValue(inputStream, Map.class);
	}
	
}