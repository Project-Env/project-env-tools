package io.projectenv.tools.jdk;

import io.projectenv.tools.*;
import io.projectenv.tools.github.GithubClient;
import io.projectenv.tools.github.Release;
import io.projectenv.tools.github.Repository;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import org.apache.maven.plugin.logging.Log;

public class TemurinVersionsDatasource implements ToolsIndexDatasource {

    private static final String DISTRIBUTION_ID = "temurin";
    private static final Pattern RELEASES_REPOSITORY_PATTERN = Pattern.compile("^temurin(\\d+)-binaries$");

    private static final Pattern LEGACY_RELEASE_TAG_PATTERN = Pattern.compile("^jdk(\\d+)u([\\d.]+)-b(\\d+)$");
    private static final Pattern RELEASE_TAG_PATTERN = Pattern.compile("^jdk-([\\d.+]+)$");

    private static final Pattern RELEASE_ASSET_NAME_PATTERN = Pattern.compile("^OpenJDK\\d+U-jdk_(x64|aarch64)_(\\w+)_hotspot_(.+).(tar\\.gz|zip)$");

    private static final SortedSet<String> SYNONYMS = SortedCollections.createNaturallySortedSet(
            "Temurin",
            "temurin",
            "TEMURIN"
    );

    private final GithubClient githubClient;
    private final Log log;

    public TemurinVersionsDatasource(GithubClient githubClient, Log log) {
        this.githubClient = githubClient;
        this.log = log;
    }

    @Override
    public ToolsIndexV2 fetchToolVersions() {
        SortedMap<String, SortedSet<String>> jdkDistributionSynonyms = SortedCollections.createNaturallySortedMap();
        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> jdkVersions = SortedCollections.createNaturallySortedMap();

        List<Repository> matchingRepos = githubClient.getRepositories("adoptium")
                .stream()
                .filter(repo -> RELEASES_REPOSITORY_PATTERN.matcher(repo.getName()).find())
                .toList();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Release>>> futures = new ArrayList<>();

            for (Repository repository : matchingRepos) {
                futures.add(executor.submit(() ->
                        githubClient.getReleases("adoptium", repository.getName())
                                .stream()
                                .sorted(Comparator.comparing(Release::getTagName))
                                .toList()));
            }

            for (Future<List<Release>> future : futures) {
                processReleases(future.get(), jdkVersions);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch Temurin releases in parallel", e);
        }

        jdkDistributionSynonyms.put(DISTRIBUTION_ID, SYNONYMS);

        return ImmutableToolsIndexV2.builder()
                .jdkVersions(jdkVersions)
                .jdkDistributionSynonyms(jdkDistributionSynonyms)
                .build();
    }

    private void processReleases(List<Release> releases,
                                 SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> jdkVersions) {
        for (var release : releases) {
            var version = extractJavaVersion(release.getTagName());
            if (version == null) {
                log.debug("Unexpected release tag name: " + release.getTagName());
                continue;
            }

            for (var releaseAsset : release.getAssets()) {
                var releaseAssetNameMatcher = RELEASE_ASSET_NAME_PATTERN.matcher(releaseAsset.getName());
                if (!releaseAssetNameMatcher.find()) {
                    continue;
                }

                var cpuArchitecture = mapToCpuArchitecture(releaseAssetNameMatcher.group(1));
                var operatingSystem = mapToOperatingSystem(releaseAssetNameMatcher.group(2));
                if (operatingSystem == null) {
                    continue;
                }

                jdkVersions
                        .computeIfAbsent(DISTRIBUTION_ID, k -> SortedCollections.createSemverSortedMap())
                        .computeIfAbsent(version, k -> SortedCollections.createNaturallySortedMap())
                        .computeIfAbsent(operatingSystem, k -> SortedCollections.createNaturallySortedMap())
                        .put(cpuArchitecture, releaseAsset.getBrowserDownloadUrl());
            }
        }
    }

    private String extractJavaVersion(String releaseTagName) {
        var matcher = LEGACY_RELEASE_TAG_PATTERN.matcher(releaseTagName);
        if (matcher.find()) {
            var majorJavaVersion = matcher.group(1);
            var minorJavaVersion = matcher.group(2);
            var buildVersion = matcher.group(3);
            return majorJavaVersion + ".0." + minorJavaVersion + "+" + StringUtils.stripStart(buildVersion, "0");
        }

        matcher = RELEASE_TAG_PATTERN.matcher(releaseTagName);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    private OperatingSystem mapToOperatingSystem(String name) {
        return switch (name) {
            case "mac" -> OperatingSystem.MACOS;
            case "linux" -> OperatingSystem.LINUX;
            case "windows" -> OperatingSystem.WINDOWS;
            default -> null;
        };
    }

    private CpuArchitecture mapToCpuArchitecture(String name) {
        return switch (name) {
            case "aarch64" -> CpuArchitecture.AARCH64;
            default -> CpuArchitecture.AMD64;
        };
    }

}
