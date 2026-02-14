package io.projectenv.tools.jdk;

import io.projectenv.tools.*;
import io.projectenv.tools.http.ResilientHttpClient;
import io.projectenv.tools.jdk.github.GithubClient;
import io.projectenv.tools.jdk.github.Release;

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

public class GraalVmVersionsDatasource implements ToolsIndexExtender {

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

    public GraalVmVersionsDatasource(GithubClient githubClient, ResilientHttpClient httpClient) {
        this.githubClient = githubClient;
        this.httpClient = httpClient;
    }

    @Override
    public ToolsIndexV2 extendToolsIndex(ToolsIndexV2 currentToolsIndex) {
        SortedMap<String, SortedSet<String>> jdkDistributionSynonyms = SortedCollections.createNaturallySortedMap(currentToolsIndex.getJdkDistributionSynonyms());
        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> jdkVersions = SortedCollections.createNaturallySortedMap(currentToolsIndex.getJdkVersions());

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
                            .computeIfAbsent(distributionName, key -> SortedCollections.createSemverSortedMap())
                            .computeIfAbsent(result.graalVmVersion(), key -> SortedCollections.createNaturallySortedMap())
                            .computeIfAbsent(osEntry.getKey(), key -> SortedCollections.createNaturallySortedMap())
                            .putAll(osEntry.getValue());
                }
            }
        }

        for (var distributionId : jdkVersions.keySet()) {
            var distributionIdMatcher = DISTRIBUTION_ID_PATTERN.matcher(distributionId);
            if (!distributionIdMatcher.find()) {
                continue;
            }

            var majorJavaVersion = distributionIdMatcher.group(1);
            jdkDistributionSynonyms.put(distributionId, SYNONYMS_BASES.stream()
                    .map(synonymBaseName -> synonymBaseName + majorJavaVersion)
                    .collect(SortedCollections.toNaturallySortedSet()));
        }

        return ImmutableToolsIndexV2.builder()
                .from(currentToolsIndex)
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
            throw new RuntimeException("failed to process GraalVM releases in parallel", e);
        }
    }

    private ReleaseResult processRelease(Release release) {
        var downloadUrls = new HashMap<String, Map<OperatingSystem, Map<CpuArchitecture, String>>>();
        for (var releaseAsset : release.getAssets()) {
            var releaseAssetNameMatcher = RELEASE_ASSET_NAME_PATTERN.matcher(releaseAsset.getName());
            if (!releaseAssetNameMatcher.find()) {
                ProcessOutput.writeInfoMessage("skipping unknown release asset name {0}", releaseAsset.getName());
                continue;
            }

            var javaMajorVersion = releaseAssetNameMatcher.group(1);
            var operatingSystem = mapToOperatingSystem(releaseAssetNameMatcher.group(2));
            var cpuArchitecture = mapToCpuArchitecture(releaseAssetNameMatcher.group(3));

            downloadUrls
                    .computeIfAbsent(javaMajorVersion, key -> new EnumMap<>(OperatingSystem.class))
                    .computeIfAbsent(operatingSystem, key -> new EnumMap<>(CpuArchitecture.class))
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

    private OperatingSystem mapToOperatingSystem(String operatingSystemName) {
        return switch (operatingSystemName) {
            case "darwin" -> OperatingSystem.MACOS;
            default -> OperatingSystem.valueOf(operatingSystemName.toUpperCase());
        };
    }

    private CpuArchitecture mapToCpuArchitecture(String cpuArchitectureName) {
        return switch (cpuArchitectureName) {
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
                throw new IOException("unexpected status code " + response.statusCode() + " for " + windowsDistributionUrl);
            }

            try (InputStream in = response.body();
                 ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(in))) {

                ZipEntry entry;
                while ((entry = zipIn.getNextEntry()) != null) {
                    if (entry.getName().endsWith(GRAAL_VM_VERSION_FILE_PATH)) {
                        var buffer = new ByteArrayOutputStream();
                        zipIn.transferTo(buffer);

                        String versionFileContent = buffer.toString(StandardCharsets.UTF_8);

                        var graalVmVersionMatcher = GRAAL_VM_VERSION_PATTERN.matcher(versionFileContent);
                        if (!graalVmVersionMatcher.find()) {
                            throw new IllegalStateException("found release file, but could not extract GraalVM version");
                        }
                        return graalVmVersionMatcher.group(1);
                    }
                }
            }

            throw new IllegalStateException("could not find release file in " + windowsDistributionUrl);
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
