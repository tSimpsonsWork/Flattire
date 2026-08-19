package com.stampede.flattire.dto;

public record MavenVersionResult(
        String groupId,
        String artifactId,
        String version
) {

}