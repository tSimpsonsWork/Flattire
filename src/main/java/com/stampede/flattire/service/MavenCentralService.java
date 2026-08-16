package com.stampede.flattire.service;

import com.stampede.flattire.dto.MavenSearchResult;
import com.stampede.flattire.dto.MavenVersionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class MavenCentralService {

    private static final Duration SEARCH_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration VERSION_CACHE_TTL = Duration.ofHours(12);

    private final RestClient searchClient;
    private final RestClient repositoryClient;

    private final Map<String, CacheEntry<List<MavenSearchResult>>> searchCache =
            new ConcurrentHashMap<>();

    private final Map<String, CacheEntry<List<MavenVersionResult>>> versionCache =
            new ConcurrentHashMap<>();

    public MavenCentralService() {
        this.searchClient =
                RestClient.create("https://search.maven.org");

        this.repositoryClient =
                RestClient.create("https://repo.maven.apache.org");
    }

    public List<MavenSearchResult> search(String query) {

        long start = System.currentTimeMillis();

        if (query == null || query.trim().length() < 2) {
            return List.of();
        }

        String normalizedQuery = query.trim();
        String cacheKey = normalizedQuery.toLowerCase();

        CacheEntry<List<MavenSearchResult>> cached =
                searchCache.get(cacheKey);

        if (cached != null && !cached.isExpired()) {

            log.debug(
                    "Maven search | query={} | source=CACHE | results={} | durationMs={}",
                    cacheKey,
                    cached.value().size(),
                    System.currentTimeMillis() - start
            );

            return cached.value();
        }

        JsonNode response = searchClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/solrsearch/select")
                        .queryParam("q", normalizedQuery)
                        .queryParam("rows", 10)
                        .queryParam("wt", "json")
                        .build())
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {

            log.warn(
                    "Maven search failed | query={} | reason=null-response | durationMs={}",
                    cacheKey,
                    System.currentTimeMillis() - start
            );

            return List.of();
        }

        List<MavenSearchResult> results =
                new ArrayList<>();

        JsonNode docs =
                response.path("response").path("docs");

        for (JsonNode doc : docs) {

            results.add(
                    new MavenSearchResult(
                            doc.path("g").asText(),
                            doc.path("a").asText(),
                            doc.path("latestVersion").asText()
                    )
            );
        }

        searchCache.put(
                cacheKey,
                new CacheEntry<>(
                        results,
                        Instant.now().plus(SEARCH_CACHE_TTL)
                )
        );

        log.info(
                "Maven search | query={} | source=MAVEN_CENTRAL | results={} | durationMs={}",
                cacheKey,
                results.size(),
                System.currentTimeMillis() - start
        );

        return results;
    }

    public List<MavenVersionResult> getVersions(
            String groupId,
            String artifactId
    ) {

        long start = System.currentTimeMillis();

        String normalizedGroupId = groupId.trim();
        String normalizedArtifactId = artifactId.trim();

        String cacheKey =
                normalizedGroupId.toLowerCase()
                        + ":"
                        + normalizedArtifactId.toLowerCase();

        CacheEntry<List<MavenVersionResult>> cached =
                versionCache.get(cacheKey);

        if (cached != null && !cached.isExpired()) {

            log.debug(
                    "Maven versions | artifact={} | source=CACHE | versions={} | durationMs={}",
                    cacheKey,
                    cached.value().size(),
                    System.currentTimeMillis() - start
            );

            return cached.value();
        }

        String groupPath =
                normalizedGroupId.replace(".", "/");

        String metadataPath =
                "/maven2/"
                        + groupPath
                        + "/"
                        + normalizedArtifactId
                        + "/maven-metadata.xml";

        String xml = repositoryClient.get()
                .uri(metadataPath)
                .retrieve()
                .body(String.class);

        if (xml == null || xml.isBlank()) {

            log.warn(
                    "Maven metadata returned empty response | artifact={} | durationMs={}",
                    cacheKey,
                    System.currentTimeMillis() - start
            );

            return List.of();
        }

        try {

            DocumentBuilderFactory factory =
                    DocumentBuilderFactory.newInstance();

            Document document =
                    factory
                            .newDocumentBuilder()
                            .parse(
                                    new InputSource(
                                            new StringReader(xml)
                                    )
                            );

            NodeList versionNodes =
                    document.getElementsByTagName("version");

            List<MavenVersionResult> results =
                    new ArrayList<>();

            for (
                    int i = versionNodes.getLength() - 1;
                    i >= 0;
                    i--
            ) {

                String version =
                        versionNodes
                                .item(i)
                                .getTextContent();

                results.add(
                        new MavenVersionResult(
                                normalizedGroupId,
                                normalizedArtifactId,
                                version
                        )
                );
            }

            versionCache.put(
                    cacheKey,
                    new CacheEntry<>(
                            results,
                            Instant.now().plus(VERSION_CACHE_TTL)
                    )
            );

            log.info(
                    "Maven versions | artifact={} | source=MAVEN_METADATA | versions={} | durationMs={}",
                    cacheKey,
                    results.size(),
                    System.currentTimeMillis() - start
            );

            return results;

        } catch (Exception e) {

            log.error(
                    "Maven metadata parse failed | artifact={} | durationMs={}",
                    cacheKey,
                    System.currentTimeMillis() - start,
                    e
            );

            throw new IllegalStateException(
                    "Unable to parse Maven metadata for "
                            + normalizedGroupId
                            + ":"
                            + normalizedArtifactId,
                    e
            );
        }
    }

    private record CacheEntry<T>(
            T value,
            Instant expiresAt
    ) {

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}