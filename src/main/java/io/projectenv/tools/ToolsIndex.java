package io.projectenv.tools;

import io.projectenv.core.commons.system.OperatingSystem;
import org.immutables.gson.Gson;
import org.immutables.value.Value;
import org.immutables.value.Value.Style.ValidationMethod;

import java.util.*;

@Gson.TypeAdapters
@Value.Immutable
@Value.Modifiable
@Value.Style(validationMethod = ValidationMethod.NONE)
public interface ToolsIndex {

    SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, String>>> getJdkVersions();

    SortedMap<String, SortedSet<String>> getJdkDistributionSynonyms();

    SortedMap<String, String> getGradleVersions();

    SortedMap<String, String> getMavenVersions();

    SortedMap<String, SortedMap<OperatingSystem, String>> getMvndVersions();

    SortedMap<String, SortedMap<OperatingSystem, String>> getNodeVersions();

}
