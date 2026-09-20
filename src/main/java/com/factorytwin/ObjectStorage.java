package com.factorytwin;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Component
public class ObjectStorage implements AutoCloseable {
  public final S3Client client;
  public final S3Presigner signer;
  public final String bucket;

  public ObjectStorage(
      @Value("${twin.storage.endpoint}") String endpoint,
      @Value("${twin.storage.public-endpoint}") String publicEndpoint,
      @Value("${twin.storage.region}") String region,
      @Value("${twin.storage.bucket}") String bucket,
      @Value("${twin.storage.access-key}") String access,
      @Value("${twin.storage.secret-key}") String secret) {
    this.bucket = bucket;
    var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret));
    var config =
        S3Configuration.builder()
            .pathStyleAccessEnabled(true)
            .chunkedEncodingEnabled(false)
            .build();
    client =
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .serviceConfiguration(config)
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(3))
                    .socketTimeout(Duration.ofSeconds(30)))
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .apiCallTimeout(Duration.ofSeconds(60))
                    .build())
            .build();
    signer =
        S3Presigner.builder()
            .endpointOverride(URI.create(publicEndpoint))
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .serviceConfiguration(config)
            .build();
  }

  public String download(String key) {
    return signer
        .presignGetObject(
            b ->
                b.signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(r -> r.bucket(bucket).key(key)))
        .url()
        .toString();
  }

  public void health() {
    client.headBucket(b -> b.bucket(bucket));
  }

  public void cors(List<String> origins) {
    client.putBucketCors(
        b ->
            b.bucket(bucket)
                .corsConfiguration(
                    c ->
                        c.corsRules(
                            r ->
                                r.allowedOrigins(origins)
                                    .allowedMethods("GET", "HEAD", "PUT")
                                    .allowedHeaders("*")
                                    .exposeHeaders("ETag", "Content-Length", "Content-Range")
                                    .maxAgeSeconds(300))));
  }

  @Override
  public void close() {
    client.close();
    signer.close();
  }
}
