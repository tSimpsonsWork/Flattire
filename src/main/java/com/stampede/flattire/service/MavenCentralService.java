package com.stampede.flattire.service;

import com.stampede.flattire.dto.MavenSearchResult;
import com.stampede.flattire.dto.MavenVersionResult;
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

@Service
public class MavenCentralService {

    private final RestClient restClient;
    private static final Duration SEARCH_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration VERSION_CACHE_TTL = Duration.ofHours(12);

    private final Map<String, CacheEntry<List<MavenSearchResult>>> searchCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<MavenVersionResult>>> versionCache = new ConcurrentHashMap<>();

    public MavenCentralService() {
        this.restClient = RestClient.create("https://search.maven.org");
    }

    public List<MavenSearchResult> search(String query) {

        if (query == null || query.trim().length() < 2) return List.of();

        String cacheKey = query.trim().toLowerCase();

        CacheEntry<List<MavenSearchResult>> cached = searchCache.get(cacheKey);

        if (cached != null && !cached.isExpired()) return cached.value();

        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/solrsearch/select")
                        .queryParam("q", query.trim())
                        .queryParam("rows", 10)
                        .queryParam("wt", "json")
                        .build())
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            return List.of();
        }

        List<MavenSearchResult> results = new ArrayList<>();

        JsonNode docs = response
                .path("response")
                .path("docs");

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

        return results;
    }

    public List<MavenVersionResult> getVersions(String groupId, String artifactId) {

        String cacheKey =
                groupId.trim().toLowerCase()
                        + ":"
                        + artifactId.trim().toLowerCase();

        CacheEntry<List<MavenVersionResult>> cached =
                versionCache.get(cacheKey);

        if (cached != null && !cached.isExpired()) return cached.value();

        String groupPath = groupId.replace(".", "/");

        String metadataUrl =
                "https://repo.maven.apache.org/maven2/"
                        + groupPath
                        + "/"
                        + artifactId
                        + "/maven-metadata.xml";

        String xml = RestClient.create()
                .get()
                .uri(metadataUrl)
                .retrieve()
                .body(String.class);

        if (xml == null || xml.isBlank()) {
            return List.of();
        }

        try {
            DocumentBuilderFactory factory =
                    DocumentBuilderFactory.newInstance();

            Document document = factory
                    .newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));

            NodeList versionNodes =
                    document.getElementsByTagName("version");

            List<MavenVersionResult> results =
                    new ArrayList<>();

            for (int i = versionNodes.getLength() - 1; i >= 0; i--) {

                String version =
                        versionNodes.item(i).getTextContent();

                results.add(
                        new MavenVersionResult(
                                groupId,
                                artifactId,
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

            return results;

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to parse Maven metadata for "
                            + groupId
                            + ":"
                            + artifactId,
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