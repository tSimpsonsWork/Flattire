package com.stampede.flattire.controller;

import com.stampede.flattire.dto.MavenSearchResult;
import com.stampede.flattire.dto.MavenVersionResult;
import com.stampede.flattire.service.MavenCentralService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/flattire/api")
@RequiredArgsConstructor
public class MavenCentralController {

    private final MavenCentralService mavenCentralService;

    //http://localhost:8080/flattire/api/search?query=postgresql
    @GetMapping("/search")
    public List<MavenSearchResult> search(@RequestParam String query) {
        return mavenCentralService.search(query);
    }

    //http://localhost:8080/flattire/api/versions?groupId=org.postgresql&artifactId=postgresql
    @GetMapping("/versions")
    public List<MavenVersionResult> versions(
            @RequestParam String groupId,
            @RequestParam String artifactId
    ) {
        return mavenCentralService.getVersions(groupId, artifactId);
    }
}