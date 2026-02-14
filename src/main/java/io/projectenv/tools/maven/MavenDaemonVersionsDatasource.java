package io.projectenv.tools.maven;

import io.projectenv.tools.*;
import io.projectenv.tools.github.GithubClient;
import io.projectenv.tools.github.Release;

import java.util.Comparator;
import java.util.SortedMap;
import java.util.regex.Pattern;

import org.apache.maven.plugin.logging.Log;

public class MavenDaemonVersionsDatasource implements ToolsIndexDatasource {

    private static final Pattern RELEASE_TAG_PATTERN = Pattern.compile("^([\\d.]+)$");
    private static final Pattern RELEASE_ASSET_NAME_PATTERN = Pattern.compile("^(?:maven-)?mvnd-[\\d.]+-(\\w+)-(amd64|aarch64)\\.zip$");

    private final GithubClient githubClient;
    private final Log log;

    public MavenDaemonVersionsDatasource(GithubClient githubClient, Log log) {
        this.githubClient = githubClient;
        this.log = log;
    }

    @Override
    public ToolsIndexV2 fetchToolVersions() {
        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> mvndVersions = SortedCollections.createSemverSortedMap();

        var releases = githubClient.getReleases("apache", "maven-mvnd")
                .stream()
                .sorted(Comparator.comparing(Release::getTagName))
                .toList();

        for (var release : releases) {
            var releaseTagNameMatcher = RELEASE_TAG_PATTERN.matcher(release.getTagName());
            if (!releaseTagNameMatcher.find()) {
                log.info("Unexpected release tag name: " + release.getTagName());
                continue;
            }

            var version = releaseTagNameMatcher.group(1);

            for (var releaseAsset : release.getAssets()) {
                var releaseAssetNameMatcher = RELEASE_ASSET_NAME_PATTERN.matcher(releaseAsset.getName());
                if (!releaseAssetNameMatcher.find()) {
                    continue;
                }

                var operatingSystem = mapToOperatingSystem(releaseAssetNameMatcher.group(1));
                var cpuArchitecture = mapToCpuArchitecture(releaseAssetNameMatcher.group(2));

                mvndVersions
                        .computeIfAbsent(version, k -> SortedCollections.createNaturallySortedMap())
                        .computeIfAbsent(operatingSystem, k -> SortedCollections.createNaturallySortedMap())
                        .put(cpuArchitecture, releaseAsset.getBrowserDownloadUrl());
            }
        }

        return ImmutableToolsIndexV2.builder()
                .mvndVersions(mvndVersions)
                .build();
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

}
