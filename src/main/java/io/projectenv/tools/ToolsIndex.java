package io.projectenv.tools;

import io.projectenv.core.commons.system.OperatingSystem;
import org.immutables.gson.Gson;
import org.immutables.value.Value;

import java.util.*;

@Gson.TypeAdapters
@Value.Immutable
@Value.Modifiable
public interface ToolsIndex {

    SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, String>>> getJdkVersions();

    SortedMap<String, SortedSet<String>> getJdkDistributionSynonyms();

    SortedMap<String, String> getGradleVersions();

    SortedMap<String, String> getMavenVersions();

    SortedMap<String, SortedMap<OperatingSystem, String>> getNodeVersions();

}
