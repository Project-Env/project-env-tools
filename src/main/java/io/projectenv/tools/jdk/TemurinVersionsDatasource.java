package io.projectenv.tools.jdk;

import io.projectenv.tools.*;
import io.projectenv.tools.jdk.github.GithubClient;
import io.projectenv.tools.jdk.github.Release;
import io.projectenv.tools.jdk.github.Repository;
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

public class TemurinVersionsDatasource implements ToolsIndexExtender {

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

    public TemurinVersionsDatasource(GithubClient githubClient) {
        this.githubClient = githubClient;
    }

    @Override
    public ToolsIndexV2 extendToolsIndex(ToolsIndexV2 currentToolsIndex) {
        SortedMap<String, SortedSet<String>> jdkDistributionSynonyms = SortedCollections.createNaturallySortedMap(currentToolsIndex.getJdkDistributionSynonyms());
        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> jdkVersions = SortedCollections.createNaturallySortedMap(currentToolsIndex.getJdkVersions());

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
                .from(currentToolsIndex)
                .jdkVersions(jdkVersions)
                .jdkDistributionSynonyms(jdkDistributionSynonyms)
                .build();
    }

    private void processReleases(List<Release> releases,
                                 SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> jdkVersions) {
        for (var release : releases) {
            var version = extractJavaVersion(release.getTagName());
            if (version == null) {
                ProcessOutput.writeInfoMessage("unexpected release tag name {0}", release.getTagName());
                continue;
            }

            for (var releaseAsset : release.getAssets()) {
                var releaseAssetNameMatcher = RELEASE_ASSET_NAME_PATTERN.matcher(releaseAsset.getName());
                if (!releaseAssetNameMatcher.find()) {
                    continue;
                }

                var cpuArchitectureName = releaseAssetNameMatcher.group(1);
                var cpuArchitecture = mapToCpuArchitecture(cpuArchitectureName);

                var operatingSystemName = releaseAssetNameMatcher.group(2);
                var operatingSystem = mapToOperatingSystem(operatingSystemName);
                if (operatingSystem == null) {
                    continue;
                }

                jdkVersions
                        .computeIfAbsent(DISTRIBUTION_ID, (key) -> SortedCollections.createSemverSortedMap())
                        .computeIfAbsent(version, (key) -> SortedCollections.createNaturallySortedMap())
                        .computeIfAbsent(operatingSystem, (key) -> SortedCollections.createNaturallySortedMap())
                        .put(cpuArchitecture, releaseAsset.getBrowserDownloadUrl());
            }
        }
    }

    private String extractJavaVersion(String releaseTagName) {
        var releaseTagNameMatcher = LEGACY_RELEASE_TAG_PATTERN.matcher(releaseTagName);
        if (releaseTagNameMatcher.find()) {
            var majorJavaVersion = releaseTagNameMatcher.group(1);
            var minorJavaVersion = releaseTagNameMatcher.group(2);
            var buildVersion = releaseTagNameMatcher.group(3);

            return majorJavaVersion + ".0." + minorJavaVersion + "+" + StringUtils.stripStart(buildVersion, "0");
        }

        releaseTagNameMatcher = RELEASE_TAG_PATTERN.matcher(releaseTagName);
        if (releaseTagNameMatcher.find()) {
            return releaseTagNameMatcher.group(1);
        }

        return null;
    }

    private OperatingSystem mapToOperatingSystem(String operatingSystemName) {
        return switch (operatingSystemName) {
            case "mac" -> OperatingSystem.MACOS;
            case "linux" -> OperatingSystem.LINUX;
            case "windows" -> OperatingSystem.WINDOWS;
            default -> null;
        };
    }

    private CpuArchitecture mapToCpuArchitecture(String cpuArchitectureName) {
        return switch (cpuArchitectureName) {
            case "aarch64" -> CpuArchitecture.AARCH64;
            default -> CpuArchitecture.AMD64;
        };
    }

}
