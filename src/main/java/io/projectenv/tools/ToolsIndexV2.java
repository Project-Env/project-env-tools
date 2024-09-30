package io.projectenv.tools;

import org.immutables.gson.Gson;
import org.immutables.value.Value;
import org.immutables.value.Value.Style.ValidationMethod;

import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.SortedSet;

@Gson.TypeAdapters
@Value.Immutable
@Value.Modifiable
@Value.Style(validationMethod = ValidationMethod.NONE)
public interface ToolsIndexV2 {

    SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> getJdkVersions();

    SortedMap<String, SortedSet<String>> getJdkDistributionSynonyms();

    SortedMap<String, String> getGradleVersions();

    SortedMap<String, String> getMavenVersions();

    SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> getMvndVersions();

    SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> getNodeVersions();

    default ToolsIndex toLegacyToolsIndex() {
        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, String>>> simplifiedJdkVersions = SortedCollections.createNaturallySortedMap();
        for (Entry<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> distributionEntry : getJdkVersions().entrySet()) {
            for (Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : distributionEntry.getValue().entrySet()) {
                for (Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> operatingSystemEntry : versionEntry.getValue().entrySet()) {
                    if (operatingSystemEntry.getValue().containsKey(CpuArchitecture.AMD64)) {
                        simplifiedJdkVersions
                                .computeIfAbsent(distributionEntry.getKey(), key -> SortedCollections.createSemverSortedMap())
                                .computeIfAbsent(versionEntry.getKey(), key -> SortedCollections.createNaturallySortedMap())
                                .put(operatingSystemEntry.getKey(), operatingSystemEntry.getValue().get(CpuArchitecture.AMD64));
                    }
                }

            }
        }

        SortedMap<String, SortedMap<OperatingSystem, String>> simplifiedMvndVersions = SortedCollections.createSemverSortedMap();
        for (Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : getMvndVersions().entrySet()) {
            for (Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> operatingSystemEntry : versionEntry.getValue().entrySet()) {
                if (operatingSystemEntry.getValue().containsKey(CpuArchitecture.AMD64)) {
                    simplifiedMvndVersions
                            .computeIfAbsent(versionEntry.getKey(), key -> SortedCollections.createNaturallySortedMap())
                            .put(operatingSystemEntry.getKey(), operatingSystemEntry.getValue().get(CpuArchitecture.AMD64));
                }
            }
        }

        SortedMap<String, SortedMap<OperatingSystem, String>> simplifiedNodeVersions = SortedCollections.createSemverSortedMap();
        for (Entry<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> versionEntry : getNodeVersions().entrySet()) {
            for (Entry<OperatingSystem, SortedMap<CpuArchitecture, String>> operatingSystemEntry : versionEntry.getValue().entrySet()) {
                if (operatingSystemEntry.getValue().containsKey(CpuArchitecture.AMD64)) {
                    simplifiedNodeVersions
                            .computeIfAbsent(versionEntry.getKey(), key -> SortedCollections.createNaturallySortedMap())
                            .put(operatingSystemEntry.getKey(), operatingSystemEntry.getValue().get(CpuArchitecture.AMD64));
                }
            }
        }

        return ImmutableToolsIndex.builder()
                .jdkVersions(simplifiedJdkVersions)
                .jdkDistributionSynonyms(getJdkDistributionSynonyms())
                .gradleVersions(getGradleVersions())
                .mavenVersions(getMavenVersions())
                .mvndVersions(simplifiedMvndVersions)
                .nodeVersions(simplifiedNodeVersions)
                .build();
    }

}
