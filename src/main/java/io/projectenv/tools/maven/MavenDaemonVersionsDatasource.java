package io.projectenv.tools.maven;

import io.projectenv.core.commons.process.ProcessOutput;
import io.projectenv.core.commons.system.OperatingSystem;
import io.projectenv.tools.*;
import io.projectenv.tools.jdk.github.GithubClient;
import io.projectenv.tools.jdk.github.Release;

import java.util.Comparator;
import java.util.Map;
import java.util.SortedMap;
import java.util.regex.Pattern;

public class MavenDaemonVersionsDatasource implements ToolsIndexExtender {

    private static final Pattern RELEASE_TAG_PATTERN = Pattern.compile("^([\\d.]+)$");

    // mvnd-0.7.1-darwin-amd64.zip
    private static final Pattern RELEASE_ASSET_NAME_PATTERN = Pattern.compile("^mvnd-[\\d.]+-(\\w+)-amd64\\.zip$");

    private final GithubClient githubClient;

    public MavenDaemonVersionsDatasource(GithubClient githubClient) {
        this.githubClient = githubClient;
    }

    @Override
    public ToolsIndexV2 extendToolsIndex(ToolsIndexV2 currentToolsIndex) {
        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> mvndVersions = SortedCollections.createNaturallySortedMap(currentToolsIndex.getMvndVersions());

        var releases = githubClient.getReleases("apache", "maven-mvnd")
                .stream()
                .sorted(Comparator.comparing(Release::getTagName))
                .toList();

        for (var release : releases) {
            var releaseTagNameMatcher = RELEASE_TAG_PATTERN.matcher(release.getTagName());
            if (!releaseTagNameMatcher.find()) {
                ProcessOutput.writeInfoMessage("unexpected release tag name {0}", release.getTagName());
                continue;
            }

            var version = releaseTagNameMatcher.group(1);

            for (var releaseAsset : release.getAssets()) {
                var releaseAssetNameMatcher = RELEASE_ASSET_NAME_PATTERN.matcher(releaseAsset.getName());
                if (!releaseAssetNameMatcher.find()) {
                    continue;
                }

                var operatingSystem = releaseAssetNameMatcher.group(1);

                mvndVersions
                        .computeIfAbsent(version, (key) -> SortedCollections.createNaturallySortedMap())
                        .put(mapToOperatingSystem(operatingSystem), SortedCollections.createNaturallySortedMap(Map.of(
                                CpuArchitecture.AMD64, releaseAsset.getBrowserDownloadUrl()
                        )));
            }
        }

        return ImmutableToolsIndexV2.builder()
                .from(currentToolsIndex)
                .mvndVersions(mvndVersions)
                .build();
    }

    private OperatingSystem mapToOperatingSystem(String operatingSystemName) {
        return switch (operatingSystemName) {
            case "darwin" -> OperatingSystem.MACOS;
            default -> OperatingSystem.valueOf(operatingSystemName.toUpperCase());
        };
    }

}
