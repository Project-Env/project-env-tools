package io.projectenv.tools.jdk;

import io.projectenv.tools.*;
import io.projectenv.tools.http.ResilientHttpClient;
import io.projectenv.tools.github.GithubClient;
import io.projectenv.tools.github.Release;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.maven.plugin.logging.Log;

public class GraalVmVersionsDatasource implements ToolsIndexDatasource {

    private static final String DISTRIBUTION_ID_BASE_NAME = "graalvm_ce";
    private static final Pattern DISTRIBUTION_ID_PATTERN = Pattern.compile("^" + DISTRIBUTION_ID_BASE_NAME + "(\\d+)$");

    private static final Pattern RELEASE_ASSET_NAME_PATTERN = Pattern.compile("graalvm-(?:ce|community)-(?:java|jdk-)(\\d+)[^-_]*[-_](\\w+)-(amd64|x64|aarch64)[-_](?:[\\d.]+|bin)\\.(?:tar\\.gz|zip)$");

    private static final Pattern GRAAL_VM_VERSION_PATTERN = Pattern.compile("GRAALVM_VERSION=\"?([\\d.]+)\"?");

    private static final String GRAAL_VM_VERSION_FILE_PATH = "/release";

    private static final Set<String> SYNONYMS_BASES = Set.of(
            "Graal VM CE ",
            "graalvm_ce",
            "graalvmce",
            "GraalVM CE ",
            "GraalVMCE",
            "GraalVM_CE"
    );

    private final GithubClient githubClient;
    private final ResilientHttpClient httpClient;
    private final Log log;

    public GraalVmVersionsDatasource(GithubClient githubClient, ResilientHttpClient httpClient, Log log) {
        this.githubClient = githubClient;
        this.httpClient = httpClient;
        this.log = log;
    }

    @Override
    public ToolsIndexV2 fetchToolVersions() {
        SortedMap<String, SortedSet<String>> jdkDistributionSynonyms = SortedCollections.createNaturallySortedMap();
        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> jdkVersions = SortedCollections.createNaturallySortedMap();

        var releases = githubClient.getReleases("graalvm", "graalvm-ce-builds")
                .stream()
                .sorted(Comparator.comparing(Release::getTagName))
                .toList();

        List<ReleaseResult> releaseResults = processReleasesInParallel(releases);

        for (var result : releaseResults) {
            for (Map.Entry<String, Map<OperatingSystem, Map<CpuArchitecture, String>>> entry : result.downloadUrls().entrySet()) {
                var distributionName = DISTRIBUTION_ID_BASE_NAME + entry.getKey();

                for (Map.Entry<OperatingSystem, Map<CpuArchitecture, String>> osEntry : entry.getValue().entrySet()) {
                    jdkVersions
                            .computeIfAbsent(distributionName, k -> SortedCollections.createSemverSortedMap())
                            .computeIfAbsent(result.graalVmVersion(), k -> SortedCollections.createNaturallySortedMap())
                            .computeIfAbsent(osEntry.getKey(), k -> SortedCollections.createNaturallySortedMap())
                            .putAll(osEntry.getValue());
                }
            }
        }

        for (var distributionId : jdkVersions.keySet()) {
            var matcher = DISTRIBUTION_ID_PATTERN.matcher(distributionId);
            if (!matcher.find()) {
                continue;
            }

            var majorJavaVersion = matcher.group(1);
            jdkDistributionSynonyms.put(distributionId, SYNONYMS_BASES.stream()
                    .map(base -> base + majorJavaVersion)
                    .collect(SortedCollections.toNaturallySortedSet()));
        }

        return ImmutableToolsIndexV2.builder()
                .jdkVersions(jdkVersions)
                .jdkDistributionSynonyms(jdkDistributionSynonyms)
                .build();
    }

    private List<ReleaseResult> processReleasesInParallel(List<Release> releases) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ReleaseResult>> futures = new ArrayList<>();

            for (var release : releases) {
                futures.add(executor.submit(() -> processRelease(release)));
            }

            List<ReleaseResult> results = new ArrayList<>();
            for (var future : futures) {
                var result = future.get();
                if (result != null) {
                    results.add(result);
                }
            }
            return results;
        } catch (Exception e) {
            throw new RuntimeException("Failed to process GraalVM releases in parallel", e);
        }
    }

    private ReleaseResult processRelease(Release release) {
        var downloadUrls = new HashMap<String, Map<OperatingSystem, Map<CpuArchitecture, String>>>();
        for (var releaseAsset : release.getAssets()) {
            var matcher = RELEASE_ASSET_NAME_PATTERN.matcher(releaseAsset.getName());
            if (!matcher.find()) {
                log.debug("Skipping unknown release asset: " + releaseAsset.getName());
                continue;
            }

            var javaMajorVersion = matcher.group(1);
            var operatingSystem = mapToOperatingSystem(matcher.group(2));
            var cpuArchitecture = mapToCpuArchitecture(matcher.group(3));

            downloadUrls
                    .computeIfAbsent(javaMajorVersion, k -> new EnumMap<>(OperatingSystem.class))
                    .computeIfAbsent(operatingSystem, k -> new EnumMap<>(CpuArchitecture.class))
                    .put(cpuArchitecture, releaseAsset.getBrowserDownloadUrl());
        }

        if (downloadUrls.isEmpty()) {
            return null;
        }

        String firstWindowsDownloadUrl = downloadUrls.entrySet().stream()
                .findFirst()
                .map(entry -> entry.getValue().get(OperatingSystem.WINDOWS).get(CpuArchitecture.AMD64))
                .orElseThrow();

        String graalVmVersion = extractGraalVmVersion(firstWindowsDownloadUrl);

        return new ReleaseResult(graalVmVersion, downloadUrls);
    }

    private OperatingSystem mapToOperatingSystem(String name) {
        return switch (name) {
            case "darwin" -> OperatingSystem.MACOS;
            default -> OperatingSystem.valueOf(name.toUpperCase());
        };
    }

    private CpuArchitecture mapToCpuArchitecture(String name) {
        return switch (name) {
            case "aarch64" -> CpuArchitecture.AARCH64;
            default -> CpuArchitecture.AMD64;
        };
    }

    private String extractGraalVmVersion(String windowsDistributionUrl) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(windowsDistributionUrl))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("Unexpected status code " + response.statusCode() + " for " + windowsDistributionUrl);
            }

            try (InputStream in = response.body();
                 ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(in))) {

                ZipEntry entry;
                while ((entry = zipIn.getNextEntry()) != null) {
                    if (entry.getName().endsWith(GRAAL_VM_VERSION_FILE_PATH)) {
                        var buffer = new ByteArrayOutputStream();
                        zipIn.transferTo(buffer);

                        String versionFileContent = buffer.toString(StandardCharsets.UTF_8);
                        var versionMatcher = GRAAL_VM_VERSION_PATTERN.matcher(versionFileContent);
                        if (!versionMatcher.find()) {
                            throw new IllegalStateException("Found release file but could not extract GraalVM version");
                        }
                        return versionMatcher.group(1);
                    }
                }
            }

            throw new IllegalStateException("Could not find release file in " + windowsDistributionUrl);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(e);
        }
    }

    record ReleaseResult(String graalVmVersion, Map<String, Map<OperatingSystem, Map<CpuArchitecture, String>>> downloadUrls) {
    }

}
