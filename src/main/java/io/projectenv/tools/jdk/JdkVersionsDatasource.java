package io.projectenv.tools.jdk;

import io.projectenv.core.commons.system.OperatingSystem;
import io.projectenv.tools.ImmutableToolsIndex;
import io.projectenv.tools.ToolsIndex;
import io.projectenv.tools.ToolsIndexExtender;
import io.projectenv.tools.jdk.discoapi.*;

import java.util.*;

public class JdkVersionsDatasource implements ToolsIndexExtender {

    private static final List<String> DISTRIBUTIONS = List.of("temurin", "graalvm_ce11", "graalvm_ce17");
    public static final String RELEASE_TYPE_GENERAL_ACCESS = "ga";

    private final DiscoApiClient discoApiClient = DiscoApiClientFactory.createDiscoApiClient();

    @Override
    public ToolsIndex extendToolsIndex(ToolsIndex currentToolsIndex) {
        Map<String, Set<String>> jdkDistributionSynonyms = new TreeMap<>(currentToolsIndex.getJdkDistributionSynonyms());
        Map<String, Map<String, Map<OperatingSystem, String>>> jdkVersions = new TreeMap<>(currentToolsIndex.getJdkVersions());

        for (String distributionId : DISTRIBUTIONS) {
            DiscoApiDistribution distributionInfo = fetchDistributionInfo(distributionId);

            Set<String> distributionSynonyms = jdkDistributionSynonyms.computeIfAbsent(distributionId, (key) -> new TreeSet<>());
            distributionSynonyms.add(distributionInfo.getName());
            distributionSynonyms.addAll(distributionInfo.getSynonyms());

            for (String distributionVersion : distributionInfo.getVersions()) {
                if (distributionVersion.endsWith("-ea")) {
                    continue;
                }

                Map<OperatingSystem, String> downloadUrls = fetchDownloadUrls(distributionId, distributionVersion);
                if (!downloadUrls.isEmpty()) {
                    jdkVersions.computeIfAbsent(distributionId, (key) -> new TreeMap<>()).put(distributionVersion, downloadUrls);
                }
            }
        }

        return ImmutableToolsIndex.builder()
                .from(currentToolsIndex)
                .jdkVersions(jdkVersions)
                .jdkDistributionSynonyms(jdkDistributionSynonyms)
                .build();
    }

    private DiscoApiDistribution fetchDistributionInfo(String distributionId) {
        return discoApiClient.getDistributions(distributionId).getResult().stream().findFirst().orElseThrow();
    }

    private Map<OperatingSystem, String> fetchDownloadUrls(String distributionId, String distributionVersion) {
        Map<OperatingSystem, String> downloadUrls = new HashMap<>();

        for (OperatingSystem operatingSystem : OperatingSystem.values()) {

            DiscoApiJdkPackage jdkPackage = discoApiClient.getJdkPackages(
                            distributionVersion,
                            distributionId,
                            "x64",
                            getDiscoApiOperatingSystemParameter(operatingSystem),
                            getDiscoApiArchiveTypeParameter(operatingSystem),
                            RELEASE_TYPE_GENERAL_ACCESS).getResult()
                    .stream()
                    .findFirst()
                    .orElse(null);

            if (jdkPackage == null) {
                continue;
            }

            DiscoApiJdkPackageDownloadInfo downloadInfo = discoApiClient.getJdkPackageDownloadInfo(jdkPackage.getEphemeralId()).getResult().stream().findFirst().orElse(null);
            if (downloadInfo == null) {
                continue;
            }

            downloadUrls.put(operatingSystem, downloadInfo.getDirectDownloadUri());
        }

        return downloadUrls;
    }

    private String getDiscoApiArchiveTypeParameter(OperatingSystem operatingSystem) {
        if (operatingSystem == OperatingSystem.MACOS || operatingSystem == OperatingSystem.LINUX) {
            return "tar.gz";
        } else if (operatingSystem == OperatingSystem.WINDOWS) {
            return "zip";
        }

        throw new IllegalArgumentException("unsupported OS: " + operatingSystem);
    }

    private String getDiscoApiOperatingSystemParameter(OperatingSystem operatingSystem) {
        return operatingSystem.name().toLowerCase();
    }

}
