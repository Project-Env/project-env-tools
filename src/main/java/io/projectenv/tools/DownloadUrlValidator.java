package io.projectenv.tools;

import io.projectenv.tools.http.ResilientHttpClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

public class DownloadUrlValidator implements ToolsIndexExtender {

    private static final int MAX_CONCURRENT_VALIDATIONS = 40;

    private final ResilientHttpClient httpClient = ResilientHttpClient.create();
    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT_VALIDATIONS);

    @Override
    public ToolsIndexV2 extendToolsIndex(ToolsIndexV2 currentToolsIndex) {
        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> validatedJdkVersions = SortedCollections.createNaturallySortedMap();
        SortedMap<String, String> validatedGradleVersions = SortedCollections.createSemverSortedMap();
        SortedMap<String, String> validatedMavenVersions = SortedCollections.createSemverSortedMap();
        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> validatedMvndVersions = SortedCollections.createSemverSortedMap();
        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> validatedNodeVersions = SortedCollections.createSemverSortedMap();
        SortedMap<String, SortedMap<OperatingSystem, String>> validatedClojureVersions = SortedCollections.createSemverSortedMap();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();

            // Validate JDK URLs
            for (Map.Entry<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> distributionEntry : currentToolsIndex.getJdkVersions().entrySet()) {
                for (Map.Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : distributionEntry.getValue().entrySet()) {
                    for (Map.Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> operatingSystemEntry : versionEntry.getValue().entrySet()) {
                        for (Map.Entry<CpuArchitecture, String> cpuArchitectureEntry : operatingSystemEntry.getValue().entrySet()) {
                            String distribution = distributionEntry.getKey();
                            String version = versionEntry.getKey();
                            OperatingSystem os = operatingSystemEntry.getKey();
                            CpuArchitecture cpu = cpuArchitectureEntry.getKey();
                            String url = cpuArchitectureEntry.getValue();

                            futures.add(executor.submit(() -> {
                                if (urlReturns2XX(url)) {
                                    synchronized (validatedJdkVersions) {
                                        validatedJdkVersions
                                                .computeIfAbsent(distribution, key -> SortedCollections.createSemverSortedMap())
                                                .computeIfAbsent(version, key -> SortedCollections.createNaturallySortedMap())
                                                .computeIfAbsent(os, key -> SortedCollections.createNaturallySortedMap())
                                                .put(cpu, url);
                                    }
                                }
                            }));
                        }
                    }
                }
            }

            // Validate Gradle URLs
            for (Map.Entry<String, String> versionEntry : currentToolsIndex.getGradleVersions().entrySet()) {
                String version = versionEntry.getKey();
                String url = versionEntry.getValue();

                futures.add(executor.submit(() -> {
                    if (urlReturns2XX(url)) {
                        synchronized (validatedGradleVersions) {
                            validatedGradleVersions.put(version, url);
                        }
                    }
                }));
            }

            // Validate Maven URLs
            for (Map.Entry<String, String> versionEntry : currentToolsIndex.getMavenVersions().entrySet()) {
                String version = versionEntry.getKey();
                String url = versionEntry.getValue();

                futures.add(executor.submit(() -> {
                    if (urlReturns2XX(url)) {
                        synchronized (validatedMavenVersions) {
                            validatedMavenVersions.put(version, url);
                        }
                    }
                }));
            }

            // Validate mvnd URLs
            for (Map.Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : currentToolsIndex.getMvndVersions().entrySet()) {
                for (Map.Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> operatingSystemEntry : versionEntry.getValue().entrySet()) {
                    for (Map.Entry<CpuArchitecture, String> cpuArchitectureEntry : operatingSystemEntry.getValue().entrySet()) {
                        String version = versionEntry.getKey();
                        OperatingSystem os = operatingSystemEntry.getKey();
                        CpuArchitecture cpu = cpuArchitectureEntry.getKey();
                        String url = cpuArchitectureEntry.getValue();

                        futures.add(executor.submit(() -> {
                            if (urlReturns2XX(url)) {
                                synchronized (validatedMvndVersions) {
                                    validatedMvndVersions
                                            .computeIfAbsent(version, key -> SortedCollections.createNaturallySortedMap())
                                            .computeIfAbsent(os, key -> SortedCollections.createNaturallySortedMap())
                                            .put(cpu, url);
                                }
                            }
                        }));
                    }
                }
            }

            // Validate Node URLs
            for (Map.Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : currentToolsIndex.getNodeVersions().entrySet()) {
                for (Map.Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> operatingSystemEntry : versionEntry.getValue().entrySet()) {
                    for (Map.Entry<CpuArchitecture, String> cpuArchitectureEntry : operatingSystemEntry.getValue().entrySet()) {
                        String version = versionEntry.getKey();
                        OperatingSystem os = operatingSystemEntry.getKey();
                        CpuArchitecture cpu = cpuArchitectureEntry.getKey();
                        String url = cpuArchitectureEntry.getValue();

                        futures.add(executor.submit(() -> {
                            if (urlReturns2XX(url)) {
                                synchronized (validatedNodeVersions) {
                                    validatedNodeVersions
                                            .computeIfAbsent(version, key -> SortedCollections.createNaturallySortedMap())
                                            .computeIfAbsent(os, key -> SortedCollections.createNaturallySortedMap())
                                            .put(cpu, url);
                                }
                            }
                        }));
                    }
                }
            }

            // Validate Clojure URLs
            for (Map.Entry<String, SortedMap<OperatingSystem, String>> versionEntry : currentToolsIndex.getClojureVersions().entrySet()) {
                for (Map.Entry<OperatingSystem, String> operatingSystemEntry : versionEntry.getValue().entrySet()) {
                    String version = versionEntry.getKey();
                    OperatingSystem os = operatingSystemEntry.getKey();
                    String url = operatingSystemEntry.getValue();

                    futures.add(executor.submit(() -> {
                        if (urlReturns2XX(url)) {
                            synchronized (validatedClojureVersions) {
                                validatedClojureVersions
                                        .computeIfAbsent(version, key -> SortedCollections.createNaturallySortedMap())
                                        .put(os, url);
                            }
                        }
                    }));
                }
            }

            // Wait for all validations to complete
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (Exception e) {
            throw new RuntimeException("URL validation failed", e);
        }

        return ImmutableToolsIndexV2.builder()
                .jdkVersions(validatedJdkVersions)
                .jdkDistributionSynonyms(currentToolsIndex.getJdkDistributionSynonyms())
                .gradleVersions(validatedGradleVersions)
                .mavenVersions(validatedMavenVersions)
                .mvndVersions(validatedMvndVersions)
                .nodeVersions(validatedNodeVersions)
                .clojureVersions(validatedClojureVersions)
                .build();
    }

    private boolean urlReturns2XX(String url) {
        try {
            semaphore.acquire();
            try {
                ProcessOutput.writeInfoMessage("Checking URL {0}...", url);

                var httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build();

                int statusCode = httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding()).statusCode();

                if (statusCode >= 200 && statusCode < 300) {
                    ProcessOutput.writeInfoMessage("Got {0} for URL {1} - keeping URL", statusCode, url);
                    return true;
                } else {
                    ProcessOutput.writeInfoMessage("Got {0} for URL {1} - removing URL from index", statusCode, url);
                    return false;
                }
            } finally {
                semaphore.release();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}
