package com.adcb.cert;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
 
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
 
public class S3FileLambda implements RequestHandler<Object, String> {
 
    private static final String BUCKET = "adcb-jks-certificate";
    private static final String KEY = "config/message.txt";
 
    private static final S3Client s3Client = S3Client.create();
 
    // Lambda container memory cache
    private static volatile String cachedContent;
    private static volatile String cachedETag;
 
    @Override
    public String handleRequest(Object input, Context context) {
 
        try {
 
            // 1. Check latest S3 metadata
            HeadObjectRequest headRequest =
                    HeadObjectRequest.builder()
                            .bucket(BUCKET)
                            .key(KEY)
                            .build();
 
            HeadObjectResponse headResponse =
                    s3Client.headObject(headRequest);
 
            String currentETag = headResponse.eTag();
 
            // 2. Cache exists and file has NOT changed
            if (cachedContent != null &&
                currentETag.equals(cachedETag)) {
 
                context.getLogger().log("CACHE HIT");
 
                System.out.println(cachedContent);
 
                return cachedContent;
            }
 
            // 3. First request OR S3 file changed
            context.getLogger().log("S3 FETCH - File changed");
 
            GetObjectRequest getRequest =
                    GetObjectRequest.builder()
                            .bucket(BUCKET)
                            .key(KEY)
                            .build();
 
            String newContent =
                    s3Client.getObjectAsBytes(getRequest)
                            .asUtf8String();
 
            // 4. Update cache
            cachedContent = newContent;
            cachedETag = currentETag;
 
            System.out.println(newContent);
 
            return newContent;
 
        } catch (Exception e) {
 
            context.getLogger().log(
                    "Error: " + e.getMessage()
            );
 
            throw new RuntimeException(e);
        }
    }
}


<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.servion.uatdev.mycert</groupId>
  <artifactId>ADCBCertificate</artifactId>
  <version>1.0.1</version>
  
  <dependencies>
  	<dependency>
			<groupId>software.amazon.awssdk</groupId>
			<artifactId>s3</artifactId>
			<version>2.16.92</version> <!-- Replace with the appropriate version -->
		</dependency>
		
		<dependency>
			<groupId>com.amazonaws</groupId>
			<artifactId>aws-lambda-java-core</artifactId>
			<version>1.2.0</version>
		</dependency>
		
		<dependency>
			<groupId>com.amazonaws</groupId>
			<artifactId>aws-java-sdk-s3</artifactId>
			<version>1.12.92</version> <!-- Replace with the appropriate version -->
		</dependency>
  </dependencies>
  
</project>

