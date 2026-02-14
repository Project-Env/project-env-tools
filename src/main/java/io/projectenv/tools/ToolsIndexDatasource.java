package io.projectenv.tools;

/**
 * A datasource that produces tool version entries for the tools index.
 * Each datasource is responsible for one type of tool (e.g. Node.js, Maven, Gradle).
 */
public interface ToolsIndexDatasource {

    ToolsIndexV2 fetchToolVersions();

}
