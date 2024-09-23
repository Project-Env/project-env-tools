package io.projectenv.tools;

import io.projectenv.core.commons.process.ProcessOutput;
import io.projectenv.core.commons.system.OperatingSystem;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.SortedMap;

public class DownloadUrlValidator implements ToolsIndexExtender {

    private final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public ToolsIndexV2 extendToolsIndex(ToolsIndexV2 currentToolsIndex) {
        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> validatedJdkVersions = SortedCollections.createNaturallySortedMap();
        for (Map.Entry<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> distributionEntry : currentToolsIndex.getJdkVersions().entrySet()) {
            for (Map.Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : distributionEntry.getValue().entrySet()) {
                for (Map.Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> operatingSystemEntry : versionEntry.getValue().entrySet()) {
                    for (Map.Entry<CpuArchitecture, String> cpuArchitectureEntry : operatingSystemEntry.getValue().entrySet()) {
                        if (urlReturns2XX(cpuArchitectureEntry.getValue())) {
                            validatedJdkVersions
                                    .computeIfAbsent(distributionEntry.getKey(), key -> SortedCollections.createNaturallySortedMap())
                                    .computeIfAbsent(versionEntry.getKey(), key -> SortedCollections.createNaturallySortedMap())
                                    .computeIfAbsent(operatingSystemEntry.getKey(), key -> SortedCollections.createNaturallySortedMap())
                                    .put(cpuArchitectureEntry.getKey(), cpuArchitectureEntry.getValue());
                        }
                    }
                }
            }
        }

        SortedMap<String, String> validatedGradleVersions = SortedCollections.createNaturallySortedMap();
        for (Map.Entry<String, String> versionEntry : currentToolsIndex.getGradleVersions().entrySet()) {
            if (urlReturns2XX(versionEntry.getValue())) {
                validatedGradleVersions.put(versionEntry.getKey(), versionEntry.getValue());
            }
        }

        SortedMap<String, String> validatedMavenVersions = SortedCollections.createNaturallySortedMap();
        for (Map.Entry<String, String> versionEntry : currentToolsIndex.getMavenVersions().entrySet()) {
            if (urlReturns2XX(versionEntry.getValue())) {
                validatedMavenVersions.put(versionEntry.getKey(), versionEntry.getValue());
            }
        }

        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> validatedMvndVersions = SortedCollections.createNaturallySortedMap();
        for (Map.Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : currentToolsIndex.getMvndVersions().entrySet()) {
            for (Map.Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> operatingSystemEntry : versionEntry.getValue().entrySet()) {
                for (Map.Entry<CpuArchitecture, String> cpuArchitectureEntry : operatingSystemEntry.getValue().entrySet()) {
                    if (urlReturns2XX(cpuArchitectureEntry.getValue())) {
                        validatedMvndVersions
                                .computeIfAbsent(versionEntry.getKey(), key -> SortedCollections.createNaturallySortedMap())
                                .computeIfAbsent(operatingSystemEntry.getKey(), key -> SortedCollections.createNaturallySortedMap())
                                .put(cpuArchitectureEntry.getKey(), cpuArchitectureEntry.getValue());
                    }
                }
            }
        }

        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> validatedNodeVersions = SortedCollections.createNaturallySortedMap();
        for (Map.Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : currentToolsIndex.getNodeVersions().entrySet()) {
            for (Map.Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> operatingSystemEntry : versionEntry.getValue().entrySet()) {
                for (Map.Entry<CpuArchitecture, String> cpuArchitectureEntry : operatingSystemEntry.getValue().entrySet()) {
                    if (urlReturns2XX(cpuArchitectureEntry.getValue())) {
                        validatedNodeVersions
                                .computeIfAbsent(versionEntry.getKey(), key -> SortedCollections.createNaturallySortedMap())
                                .computeIfAbsent(operatingSystemEntry.getKey(), key -> SortedCollections.createNaturallySortedMap())
                                .put(cpuArchitectureEntry.getKey(), cpuArchitectureEntry.getValue());
                    }
                }
            }
        }

        return ImmutableToolsIndexV2.builder()
                .jdkVersions(validatedJdkVersions)
                .jdkDistributionSynonyms(currentToolsIndex.getJdkDistributionSynonyms())
                .gradleVersions(validatedGradleVersions)
                .mavenVersions(validatedMavenVersions)
                .mvndVersions(validatedMvndVersions)
                .nodeVersions(validatedNodeVersions)
                .build();
    }

    private boolean urlReturns2XX(String url) {
        try {
            ProcessOutput.writeInfoMessage("Checking URL {0}...", url);

            var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            int statusCode = HTTP_CLIENT.send(httpRequest, HttpResponse.BodyHandlers.discarding()).statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                ProcessOutput.writeInfoMessage("Got {0} for URL {1} - keeping URL", statusCode, url);
                return true;
            } else {
                ProcessOutput.writeInfoMessage("Got {0} for URL {1} - removing URL from index", statusCode, url);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}
