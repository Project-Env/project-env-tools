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

import org.apache.maven.plugin.logging.Log;

/**
 * Validates all download URLs in a tools index by sending HTTP HEAD requests.
 * URLs that don't return a 2xx status code are removed from the index.
 */
public class DownloadUrlValidator {

    private static final int MAX_CONCURRENT_VALIDATIONS = 40;

    private final ResilientHttpClient httpClient;
    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT_VALIDATIONS);
    private final Log log;

    public DownloadUrlValidator(Log log) {
        this.httpClient = ResilientHttpClient.create(log);
        this.log = log;
    }

    public ToolsIndexV2 validateUrls(ToolsIndexV2 toolsIndex) {
        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> validatedJdkVersions = SortedCollections.createNaturallySortedMap();
        SortedMap<String, String> validatedGradleVersions = SortedCollections.createSemverSortedMap();
        SortedMap<String, String> validatedMavenVersions = SortedCollections.createSemverSortedMap();
        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> validatedMvndVersions = SortedCollections.createSemverSortedMap();
        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> validatedNodeVersions = SortedCollections.createSemverSortedMap();
        SortedMap<String, SortedMap<OperatingSystem, String>> validatedClojureVersions = SortedCollections.createSemverSortedMap();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();

            for (Map.Entry<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> distributionEntry : toolsIndex.getJdkVersions().entrySet()) {
                for (Map.Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : distributionEntry.getValue().entrySet()) {
                    for (Map.Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> osEntry : versionEntry.getValue().entrySet()) {
                        for (Map.Entry<CpuArchitecture, String> cpuEntry : osEntry.getValue().entrySet()) {
                            String distribution = distributionEntry.getKey();
                            String version = versionEntry.getKey();
                            OperatingSystem os = osEntry.getKey();
                            CpuArchitecture cpu = cpuEntry.getKey();
                            String url = cpuEntry.getValue();

                            futures.add(executor.submit(() -> {
                                if (isUrlValid(url)) {
                                    synchronized (validatedJdkVersions) {
                                        validatedJdkVersions
                                                .computeIfAbsent(distribution, k -> SortedCollections.createSemverSortedMap())
                                                .computeIfAbsent(version, k -> SortedCollections.createNaturallySortedMap())
                                                .computeIfAbsent(os, k -> SortedCollections.createNaturallySortedMap())
                                                .put(cpu, url);
                                    }
                                }
                            }));
                        }
                    }
                }
            }

            for (Map.Entry<String, String> entry : toolsIndex.getGradleVersions().entrySet()) {
                futures.add(executor.submit(() -> {
                    if (isUrlValid(entry.getValue())) {
                        synchronized (validatedGradleVersions) {
                            validatedGradleVersions.put(entry.getKey(), entry.getValue());
                        }
                    }
                }));
            }

            for (Map.Entry<String, String> entry : toolsIndex.getMavenVersions().entrySet()) {
                futures.add(executor.submit(() -> {
                    if (isUrlValid(entry.getValue())) {
                        synchronized (validatedMavenVersions) {
                            validatedMavenVersions.put(entry.getKey(), entry.getValue());
                        }
                    }
                }));
            }

            for (Map.Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : toolsIndex.getMvndVersions().entrySet()) {
                for (Map.Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> osEntry : versionEntry.getValue().entrySet()) {
                    for (Map.Entry<CpuArchitecture, String> cpuEntry : osEntry.getValue().entrySet()) {
                        String version = versionEntry.getKey();
                        OperatingSystem os = osEntry.getKey();
                        CpuArchitecture cpu = cpuEntry.getKey();
                        String url = cpuEntry.getValue();

                        futures.add(executor.submit(() -> {
                            if (isUrlValid(url)) {
                                synchronized (validatedMvndVersions) {
                                    validatedMvndVersions
                                            .computeIfAbsent(version, k -> SortedCollections.createNaturallySortedMap())
                                            .computeIfAbsent(os, k -> SortedCollections.createNaturallySortedMap())
                                            .put(cpu, url);
                                }
                            }
                        }));
                    }
                }
            }

            for (Map.Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : toolsIndex.getNodeVersions().entrySet()) {
                for (Map.Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> osEntry : versionEntry.getValue().entrySet()) {
                    for (Map.Entry<CpuArchitecture, String> cpuEntry : osEntry.getValue().entrySet()) {
                        String version = versionEntry.getKey();
                        OperatingSystem os = osEntry.getKey();
                        CpuArchitecture cpu = cpuEntry.getKey();
                        String url = cpuEntry.getValue();

                        futures.add(executor.submit(() -> {
                            if (isUrlValid(url)) {
                                synchronized (validatedNodeVersions) {
                                    validatedNodeVersions
                                            .computeIfAbsent(version, k -> SortedCollections.createNaturallySortedMap())
                                            .computeIfAbsent(os, k -> SortedCollections.createNaturallySortedMap())
                                            .put(cpu, url);
                                }
                            }
                        }));
                    }
                }
            }

            for (Map.Entry<String, SortedMap<OperatingSystem, String>> versionEntry : toolsIndex.getClojureVersions().entrySet()) {
                for (Map.Entry<OperatingSystem, String> osEntry : versionEntry.getValue().entrySet()) {
                    String version = versionEntry.getKey();
                    OperatingSystem os = osEntry.getKey();
                    String url = osEntry.getValue();

                    futures.add(executor.submit(() -> {
                        if (isUrlValid(url)) {
                            synchronized (validatedClojureVersions) {
                                validatedClojureVersions
                                        .computeIfAbsent(version, k -> SortedCollections.createNaturallySortedMap())
                                        .put(os, url);
                            }
                        }
                    }));
                }
            }

            for (Future<?> future : futures) {
                future.get();
            }
        } catch (Exception e) {
            throw new RuntimeException("URL validation failed", e);
        }

        return ImmutableToolsIndexV2.builder()
                .jdkVersions(validatedJdkVersions)
                .jdkDistributionSynonyms(toolsIndex.getJdkDistributionSynonyms())
                .gradleVersions(validatedGradleVersions)
                .mavenVersions(validatedMavenVersions)
                .mvndVersions(validatedMvndVersions)
                .nodeVersions(validatedNodeVersions)
                .clojureVersions(validatedClojureVersions)
                .build();
    }

    private boolean isUrlValid(String url) {
        try {
            semaphore.acquire();
            try {
                log.info("Checking URL " + url);

                var httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .header("User-Agent", "project-env-tools/1.0")
                        .build();

                int statusCode = httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding()).statusCode();

                if (statusCode >= 200 && statusCode < 300) {
                    log.info("Got " + statusCode + " for " + url + " - keeping");
                    return true;
                } else {
                    log.info("Got " + statusCode + " for " + url + " - removing");
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
