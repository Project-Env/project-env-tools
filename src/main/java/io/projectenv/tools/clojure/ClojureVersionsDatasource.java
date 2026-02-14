package io.projectenv.tools.clojure;

import io.projectenv.tools.*;
import io.projectenv.tools.github.GithubClient;
import io.projectenv.tools.github.Release;

import java.util.SortedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.plugin.logging.Log;

public class ClojureVersionsDatasource implements ToolsIndexDatasource {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+\\.\\d+\\.\\d+.\\d+)$");

    private final GithubClient githubClient;
    private final Log log;

    public ClojureVersionsDatasource(GithubClient githubClient, Log log) {
        this.githubClient = githubClient;
        this.log = log;
    }

    @Override
    public ToolsIndexV2 fetchToolVersions() {
        SortedMap<String, SortedMap<OperatingSystem, String>> clojureVersions = SortedCollections.createSemverSortedMap();

        for (Release release : githubClient.getReleases("clojure", "brew-install")) {
            String tag = release.getTagName();
            Matcher matcher = VERSION_PATTERN.matcher(tag);
            if (!matcher.find()) {
                log.debug("Unexpected release tag name: " + tag);
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
                    log.debug("Skipping unknown asset " + assetName + " for release " + tag);
                }
            }
        }

        return ImmutableToolsIndexV2.builder()
                .clojureVersions(clojureVersions)
                .build();
    }
}
