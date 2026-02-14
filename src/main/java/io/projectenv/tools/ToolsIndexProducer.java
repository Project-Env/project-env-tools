package io.projectenv.tools;

import io.projectenv.tools.gradle.GradleVersionsDatasource;
import io.projectenv.tools.http.ResilientHttpClient;
import io.projectenv.tools.jdk.GraalVmVersionsDatasource;
import io.projectenv.tools.jdk.TemurinVersionsDatasource;
import io.projectenv.tools.jdk.github.GithubClient;
import io.projectenv.tools.jdk.github.impl.SimpleGithubClient;
import io.projectenv.tools.maven.MavenDaemonVersionsDatasource;
import io.projectenv.tools.maven.MavenVersionsDatasource;
import io.projectenv.tools.nodejs.NodeVersionsDatasource;
import io.projectenv.tools.clojure.ClojureVersionsDatasource;
import org.apache.commons.lang3.exception.ExceptionUtils;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static picocli.CommandLine.ExitCode;
import static picocli.CommandLine.Option;

@Command(name = "project-env-tools-index-producer")
public class ToolsIndexProducer implements Callable<Integer> {

    @Option(names = {"--index-file"}, required = true)
    private File indexFile;

    @Option(names = {"--legacy-index-file"}, required = true)
    private File legacyIndexFile;

    @Option(names = {"--github-access-token"}, required = true)
    private String githubAccessToken;

    @Option(names = {"--debug"})
    private boolean debug;

    @Option(names = {"--tools"}, split = ",", description = "Comma-separated list of tools to index (e.g. nodejs,maven). If not specified, all tools are indexed.")
    private List<String> tools;

    @Override
    public Integer call() {
        try {
            if (debug) {
                ProcessOutput.activateDebugMode();
            }

            var toolsIndex = readOrCreateToolsIndexV2();

            GithubClient githubClient = SimpleGithubClient.withAccessToken(githubAccessToken);
            Map<String, ToolsIndexExtender> allDatasources = new LinkedHashMap<>();
            allDatasources.put("temurin", new TemurinVersionsDatasource(githubClient));
            allDatasources.put("graalvm", new GraalVmVersionsDatasource(githubClient, ResilientHttpClient.create()));
            allDatasources.put("nodejs", new NodeVersionsDatasource());
            allDatasources.put("maven", new MavenVersionsDatasource());
            allDatasources.put("mvnd", new MavenDaemonVersionsDatasource(githubClient));
            allDatasources.put("gradle", new GradleVersionsDatasource(githubClient));
            allDatasources.put("clojure", new ClojureVersionsDatasource(githubClient));

            List<ToolsIndexExtender> datasources;
            if (tools != null && !tools.isEmpty()) {
                datasources = new ArrayList<>();
                for (String tool : tools) {
                    ToolsIndexExtender extender = allDatasources.get(tool);
                    if (extender == null) {
                        ProcessOutput.writeInfoMessage("Unknown tool: {0}. Available tools: {1}", tool, allDatasources.keySet());
                        return ExitCode.USAGE;
                    }
                    datasources.add(extender);
                }
            } else {
                datasources = new ArrayList<>(allDatasources.values());
            }

            toolsIndex = fetchDatasourcesInParallel(datasources, toolsIndex);

            toolsIndex = new DownloadUrlValidator().extendToolsIndex(toolsIndex);

            ToolIndexV2Parser.writeTo(toolsIndex, indexFile);
            ToolIndexParser.writeTo(toolsIndex.toLegacyToolsIndex(), legacyIndexFile);

            return ExitCode.OK;
        } catch (Exception e) {
            var rootCauseMessage = ExceptionUtils.getRootCauseMessage(e);

            ProcessOutput.writeInfoMessage("failed to produce tools index: {0}", rootCauseMessage);
            ProcessOutput.writeDebugMessage(e);

            return ExitCode.SOFTWARE;
        }
    }

    private ToolsIndexV2 fetchDatasourcesInParallel(List<ToolsIndexExtender> datasources, ToolsIndexV2 initialIndex) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ToolsIndexV2>> futures = new ArrayList<>();
            for (ToolsIndexExtender datasource : datasources) {
                futures.add(executor.submit(() -> datasource.extendToolsIndex(initialIndex)));
            }

            return mergeResults(initialIndex, futures);
        }
    }

    private ToolsIndexV2 mergeResults(ToolsIndexV2 initialIndex, List<Future<ToolsIndexV2>> futures) throws Exception {
        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> mergedJdkVersions =
                SortedCollections.createNaturallySortedMap(initialIndex.getJdkVersions());
        SortedMap<String, SortedSet<String>> mergedJdkDistributionSynonyms =
                SortedCollections.createNaturallySortedMap(initialIndex.getJdkDistributionSynonyms());
        SortedMap<String, String> mergedGradleVersions =
                SortedCollections.createSemverSortedMap(initialIndex.getGradleVersions());
        SortedMap<String, String> mergedMavenVersions =
                SortedCollections.createSemverSortedMap(initialIndex.getMavenVersions());
        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> mergedMvndVersions =
                SortedCollections.createSemverSortedMap(initialIndex.getMvndVersions());
        SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> mergedNodeVersions =
                SortedCollections.createSemverSortedMap(initialIndex.getNodeVersions());
        SortedMap<String, SortedMap<OperatingSystem, String>> mergedClojureVersions =
                SortedCollections.createSemverSortedMap(initialIndex.getClojureVersions());

        for (Future<ToolsIndexV2> future : futures) {
            ToolsIndexV2 result = future.get();
            putAllIfNotNull(mergedJdkVersions, result.getJdkVersions());
            putAllIfNotNull(mergedJdkDistributionSynonyms, result.getJdkDistributionSynonyms());
            putAllIfNotNull(mergedGradleVersions, result.getGradleVersions());
            putAllIfNotNull(mergedMavenVersions, result.getMavenVersions());
            putAllIfNotNull(mergedMvndVersions, result.getMvndVersions());
            putAllIfNotNull(mergedNodeVersions, result.getNodeVersions());
            putAllIfNotNull(mergedClojureVersions, result.getClojureVersions());
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

    public static void main(String[] args) {
        System.exit(executeProjectEnvToolsIndexProducer(args));
    }

    public static int executeProjectEnvToolsIndexProducer(String[] args) {
        return new CommandLine(new ToolsIndexProducer()).execute(args);
    }

    private ToolsIndexV2 readOrCreateToolsIndexV2() {
        if (indexFile.exists()) {
            return ToolIndexV2Parser.readFrom(indexFile);
        } else {
            return ImmutableToolsIndexV2.builder().build();
        }
    }

    private static <K, V> void putAllIfNotNull(Map<K, V> target, Map<K, V> source) {
        if (source != null) {
            target.putAll(source);
        }
    }

}
