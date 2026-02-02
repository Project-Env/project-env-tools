package io.projectenv.tools;

import io.projectenv.tools.gradle.GradleVersionsDatasource;
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
import java.util.List;
import java.util.concurrent.Callable;

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

    @Override
    public Integer call() {
        try {
            if (debug) {
                ProcessOutput.activateDebugMode();
            }

            var toolsIndex = readOrCreateToolsIndexV2();

            GithubClient githubClient = SimpleGithubClient.withAccessToken(githubAccessToken);
            for (ToolsIndexExtender extender : List.of(
                    new TemurinVersionsDatasource(githubClient),
                    new GraalVmVersionsDatasource(githubClient),
                    new NodeVersionsDatasource(),
                    new MavenVersionsDatasource(),
                    new MavenDaemonVersionsDatasource(githubClient),
                    new GradleVersionsDatasource(githubClient),
                    new ClojureVersionsDatasource(githubClient),
                    new DownloadUrlValidator())) {

                toolsIndex = extender.extendToolsIndex(toolsIndex);
            }

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

}
