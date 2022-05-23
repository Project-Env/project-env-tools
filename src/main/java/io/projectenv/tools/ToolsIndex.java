package io.projectenv.tools;

import io.projectenv.core.commons.system.OperatingSystem;
import org.immutables.gson.Gson;
import org.immutables.value.Value;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Gson.TypeAdapters
@Value.Immutable
@Value.Modifiable
public interface ToolsIndex {

    Map<String, Map<String, Map<OperatingSystem, String>>> getJdkVersions();

    Map<String, Set<String>> getJdkDistributionSynonyms();

    Map<String, String> getGradleVersions();

    Map<String, String> getMavenVersions();

    Map<String, Map<OperatingSystem, String>> getNodeVersions();

}
