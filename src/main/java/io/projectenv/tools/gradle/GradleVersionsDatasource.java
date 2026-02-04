package io.projectenv.tools.gradle;

import io.projectenv.tools.ImmutableToolsIndexV2;
import io.projectenv.tools.ProcessOutput;
import io.projectenv.tools.SortedCollections;
import io.projectenv.tools.ToolsIndexExtender;
import io.projectenv.tools.ToolsIndexV2;
import io.projectenv.tools.jdk.github.GithubClient;
import io.projectenv.tools.jdk.github.Release;

import java.util.Comparator;
import java.util.SortedMap;
import java.util.regex.Pattern;

public class GradleVersionsDatasource implements ToolsIndexExtender {

    private static final String GITHUB_OWNER = "gradle";
    private static final String GITHUB_REPO = "gradle-distributions";

    private static final Pattern RELEASE_TAG_PATTERN = Pattern.compile("^v(.+)$");
    private static final Pattern BIN_ASSET_PATTERN = Pattern.compile("^gradle-.+-bin\\.zip$");
    private static final Pattern RC_OR_MILESTONE_PATTERN = Pattern.compile("(?i)(-RC|-M|milestone|rc)\\d*$");

    private final GithubClient githubClient;

    public GradleVersionsDatasource(GithubClient githubClient) {
        this.githubClient = githubClient;
    }

    @Override
    public ToolsIndexV2 extendToolsIndex(ToolsIndexV2 currentToolsIndex) {
        SortedMap<String, String> gradleVersions = SortedCollections.createSemverSortedMap(currentToolsIndex.getGradleVersions());

        var releases = githubClient.getReleases(GITHUB_OWNER, GITHUB_REPO)
                .stream()
                .filter(release -> !release.isPrerelease())
                .sorted(Comparator.comparing(Release::getTagName))
                .toList();

        for (var release : releases) {
            var version = extractGradleVersion(release.getTagName());
            if (version == null) {
                ProcessOutput.writeInfoMessage("unexpected release tag name {0}", release.getTagName());
                continue;
            }

            for (var releaseAsset : release.getAssets()) {
                if (!BIN_ASSET_PATTERN.matcher(releaseAsset.getName()).matches()) {
                    continue;
                }

                gradleVersions.put(version, releaseAsset.getBrowserDownloadUrl());
                break;
            }
        }

        return ImmutableToolsIndexV2.builder()
                .from(currentToolsIndex)
                .gradleVersions(gradleVersions)
                .build();
    }

    private String extractGradleVersion(String releaseTagName) {
        var matcher = RELEASE_TAG_PATTERN.matcher(releaseTagName);
        if (matcher.find()) {
            var version = matcher.group(1);
            // Exclude RC and Milestone releases
            if (RC_OR_MILESTONE_PATTERN.matcher(version).find()) {
                return null;
            }
            return version;
        }
        return null;
    }
}
