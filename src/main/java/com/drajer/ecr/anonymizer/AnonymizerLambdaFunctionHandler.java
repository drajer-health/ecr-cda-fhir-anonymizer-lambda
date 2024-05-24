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
import java.util.UUID;

import org.springframework.util.ResourceUtils;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.event.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.util.StringUtils;
import com.drajer.ecr.anonymizer.service.AnonymizerService;

import net.sf.saxon.Transform;

public class AnonymizerLambdaFunctionHandler implements RequestHandler<S3Event, String> {
	private AmazonS3 s3Client = AmazonS3ClientBuilder.standard().build();
	private String destPath = System.getProperty("java.io.tmpdir");
	public static final int DEFAULT_BUFFER_SIZE = 8192;

	@Override
	public String handleRequest(S3Event event, Context context) {
		// call ccda -- to fhir object
		// convert fhir object to anonymizer
		// write anonymizer to bucket
		InputStream input = null;
		File outputFile = null;
		String keyFileName = "";
		String keyPrefix = "";
		try {
			S3EventNotificationRecord record = event.getRecords().get(0);
			String key = record.getS3().getObject().getKey();
			String bucket = record.getS3().getBucket().getName();

			context.getLogger().log("EventName:" + record.getEventName());
			context.getLogger().log("BucketName:" + bucket);
			context.getLogger().log("Key:" + key);

			if (key != null && key.indexOf(File.separator) != -1) {
				keyFileName = key.substring(key.lastIndexOf(File.separator));
				keyPrefix = key.substring(0, key.lastIndexOf(File.separator) + 1);
			} else {
				keyFileName = key;
			}

			context.getLogger().log("JVM - Temp Folder Path:::" + destPath);

			if (!this.isConverterBucket(bucket)) {
				context.getLogger().log(
						"BUCKET_NAME env null; Env BUCKET_NAME should match the bucket name created for converter ");
				return "Error: Different Bucket";
			}

			S3Object s3Object = s3Client.getObject(new GetObjectRequest(bucket, key));
			input = s3Object.getObjectContent();
			outputFile = new File("/tmp/" + keyFileName);

			outputFile.setWritable(true);

			context.getLogger().log("Output File----" + outputFile.getAbsolutePath());
			context.getLogger().log("Output File -- CanWrite?:" + outputFile.canWrite());
			context.getLogger().log("Output File -- Length:" + outputFile.length());

			try (FileOutputStream outputStream = new FileOutputStream(outputFile, false)) {
				int read;
				byte[] bytes = new byte[DEFAULT_BUFFER_SIZE];
				while ((read = input.read(bytes)) != -1) {
					outputStream.write(bytes, 0, read);
				}
				outputStream.close();
			}

			context.getLogger().log("Output File -- Length:" + outputFile.length());
			context.getLogger().log("---- s3Object-Content....:" + s3Object.getObjectMetadata().getContentType());

			UUID randomUUID = UUID.randomUUID();
			File xsltFile = ResourceUtils.getFile("classpath:hl7-xml-transforms/transforms/cda2fhir-r4/cda2fhir.xslt");

			context.getLogger().log("--- Before Transformation XSLT---::" + xsltFile.getAbsolutePath());
			context.getLogger().log("--- Before Transformation OUTPUT---::" + outputFile.getAbsolutePath());
			context.getLogger().log("--- Before Transformation UUID---::" + randomUUID);

			xsltTransformation(xsltFile.getAbsolutePath(), outputFile.getAbsolutePath(), randomUUID, context);

			String responseXML = getFileContentAsString(randomUUID, context);

			AnonymizerService anonymizerService = new AnonymizerService();
			String processedDataBundleXml = anonymizerService.processBundleXml(responseXML);

			if (StringUtils.isNullOrEmpty(processedDataBundleXml)) {
				context.getLogger().log("Output not generated check logs ");
			} else {
				context.getLogger().log("Writing output file ");
				this.writeFile(processedDataBundleXml, keyPrefix, processedDataBundleXml, context);
				context.getLogger().log("Output Generated  " + bucket + "/" + keyPrefix);

			}
			return "SUCCESS";
		} catch (Exception e) {
			context.getLogger().log(e.getMessage());
			e.printStackTrace();
			return "ERROR:" + e.getMessage();
		} finally {
			try {
				input.close();
			} catch (Exception e) {
			}
		}
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

			// Uploading to S3 destination bucket
			s3Client.putObject(bucketName, keyPrefix + createAnonymizerFilename(keyPrefix), is, meta);
			is.close();
		} catch (Exception e) {
			context.getLogger().log("ERROR:" + e.getMessage());
			e.printStackTrace();
		}
	}

	private String createAnonymizerFilename(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString() + "_anonymizer.xml";
	}

}
