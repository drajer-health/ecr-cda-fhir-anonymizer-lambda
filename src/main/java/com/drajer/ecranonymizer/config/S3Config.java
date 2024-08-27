package com.drajer.ecranonymizer.config;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

@Configuration
public class S3Config {

    @Value("${bucket.name:}")
    private String bucketName;

    @Bean
    @Conditional(S3BucketCondition.class)
    public AmazonS3 amazonS3Client() {
        // Configure and return an AmazonS3 client
        return AmazonS3ClientBuilder.standard().withRegion(Regions.US_EAST_1).build();
    }

    @Bean
    @Conditional(S3BucketCondition.class)
    public S3StorageService s3StorageService(AmazonS3 amazonS3Client) {
        return new S3StorageService(amazonS3Client, bucketName);
    }
}
