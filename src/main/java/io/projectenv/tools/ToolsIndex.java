package io.projectenv.tools;

import io.projectenv.core.commons.system.OperatingSystem;
import org.immutables.gson.Gson;
import org.immutables.value.Value;

import java.util.Map;

@Gson.TypeAdapters
@Value.Immutable
@Value.Modifiable
public interface ToolsIndex {

    Map<String, Map<String, Map<OperatingSystem, String>>> getJdkVersions();

    Map<String, String> getGradleVersions();

    Map<String, String> getMavenVersions();

    Map<String, Map<OperatingSystem, String>> getNodeVersions();

}
