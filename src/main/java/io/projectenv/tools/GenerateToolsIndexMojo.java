package io.projectenv.tools;

import io.projectenv.tools.clojure.ClojureVersionsDatasource;
import io.projectenv.tools.gradle.GradleVersionsDatasource;
import io.projectenv.tools.http.ResilientHttpClient;
import io.projectenv.tools.jdk.GraalVmVersionsDatasource;
import io.projectenv.tools.jdk.TemurinVersionsDatasource;
import io.projectenv.tools.github.GithubClient;
import io.projectenv.tools.github.impl.SimpleGithubClient;
import io.projectenv.tools.maven.MavenDaemonVersionsDatasource;
import io.projectenv.tools.maven.MavenVersionsDatasource;
import io.projectenv.tools.nodejs.NodeVersionsDatasource;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Generates the project-env tools index by fetching version information
 * from upstream sources and validating all download URLs.
 */
@Mojo(name = "generate-index", requiresProject = false)
public class GenerateToolsIndexMojo extends AbstractMojo {

    @Parameter(property = "indexFile", required = true)
    private File indexFile;

    @Parameter(property = "legacyIndexFile", required = true)
    private File legacyIndexFile;

    @Parameter(property = "githubAccessToken", required = true)
    private String githubAccessToken;

    /**
     * Comma-separated list of tools to index (e.g. "nodejs,maven").
     * Available: temurin, graalvm, nodejs, maven, mvnd, gradle, clojure.
     * If not specified, all tools are indexed.
     */
    @Parameter(property = "tools")
    private String tools;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            var previousIndex = readOrCreateToolsIndex();
            var toolsIndex = previousIndex;

            GithubClient githubClient = SimpleGithubClient.withAccessToken(githubAccessToken, getLog());
            Map<String, ToolsIndexDatasource> allDatasources = createDatasources(githubClient);

            Map<String, ToolsIndexDatasource> datasources = selectDatasources(allDatasources);

            getLog().info("Fetching versions from " + datasources.size() + " datasources: " + datasources.keySet());
            toolsIndex = fetchInParallel(datasources, toolsIndex);

            getLog().info("Validating download URLs...");
            toolsIndex = new DownloadUrlValidator(getLog()).validateUrls(previousIndex, toolsIndex);

            ToolIndexV2Parser.writeTo(toolsIndex, indexFile);
            ToolIndexParser.writeTo(toolsIndex.toLegacyToolsIndex(), legacyIndexFile);

