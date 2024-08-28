package com.drajer.ecranonymizer.config;

import com.drajer.ecranonymizer.config.S3BucketCondition;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;


@Configuration
public class S3Config {

    @Value("${bucket.name:}")
    private String bucketName;


    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.US_EAST_1)  // Specify your desired AWS region
                .credentialsProvider(DefaultCredentialsProvider.create())  // Uses default credentials provider
                .build();
    }

    @Bean
    @Conditional(S3BucketCondition.class)
    public S3StorageService s3StorageService(S3Client amazonS3Client) {
        return new S3StorageService(amazonS3Client, bucketName);
    }
}
