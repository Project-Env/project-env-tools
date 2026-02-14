package io.projectenv.tools.nodejs;

import io.projectenv.tools.*;
import org.apache.maven.plugin.logging.Log;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NodeVersionsDatasource implements ToolsIndexDatasource {

    private static final String RELEASE_BASE_URL = "https://nodejs.org/download/release/";

    private static final Pattern VERSION_PATTERN = Pattern.compile("v(\\d+\\.\\d+\\.\\d+)/");

    private static final int MAX_CONCURRENT_FETCHES = 40;

    private record AssetMapping(String preferredSuffix, String fallbackSuffix, OperatingSystem os,
                                CpuArchitecture arch) {
    }

    private static final List<AssetMapping> ASSET_MAPPINGS = List.of(
            new AssetMapping("-darwin-x64.tar.xz", "-darwin-x64.tar.gz", OperatingSystem.MACOS, CpuArchitecture.AMD64),
            new AssetMapping("-darwin-arm64.tar.xz", "-darwin-arm64.tar.gz", OperatingSystem.MACOS, CpuArchitecture.AARCH64),
            new AssetMapping("-linux-x64.tar.xz", "-linux-x64.tar.gz", OperatingSystem.LINUX, CpuArchitecture.AMD64),
            new AssetMapping("-linux-arm64.tar.xz", "-linux-arm64.tar.gz", OperatingSystem.LINUX, CpuArchitecture.AARCH64),
            new AssetMapping("-win-x64.zip", null, OperatingSystem.WINDOWS, CpuArchitecture.AMD64)
    );

    private final Log log;
    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT_FETCHES);

    public NodeVersionsDatasource(Log log) {
        this.log = log;
    }

    @Override
    public ToolsIndexV2 fetchToolVersions() {
        List<String> versions = fetchVersions();
        log.info("Found " + versions.size() + " Node.js versions, fetching release assets...");

        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> downloadUrls =
                SortedCollections.createSemverSortedMap();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();

            for (String version : versions) {
                futures.add(executor.submit(() -> {
                    var osMap = fetchVersionAssets(version);
                    if (!osMap.isEmpty()) {
                        synchronized (downloadUrls) {
                            downloadUrls.put(version, osMap);
                        }
                    }
                }));
            }

            for (Future<?> future : futures) {
                future.get();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch Node.js version assets", e);
        }

        return ImmutableToolsIndexV2.builder()
                .nodeVersions(downloadUrls)
                .build();
    }

    private List<String> fetchVersions() {
        try {
            Document doc = Jsoup.connect(RELEASE_BASE_URL).get();

            return doc.getElementsByTag("a")
                    .stream()
                    .map(element -> element.attr("href"))
                    .map(VERSION_PATTERN::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> matcher.group(1))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch Node.js version list", e);
        }
    }

    private SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>> fetchVersionAssets(String version) {
        String versionUrl = RELEASE_BASE_URL + "v" + version + "/";

        try {
            semaphore.acquire();
            try {
                log.debug("Fetching assets for Node.js v" + version);

                Document doc = Jsoup.connect(versionUrl).get();

                Set<String> availableFiles = new HashSet<>();
                for (var element : doc.getElementsByTag("a")) {
                    availableFiles.add(element.attr("href"));
                }

                String filePrefix = "node-v" + version;

                SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>> osMap =
                        SortedCollections.createNaturallySortedMap();

                for (AssetMapping mapping : ASSET_MAPPINGS) {
                    String preferredFile = filePrefix + mapping.preferredSuffix();
                    String fallbackFile = mapping.fallbackSuffix() != null ? filePrefix + mapping.fallbackSuffix() : null;

                    String matchedFile = null;
                    if (availableFiles.contains(preferredFile)) {
                        matchedFile = preferredFile;
                    } else if (fallbackFile != null && availableFiles.contains(fallbackFile)) {
                        matchedFile = fallbackFile;
                    }

                    if (matchedFile != null) {
                        osMap.computeIfAbsent(mapping.os(), k -> SortedCollections.createNaturallySortedMap())
                                .put(mapping.arch(), versionUrl + matchedFile);
                    }
                }

                return osMap;
            } finally {
                semaphore.release();
            }
        } catch (IOException | InterruptedException e) {
            log.warn("Failed to fetch assets for Node.js v" + version + ": " + e.getMessage());
            return SortedCollections.createNaturallySortedMap();
        }
    }

}
