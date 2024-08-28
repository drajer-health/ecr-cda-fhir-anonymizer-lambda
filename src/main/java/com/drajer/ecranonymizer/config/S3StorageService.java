package com.drajer.ecranonymizer.config;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;


public class S3StorageService {
	
	 private final Logger logger = LoggerFactory.getLogger(S3StorageService.class);

	private final S3Client amazonS3Client;
	private final String bucketName;

	public S3StorageService(S3Client amazonS3Client, String bucketName) {
		this.amazonS3Client = amazonS3Client;
		this.bucketName = bucketName;
	}

	
	public String uploadFile(String keyName, File file) throws IOException {
        logger.info("Request to upload file: {}", keyName);
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(keyName)
                    .build();

            // Upload the file using RequestBody from the File
            PutObjectResponse response = amazonS3Client.putObject(putObjectRequest, RequestBody.fromFile(file));
            return "File uploaded successfully: " + keyName;
        } catch (S3Exception e) {
            logger.error("S3Exception: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Method to get a file from S3
     *
     * @param fileName The name of the file to fetch from S3
     * @return A response input stream containing the file data from S3
     */
    public ResponseInputStream<GetObjectResponse> getS3File(String fileName) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileName)
                    .build();

            ResponseInputStream<GetObjectResponse> s3Object = amazonS3Client.getObject(getObjectRequest);
            return s3Object;
        } catch (S3Exception e) {
            logger.error("Failed to fetch file from S3: {}", e.awsErrorDetails().errorMessage());
            throw e;
        }
    }
}
