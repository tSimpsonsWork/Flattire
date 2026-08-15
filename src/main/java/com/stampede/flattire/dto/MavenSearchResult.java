package com.stampede.flattire.dto;

public record MavenSearchResult(
        String groupId,
        String artifactId,
        String latestVersion
) {
}
