package com.drajer.ecr.anonymizer.utils;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import com.amazonaws.services.lambda.runtime.Context;

import java.io.File;
import java.io.IOException;

public class HttpUtils {

    private final String apiUrl;

    public HttpUtils(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String makePostRequest(File file, Context context) throws IOException {
        context.getLogger().log("HTTP makePostRequest");

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(apiUrl);

            // Create MultipartEntityBuilder
            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addBinaryBody("file", file, ContentType.DEFAULT_BINARY, file.getName());

            // Build the entity and set it to the post request
            httpPost.setEntity(builder.build());

            // Execute the request and handle the response
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                HttpEntity responseEntity = response.getEntity();
                if (responseEntity != null) {
                    String responseBody = EntityUtils.toString(responseEntity);
              
                    return responseBody;
                } else {
                    context.getLogger().log("No response entity found.");
                    return null;
                }
            }
        } catch (IOException e) {
            context.getLogger().log("Error executing HTTP request: " + e.getMessage());
            throw e;
        }
    }
}