package io.projectenv.tools.jdk;

import io.projectenv.core.commons.process.ProcessOutput;
import io.projectenv.core.commons.system.OperatingSystem;
import io.projectenv.tools.ImmutableToolsIndex;
import io.projectenv.tools.SortedCollections;
import io.projectenv.tools.ToolsIndex;
import io.projectenv.tools.ToolsIndexExtender;
import io.projectenv.tools.jdk.github.GithubClient;
import io.projectenv.tools.jdk.github.Release;
import io.projectenv.tools.jdk.github.Repository;
import org.apache.commons.lang3.StringUtils;

import java.util.Comparator;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.regex.Pattern;

public class TemurinVersionsDatasource implements ToolsIndexExtender {

    private static final String DISTRIBUTION_ID = "temurin";
    private static final Pattern RELEASES_REPOSITORY_PATTERN = Pattern.compile("^temurin(\\d+)-binaries$");

    private static final Pattern RELEASE_TAG_PATTERN = Pattern.compile("^jdk-?(\\d+)(\\.0.|u)([\\d.]+)(\\+|-b)(\\d+)(\\.\\d*|)$");

    private static final Pattern RELEASE_ASSET_NAME_PATTERN = Pattern.compile("^OpenJDK\\d+U-jdk_x64_(\\w+)_hotspot_(.+).(tar\\.gz|zip)$");

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
    public ToolsIndex extendToolsIndex(ToolsIndex currentToolsIndex) {
        SortedMap<String, SortedSet<String>> jdkDistributionSynonyms = SortedCollections.createNaturallySortedMap(currentToolsIndex.getJdkDistributionSynonyms());
        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, String>>> jdkVersions = SortedCollections.createNaturallySortedMap(currentToolsIndex.getJdkVersions());

        for (Repository repository : githubClient.getRepositories("adoptium")) {
            if (!RELEASES_REPOSITORY_PATTERN.matcher(repository.getName()).find()) {
                continue;
            }

            var releases = githubClient.getReleases("adoptium", repository.getName())
                    .stream()
                    .sorted(Comparator.comparing(Release::getTagName))
                    .toList();

            for (var release : releases) {
                var releaseTagNameMatcher = RELEASE_TAG_PATTERN.matcher(release.getTagName());
                if (!releaseTagNameMatcher.find()) {
                    ProcessOutput.writeInfoMessage("unexpected release tag name {0}", release.getTagName());
                    continue;
                }

                var majorJavaVersion = releaseTagNameMatcher.group(1);
                var minorJavaVersion = releaseTagNameMatcher.group(3);
                var buildVersion = releaseTagNameMatcher.group(5);

                var version = majorJavaVersion + ".0." + minorJavaVersion + "+" + StringUtils.stripStart(buildVersion, "0");
                for (var releaseAsset : release.getAssets()) {
                    var releaseAssetNameMatcher = RELEASE_ASSET_NAME_PATTERN.matcher(releaseAsset.getName());
                    if (!releaseAssetNameMatcher.find()) {
                        continue;
                    }

                    var operatingSystemName = releaseAssetNameMatcher.group(1);
                    var operatingSystem = mapToOperatingSystem(operatingSystemName);
                    if (operatingSystem == null) {
                        continue;
                    }

                    jdkVersions
                            .computeIfAbsent(DISTRIBUTION_ID, (key) -> SortedCollections.createSemverSortedMap())
                            .computeIfAbsent(version, (key) -> SortedCollections.createNaturallySortedMap())
                            .put(operatingSystem, releaseAsset.getBrowserDownloadUrl());
                }
            }
        }

        jdkDistributionSynonyms.put(DISTRIBUTION_ID, SYNONYMS);

        return ImmutableToolsIndex.builder()
                .from(currentToolsIndex)
                .jdkVersions(jdkVersions)
                .jdkDistributionSynonyms(jdkDistributionSynonyms)
                .build();
    }

    private OperatingSystem mapToOperatingSystem(String operatingSystemName) {
        return switch (operatingSystemName) {
            case "mac" -> OperatingSystem.MACOS;
            case "linux" -> OperatingSystem.LINUX;
            case "windows" -> OperatingSystem.WINDOWS;
            default -> null;
        };
    }

}
