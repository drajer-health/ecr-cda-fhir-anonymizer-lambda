package com.drajer.ecranonymizer.config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;

public class S3StorageService {
	
	 private final Logger logger = LoggerFactory.getLogger(S3StorageService.class);

	private final AmazonS3 amazonS3Client;
	private final String bucketName;

	public S3StorageService(AmazonS3 amazonS3Client, String bucketName) {
		this.amazonS3Client = amazonS3Client;
		this.bucketName = bucketName;
	}

	
	
	public String uploadFile(String keyName, File file) throws IOException {
	    logger.info("request to upload file ");
	    try {

	
	      ObjectMetadata metadata = new ObjectMetadata();
	      InputStream fileInputStream = Files.newInputStream(file.toPath());
	      metadata.setContentLength(file.length());

	      amazonS3Client.putObject(bucketName, keyName, fileInputStream, metadata);
	      return "File uploaded: " + keyName;
	    } catch (AmazonServiceException serviceException) {
	      logger.info("AmazonServiceException: " + serviceException.getMessage());
	      throw serviceException;
	    } catch (AmazonClientException clientException) {
	      logger.info("AmazonClientException Message: " + clientException.getMessage());
	      throw clientException;
	    }
	  }
	

	  /**
	   * method will be used to get file from s3
	   *
	   * @param fileName
	   * @return S3Object
	   */

	  public S3Object getS3File(String fileName) {
	   
	    S3Object s3Object = amazonS3Client.getObject(new GetObjectRequest(bucketName, fileName));

	    logger.info("fetched s3 file {}", s3Object != null ? s3Object.getKey() : null);
	    return s3Object;
	  }
}
