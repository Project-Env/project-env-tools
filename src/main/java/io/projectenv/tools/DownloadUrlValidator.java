package io.projectenv.tools;

import io.projectenv.tools.http.ResilientHttpClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.plugin.logging.Log;

/**
 * Validates all download URLs in a tools index by sending HTTP HEAD requests.
 * <p>
 * URLs that existed in the previous index are never removed, even if validation
 * fails -- they are kept with a warning to avoid false positives from transient
 * outages. New URLs that fail validation are rejected to prevent bad data from
 * entering the index.
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

    /**
     * Validates all URLs in the merged index. Existing URLs (present in previousIndex)
     * are kept even if validation fails. New URLs are only included if validation succeeds.
     */
    public ToolsIndexV2 validateUrls(ToolsIndexV2 previousIndex, ToolsIndexV2 mergedIndex) {
        // Null-safe references to previous index maps (null when no previous index file exists)
        Map<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> prevJdkVersions =
                nullToEmpty(previousIndex.getJdkVersions());
        Map<String, String> prevGradleVersions = nullToEmpty(previousIndex.getGradleVersions());
        Map<String, String> prevMavenVersions = nullToEmpty(previousIndex.getMavenVersions());
        Map<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> prevMvndVersions =
                nullToEmpty(previousIndex.getMvndVersions());
        Map<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> prevNodeVersions =
                nullToEmpty(previousIndex.getNodeVersions());
        Map<String, SortedMap<OperatingSystem, String>> prevClojureVersions =
                nullToEmpty(previousIndex.getClojureVersions());

        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> validatedJdkVersions = SortedCollections.createNaturallySortedMap();
        SortedMap<String, String> validatedGradleVersions = SortedCollections.createSemverSortedMap();
        SortedMap<String, String> validatedMavenVersions = SortedCollections.createSemverSortedMap();
        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> validatedMvndVersions = SortedCollections.createSemverSortedMap();
        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> validatedNodeVersions = SortedCollections.createSemverSortedMap();
        SortedMap<String, SortedMap<OperatingSystem, String>> validatedClojureVersions = SortedCollections.createSemverSortedMap();

        AtomicInteger totalCount = new AtomicInteger();
        AtomicInteger keptInvalidCount = new AtomicInteger();
        AtomicInteger rejectedNewCount = new AtomicInteger();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();

            for (Map.Entry<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> distributionEntry : mergedIndex.getJdkVersions().entrySet()) {
                for (Map.Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : distributionEntry.getValue().entrySet()) {
                    for (Map.Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> osEntry : versionEntry.getValue().entrySet()) {
                        for (Map.Entry<CpuArchitecture, String> cpuEntry : osEntry.getValue().entrySet()) {
                            String distribution = distributionEntry.getKey();
                            String version = versionEntry.getKey();
                            OperatingSystem os = osEntry.getKey();
                            CpuArchitecture cpu = cpuEntry.getKey();
                            String url = cpuEntry.getValue();

                            var prevDistribution = prevJdkVersions.get(distribution);
                            var prevVersion = prevDistribution != null ? prevDistribution.get(version) : null;
                            var prevOs = prevVersion != null ? prevVersion.get(os) : null;
                            boolean existedBefore = prevOs != null && prevOs.containsKey(cpu);

                            futures.add(executor.submit(() -> {
                                totalCount.incrementAndGet();
                                boolean valid = isUrlValid(url);
                                if (!valid && !existedBefore) {
                                    rejectedNewCount.incrementAndGet();
                                    log.warn("Rejected new invalid URL for JDK " + distribution + " " + version + " " + os + "/" + cpu + ": " + url);
                                    return;
                                }
                                if (!valid) {
                                    keptInvalidCount.incrementAndGet();
                                    log.warn("Keeping potentially broken URL for JDK " + distribution + " " + version + " " + os + "/" + cpu + " (existed in previous index): " + url);
                                }
                                synchronized (validatedJdkVersions) {
                                    validatedJdkVersions
                                            .computeIfAbsent(distribution, k -> SortedCollections.createSemverSortedMap())
                                            .computeIfAbsent(version, k -> SortedCollections.createNaturallySortedMap())
                                            .computeIfAbsent(os, k -> SortedCollections.createNaturallySortedMap())
                                            .put(cpu, url);
                                }
                            }));
                        }
                    }
                }
            }

            for (Map.Entry<String, String> entry : mergedIndex.getGradleVersions().entrySet()) {
                boolean existedBefore = prevGradleVersions.containsKey(entry.getKey());

                futures.add(executor.submit(() -> {
                    totalCount.incrementAndGet();
                    boolean valid = isUrlValid(entry.getValue());
                    if (!valid && !existedBefore) {
                        rejectedNewCount.incrementAndGet();
                        log.warn("Rejected new invalid URL for Gradle " + entry.getKey() + ": " + entry.getValue());
                        return;
                    }
                    if (!valid) {
                        keptInvalidCount.incrementAndGet();
                        log.warn("Keeping potentially broken URL for Gradle " + entry.getKey() + " (existed in previous index): " + entry.getValue());
                    }
                    synchronized (validatedGradleVersions) {
                        validatedGradleVersions.put(entry.getKey(), entry.getValue());
                    }
                }));
            }

            for (Map.Entry<String, String> entry : mergedIndex.getMavenVersions().entrySet()) {
                boolean existedBefore = prevMavenVersions.containsKey(entry.getKey());

                futures.add(executor.submit(() -> {
                    totalCount.incrementAndGet();
                    boolean valid = isUrlValid(entry.getValue());
                    if (!valid && !existedBefore) {
                        rejectedNewCount.incrementAndGet();
                        log.warn("Rejected new invalid URL for Maven " + entry.getKey() + ": " + entry.getValue());
                        return;
                    }
                    if (!valid) {
                        keptInvalidCount.incrementAndGet();
                        log.warn("Keeping potentially broken URL for Maven " + entry.getKey() + " (existed in previous index): " + entry.getValue());
                    }
                    synchronized (validatedMavenVersions) {
                        validatedMavenVersions.put(entry.getKey(), entry.getValue());
                    }
                }));
            }

            for (Map.Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : mergedIndex.getMvndVersions().entrySet()) {
                for (Map.Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> osEntry : versionEntry.getValue().entrySet()) {
                    for (Map.Entry<CpuArchitecture, String> cpuEntry : osEntry.getValue().entrySet()) {
                        String version = versionEntry.getKey();
                        OperatingSystem os = osEntry.getKey();
                        CpuArchitecture cpu = cpuEntry.getKey();
                        String url = cpuEntry.getValue();

                        var prevVersion = prevMvndVersions.get(version);
                        var prevOs = prevVersion != null ? prevVersion.get(os) : null;
                        boolean existedBefore = prevOs != null && prevOs.containsKey(cpu);

                        futures.add(executor.submit(() -> {
                            totalCount.incrementAndGet();
                            boolean valid = isUrlValid(url);
                            if (!valid && !existedBefore) {
                                rejectedNewCount.incrementAndGet();
                                log.warn("Rejected new invalid URL for mvnd " + version + " " + os + "/" + cpu + ": " + url);
                                return;
                            }
                            if (!valid) {
                                keptInvalidCount.incrementAndGet();
                                log.warn("Keeping potentially broken URL for mvnd " + version + " " + os + "/" + cpu + " (existed in previous index): " + url);
                            }
                            synchronized (validatedMvndVersions) {
                                validatedMvndVersions
                                        .computeIfAbsent(version, k -> SortedCollections.createNaturallySortedMap())
                                        .computeIfAbsent(os, k -> SortedCollections.createNaturallySortedMap())
                                        .put(cpu, url);
                            }
                        }));
                    }
                }
            }

            for (Map.Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : mergedIndex.getNodeVersions().entrySet()) {
                for (Map.Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> osEntry : versionEntry.getValue().entrySet()) {
                    for (Map.Entry<CpuArchitecture, String> cpuEntry : osEntry.getValue().entrySet()) {
                        String version = versionEntry.getKey();
                        OperatingSystem os = osEntry.getKey();
                        CpuArchitecture cpu = cpuEntry.getKey();
                        String url = cpuEntry.getValue();

                        var prevVersion = prevNodeVersions.get(version);
                        var prevOs = prevVersion != null ? prevVersion.get(os) : null;
                        boolean existedBefore = prevOs != null && prevOs.containsKey(cpu);

                        futures.add(executor.submit(() -> {
                            totalCount.incrementAndGet();
                            boolean valid = isUrlValid(url);
                            if (!valid && !existedBefore) {
                                rejectedNewCount.incrementAndGet();
                                log.warn("Rejected new invalid URL for Node " + version + " " + os + "/" + cpu + ": " + url);
                                return;
                            }
                            if (!valid) {
                                keptInvalidCount.incrementAndGet();
                                log.warn("Keeping potentially broken URL for Node " + version + " " + os + "/" + cpu + " (existed in previous index): " + url);
                            }
                            synchronized (validatedNodeVersions) {
                                validatedNodeVersions
                                        .computeIfAbsent(version, k -> SortedCollections.createNaturallySortedMap())
                                        .computeIfAbsent(os, k -> SortedCollections.createNaturallySortedMap())
                                        .put(cpu, url);
                            }
                        }));
                    }
                }
            }

            for (Map.Entry<String, SortedMap<OperatingSystem, String>> versionEntry : mergedIndex.getClojureVersions().entrySet()) {
                for (Map.Entry<OperatingSystem, String> osEntry : versionEntry.getValue().entrySet()) {
                    String version = versionEntry.getKey();
                    OperatingSystem os = osEntry.getKey();
                    String url = osEntry.getValue();

                    var prevVersion = prevClojureVersions.get(version);
                    boolean existedBefore = prevVersion != null && prevVersion.containsKey(os);

                    futures.add(executor.submit(() -> {
                        totalCount.incrementAndGet();
                        boolean valid = isUrlValid(url);
                        if (!valid && !existedBefore) {
                            rejectedNewCount.incrementAndGet();
                            log.warn("Rejected new invalid URL for Clojure " + version + " " + os + ": " + url);
                            return;
                        }
                        if (!valid) {
                            keptInvalidCount.incrementAndGet();
                            log.warn("Keeping potentially broken URL for Clojure " + version + " " + os + " (existed in previous index): " + url);
                        }
                        synchronized (validatedClojureVersions) {
                            validatedClojureVersions
                                    .computeIfAbsent(version, k -> SortedCollections.createNaturallySortedMap())
                                    .put(os, url);
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

        log.info("URL validation complete: " + totalCount.get() + " checked, "
                + keptInvalidCount.get() + " kept despite validation failure (previously indexed), "
                + rejectedNewCount.get() + " new URLs rejected");

        return ImmutableToolsIndexV2.builder()
                .jdkVersions(validatedJdkVersions)
                .jdkDistributionSynonyms(mergedIndex.getJdkDistributionSynonyms())
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
                log.debug("Checking URL " + url);

                var httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .header("User-Agent", "project-env-tools/1.0")
                        .build();

                int statusCode = httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding()).statusCode();

                if (statusCode >= 200 && statusCode < 300) {
                    log.debug("Got " + statusCode + " for " + url + " - valid");
                    return true;
                } else {
                    log.warn("Got " + statusCode + " for " + url + " - invalid");
                    return false;
                }
            } finally {
                semaphore.release();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static <K, V> Map<K, V> nullToEmpty(Map<K, V> map) {
        return map != null ? map : Collections.emptyMap();
    }

}
