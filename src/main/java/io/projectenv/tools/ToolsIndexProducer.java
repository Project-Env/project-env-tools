package io.projectenv.tools;

import io.projectenv.core.commons.process.ProcessOutput;
import io.projectenv.tools.gradle.GradleVersionsDatasource;
import io.projectenv.tools.jdk.JdkVersionsDatasource;
import io.projectenv.tools.maven.MavenVersionsDatasource;
import io.projectenv.tools.nodejs.NodeVersionsDatasource;
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

    @Option(names = {"--debug"})
    private boolean debug;

    @Override
    public Integer call() {
        try {
            if (debug) {
                ProcessOutput.activateDebugMode();
            }

            var toolsIndex = readOrCreateToolsIndex();
            for (ToolsIndexExtender extender : List.of(new JdkVersionsDatasource(), new NodeVersionsDatasource(), new MavenVersionsDatasource(), new GradleVersionsDatasource())) {
                toolsIndex = extender.extendToolsIndex(toolsIndex);
            }

            ToolIndexParser.writeTo(toolsIndex, indexFile);

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

    private ToolsIndex readOrCreateToolsIndex() {
        if (indexFile.exists()) {
            return ToolIndexParser.readFrom(indexFile);
        } else {
            return ImmutableToolsIndex.builder().build();
        }
    }

}
