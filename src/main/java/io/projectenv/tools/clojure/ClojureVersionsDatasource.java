package io.projectenv.tools.clojure;

import io.projectenv.tools.*;
import io.projectenv.tools.jdk.github.GithubClient;
import io.projectenv.tools.jdk.github.Release;

import java.util.SortedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClojureVersionsDatasource implements ToolsIndexExtender {

    private static final String REPO_OWNER = "clojure";
    private static final String REPO_NAME = "brew-install";
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+\\.\\d+\\.\\d+.\\d+)$");

    private final GithubClient githubClient;

    public ClojureVersionsDatasource(GithubClient githubClient) {
        this.githubClient = githubClient;
    }

    @Override
    public ToolsIndexV2 extendToolsIndex(ToolsIndexV2 currentToolsIndex) {
        SortedMap<String, SortedMap<OperatingSystem, String>> clojureVersions = SortedCollections.createSemverSortedMap(currentToolsIndex.getClojureVersions());

        for (Release release : githubClient.getReleases(REPO_OWNER, REPO_NAME)) {
            String tag = release.getTagName();
            Matcher matcher = VERSION_PATTERN.matcher(tag);
            if (!matcher.find()) {
                ProcessOutput.writeInfoMessage("unexpected release tag name {0}", tag);
                continue;
            }

            String version = matcher.group(1);

            for (var asset : release.getAssets()) {
                String assetName = asset.getName();
                String downloadUrl = asset.getBrowserDownloadUrl();
                if (assetName.equals("clojure-tools.zip")) {
                    clojureVersions
                            .computeIfAbsent(version, v -> SortedCollections.createNaturallySortedMap())
                            .put(OperatingSystem.WINDOWS, downloadUrl);
                } else if (assetName.equals("clojure-tools-" + version + ".tar.gz")) {
                    clojureVersions
                            .computeIfAbsent(version, v -> SortedCollections.createNaturallySortedMap())
                            .put(OperatingSystem.LINUX, downloadUrl);
                    clojureVersions
                            .computeIfAbsent(version, v -> SortedCollections.createNaturallySortedMap())
                            .put(OperatingSystem.MACOS, downloadUrl);
                } else {
                    ProcessOutput.writeInfoMessage("skipping unknown asset {0} for release {1}", assetName, tag);
                }
            }
        }

        return ImmutableToolsIndexV2.builder()
                .from(currentToolsIndex)
                .clojureVersions(clojureVersions)
                .build();
    }
}

