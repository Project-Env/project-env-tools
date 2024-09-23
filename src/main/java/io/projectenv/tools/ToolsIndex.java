package io.projectenv.tools;

import org.immutables.gson.Gson;
import org.immutables.value.Value;
import org.immutables.value.Value.Style.ValidationMethod;

import java.util.SortedMap;
import java.util.SortedSet;

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
