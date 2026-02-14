package io.projectenv.tools.gradle;

import io.projectenv.tools.ImmutableToolsIndexV2;
import io.projectenv.tools.SortedCollections;
import io.projectenv.tools.ToolsIndexDatasource;
import io.projectenv.tools.ToolsIndexV2;
import io.projectenv.tools.github.GithubClient;
import io.projectenv.tools.github.Release;

import java.util.Comparator;
import java.util.SortedMap;
import java.util.regex.Pattern;

import org.apache.maven.plugin.logging.Log;

public class GradleVersionsDatasource implements ToolsIndexDatasource {

    private static final Pattern RELEASE_TAG_PATTERN = Pattern.compile("^v(.+)$");
    private static final Pattern BIN_ASSET_PATTERN = Pattern.compile("^gradle-.+-bin\\.zip$");
    private static final Pattern RC_OR_MILESTONE_PATTERN = Pattern.compile("(?i)(-RC|-M|milestone|rc)\\d*$");

    private final GithubClient githubClient;
    private final Log log;

    public GradleVersionsDatasource(GithubClient githubClient, Log log) {
        this.githubClient = githubClient;
        this.log = log;
    }

    @Override
    public ToolsIndexV2 fetchToolVersions() {
        SortedMap<String, String> gradleVersions = SortedCollections.createSemverSortedMap();

        var releases = githubClient.getReleases("gradle", "gradle-distributions")
                .stream()
                .filter(release -> !release.isPrerelease())
                .sorted(Comparator.comparing(Release::getTagName))
                .toList();

        for (var release : releases) {
            var version = extractGradleVersion(release.getTagName());
            if (version == null) {
                log.info("Unexpected release tag name: " + release.getTagName());
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
                .gradleVersions(gradleVersions)
                .build();
    }

    private String extractGradleVersion(String releaseTagName) {
        var matcher = RELEASE_TAG_PATTERN.matcher(releaseTagName);
        if (matcher.find()) {
            var version = matcher.group(1);
            if (RC_OR_MILESTONE_PATTERN.matcher(version).find()) {
                return null;
            }
            return version;
        }
        return null;
    }
}