            getLog().info("Tools index written to " + indexFile.getAbsolutePath());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate tools index", e);
        }
    }

    private Map<String, ToolsIndexDatasource> createDatasources(GithubClient githubClient) {
        Map<String, ToolsIndexDatasource> datasources = new LinkedHashMap<>();
        datasources.put("temurin", new TemurinVersionsDatasource(githubClient, getLog()));
        datasources.put("graalvm", new GraalVmVersionsDatasource(githubClient, ResilientHttpClient.create(getLog()), getLog()));
        datasources.put("nodejs", new NodeVersionsDatasource(getLog()));
        datasources.put("maven", new MavenVersionsDatasource());
        datasources.put("mvnd", new MavenDaemonVersionsDatasource(githubClient, getLog()));
        datasources.put("gradle", new GradleVersionsDatasource(githubClient, getLog()));
        datasources.put("clojure", new ClojureVersionsDatasource(githubClient, getLog()));
        return datasources;
    }

    private Map<String, ToolsIndexDatasource> selectDatasources(Map<String, ToolsIndexDatasource> allDatasources)
            throws MojoFailureException {
        if (tools == null || tools.isBlank()) {
            return allDatasources;
        }

        Map<String, ToolsIndexDatasource> selected = new LinkedHashMap<>();
        for (String tool : tools.split(",")) {
            String trimmed = tool.trim();
            ToolsIndexDatasource datasource = allDatasources.get(trimmed);
            if (datasource == null) {
                throw new MojoFailureException("Unknown tool: " + trimmed
                        + ". Available tools: " + allDatasources.keySet());
            }
            selected.put(trimmed, datasource);
        }
        return selected;
    }

    private ToolsIndexV2 fetchInParallel(Map<String, ToolsIndexDatasource> datasources, ToolsIndexV2 initialIndex)
            throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ToolsIndexV2>> futures = new ArrayList<>();
            for (var entry : datasources.entrySet()) {
                String name = entry.getKey();
                ToolsIndexDatasource datasource = entry.getValue();
                futures.add(executor.submit(() -> {
                    getLog().info("Fetching " + name + " versions...");
                    ToolsIndexV2 result = datasource.fetchToolVersions();
                    getLog().info("Fetched " + name + " versions");
                    return result;
                }));
            }

            return mergeResults(initialIndex, futures);
        }
    }

    private ToolsIndexV2 mergeResults(ToolsIndexV2 initialIndex, List<Future<ToolsIndexV2>> futures) throws Exception {
        // Start with empty maps to avoid sharing inner map references with previousIndex.
        // The initialIndex is merged as the first source, so all inner maps are freshly created.
        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> mergedJdkVersions =
                SortedCollections.createNaturallySortedMap();
        SortedMap<String, SortedSet<String>> mergedJdkDistributionSynonyms =
                SortedCollections.createNaturallySortedMap();
        SortedMap<String, String> mergedGradleVersions =
                SortedCollections.createSemverSortedMap();
        SortedMap<String, String> mergedMavenVersions =
                SortedCollections.createSemverSortedMap();
        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> mergedMvndVersions =
                SortedCollections.createSemverSortedMap();
        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> mergedNodeVersions =
                SortedCollections.createSemverSortedMap();
        SortedMap<String, SortedMap<OperatingSystem, String>> mergedClojureVersions =
                SortedCollections.createSemverSortedMap();

        // Collect initialIndex + datasource results into a single list
        List<ToolsIndexV2> allResults = new ArrayList<>();
        allResults.add(initialIndex);
        for (Future<ToolsIndexV2> future : futures) {
            allResults.add(future.get());
        }

        for (ToolsIndexV2 result : allResults) {
            deepMergeJdkVersions(mergedJdkVersions, result.getJdkVersions());
            putAllIfNotNull(mergedJdkDistributionSynonyms, result.getJdkDistributionSynonyms());
            putAllIfNotNull(mergedGradleVersions, result.getGradleVersions());
            putAllIfNotNull(mergedMavenVersions, result.getMavenVersions());
            deepMergeByVersionOsCpu(mergedMvndVersions, result.getMvndVersions());
            deepMergeByVersionOsCpu(mergedNodeVersions, result.getNodeVersions());
            deepMergeByVersionOs(mergedClojureVersions, result.getClojureVersions());
        }

        return ImmutableToolsIndexV2.builder()
                .jdkVersions(mergedJdkVersions)
                .jdkDistributionSynonyms(mergedJdkDistributionSynonyms)
                .gradleVersions(mergedGradleVersions)
                .mavenVersions(mergedMavenVersions)
                .mvndVersions(mergedMvndVersions)
                .nodeVersions(mergedNodeVersions)
                .clojureVersions(mergedClojureVersions)
                .build();
    }

    private ToolsIndexV2 readOrCreateToolsIndex() {
        if (indexFile.exists()) {
            return ToolIndexV2Parser.readFrom(indexFile);
        }
        return ImmutableToolsIndexV2.builder().build();
    }

    /**
     * Deep-merges JDK versions: distribution -> version -> OS -> CPU -> URL.
     * Uses computeIfAbsent at each level so existing entries from earlier sources are preserved.
     */
    private static void deepMergeJdkVersions(
            Map<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> target,
            Map<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> source) {
        if (source == null) {
            return;
        }
        for (var distEntry : source.entrySet()) {
            var targetVersions = target.computeIfAbsent(distEntry.getKey(),
                    k -> SortedCollections.createSemverSortedMap());
            deepMergeByVersionOsCpu(targetVersions, distEntry.getValue());
        }
    }

    /**
     * Deep-merges version -> OS -> CPU -> URL (used for Node and Mvnd).
     * Uses computeIfAbsent at each level so existing OS/CPU entries are preserved
     * even when a datasource omits them.
     */
    private static void deepMergeByVersionOsCpu(
            Map<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> target,
            Map<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> source) {
        if (source == null) {
            return;
        }
        for (var versionEntry : source.entrySet()) {
            var targetOsMap = target.computeIfAbsent(versionEntry.getKey(),
                    k -> SortedCollections.createNaturallySortedMap());
            for (var osEntry : versionEntry.getValue().entrySet()) {
                var targetCpuMap = targetOsMap.computeIfAbsent(osEntry.getKey(),
                        k -> SortedCollections.createNaturallySortedMap());
                targetCpuMap.putAll(osEntry.getValue());
            }
        }
    }

    /**
     * Deep-merges version -> OS -> URL (used for Clojure).
     * Uses computeIfAbsent so existing OS entries are preserved.
     */
    private static void deepMergeByVersionOs(
            Map<String, SortedMap<OperatingSystem, String>> target,
            Map<String, SortedMap<OperatingSystem, String>> source) {
        if (source == null) {
            return;
        }
        for (var versionEntry : source.entrySet()) {
            var targetOsMap = target.computeIfAbsent(versionEntry.getKey(),
                    k -> SortedCollections.createNaturallySortedMap());
            targetOsMap.putAll(versionEntry.getValue());
        }
    }

    private static <K, V> void putAllIfNotNull(Map<K, V> target, Map<K, V> source) {
        if (source != null) {
            target.putAll(source);
        }
    }

}
