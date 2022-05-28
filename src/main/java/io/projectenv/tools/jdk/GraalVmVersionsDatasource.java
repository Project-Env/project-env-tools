package io.projectenv.tools.jdk;

import io.projectenv.core.commons.process.ProcessOutput;
import io.projectenv.core.commons.system.OperatingSystem;
import io.projectenv.tools.ImmutableToolsIndex;
import io.projectenv.tools.SortedCollections;
import io.projectenv.tools.ToolsIndex;
import io.projectenv.tools.ToolsIndexExtender;
import io.projectenv.tools.jdk.github.GithubClient;
import io.projectenv.tools.jdk.github.Release;

import java.util.Comparator;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.regex.Pattern;

public class GraalVmVersionsDatasource implements ToolsIndexExtender {

    private static final String DISTRIBUTION_ID_BASE_NAME = "graalvm_ce";
    private static final Pattern DISTRIBUTION_ID_PATTERN = Pattern.compile("^" + DISTRIBUTION_ID_BASE_NAME + "(\\d+)$");
    private static final Pattern RELEASE_TAG_PATTERN = Pattern.compile("^vm-([\\d.]+)$");

    private static final Pattern RELEASE_ASSET_NAME_PATTERN = Pattern.compile("^graalvm-ce-java(\\d+)-(\\w+)-amd64-[\\d.]+\\.(tar\\.gz|zip)$");

    private static final Set<String> SYNONYMS_BASES = Set.of(
            "Graal VM CE ",
            "graalvm_ce",
            "graalvmce",
            "GraalVM CE ",
            "GraalVMCE",
            "GraalVM_CE"
    );

    private final GithubClient githubClient;

    public GraalVmVersionsDatasource(GithubClient githubClient) {
        this.githubClient = githubClient;
    }

    @Override
    public ToolsIndex extendToolsIndex(ToolsIndex currentToolsIndex) {
        SortedMap<String, SortedSet<String>> jdkDistributionSynonyms = SortedCollections.createNaturallySortedMap(currentToolsIndex.getJdkDistributionSynonyms());
        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, String>>> jdkVersions = SortedCollections.createNaturallySortedMap(currentToolsIndex.getJdkVersions());

        var releases = githubClient.getReleases("graalvm", "graalvm-ce-builds")
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

                var majorJavaVersion = releaseAssetNameMatcher.group(1);
                var operatingSystem = releaseAssetNameMatcher.group(2);

                var distributionName = DISTRIBUTION_ID_BASE_NAME + majorJavaVersion;
                jdkVersions
                        .computeIfAbsent(distributionName, (key) -> SortedCollections.createSemverSortedMap())
                        .computeIfAbsent(version, (key) -> SortedCollections.createNaturallySortedMap())
                        .put(mapToOperatingSystem(operatingSystem), releaseAsset.getBrowserDownloadUrl());
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

        return ImmutableToolsIndex.builder()
                .from(currentToolsIndex)
                .jdkVersions(jdkVersions)
                .jdkDistributionSynonyms(jdkDistributionSynonyms)
                .build();
    }

    private OperatingSystem mapToOperatingSystem(String operatingSystemName) {
        return switch (operatingSystemName) {
            case "darwin" -> OperatingSystem.MACOS;
            default -> OperatingSystem.valueOf(operatingSystemName.toUpperCase());
        };
    }

}
