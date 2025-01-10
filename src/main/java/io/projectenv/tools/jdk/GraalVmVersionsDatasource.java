package io.projectenv.tools.jdk;

import io.projectenv.tools.*;
import io.projectenv.tools.jdk.github.GithubClient;
import io.projectenv.tools.jdk.github.Release;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class GraalVmVersionsDatasource implements ToolsIndexExtender {

    private static final String DISTRIBUTION_ID_BASE_NAME = "graalvm_ce";
    private static final Pattern DISTRIBUTION_ID_PATTERN = Pattern.compile("^" + DISTRIBUTION_ID_BASE_NAME + "(\\d+)$");

    private static final Pattern RELEASE_ASSET_NAME_PATTERN = Pattern.compile("graalvm-(?:ce|community)-(?:java|jdk-)(\\d+)[^-_]*[-_](\\w+)-(amd64|x64|aarch64)[-_](?:[\\d.]+|bin)\\.(?:tar\\.gz|zip)$");

    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile("JAVA_VERSION=\"?([\\d.]+)\"?");
    private static final Pattern GRAALVM_VERSION_PATTERN = Pattern.compile("GRAALVM_VERSION=\"?([\\d.]+)\"?");

    private static final String GRAAL_VM_VERSION_FILE_PATH = "/release";

    private static final Set<String> SYNONYMS_BASES = Set.of(
            "Graal VM CE ",
            "graalvm_ce",
            "graalvmce",
            "GraalVM CE ",
            "GraalVMCE",
            "GraalVM_CE"
    );

    private final GithubClient githubClient;

    public GraalVmVersionsDatasource(GithubClient githubClient) {
        this.githubClient = githubClient;
    }

    @Override
    public ToolsIndexV2 extendToolsIndex(ToolsIndexV2 currentToolsIndex) {
        SortedMap<String, SortedSet<String>> jdkDistributionSynonyms = SortedCollections.createNaturallySortedMap(currentToolsIndex.getJdkDistributionSynonyms());
        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>>> jdkVersions = SortedCollections.createNaturallySortedMap(currentToolsIndex.getJdkVersions());

        var releases = githubClient.getReleases("graalvm", "graalvm-ce-builds")
                .stream()
                .sorted(Comparator.comparing(Release::getTagName))
                .toList();

        for (var release : releases) {
            var downloadUrls = new HashMap<String, Map<OperatingSystem, Map<CpuArchitecture, String>>>();
            for (var releaseAsset : release.getAssets()) {
                var releaseAssetNameMatcher = RELEASE_ASSET_NAME_PATTERN.matcher(releaseAsset.getName());
                if (!releaseAssetNameMatcher.find()) {
                    ProcessOutput.writeInfoMessage("skipping unknown release asset name {0}", releaseAsset.getName());
                    continue;
                }

                var javaMajorVersion = releaseAssetNameMatcher.group(1);
                var operatingSystem = mapToOperatingSystem(releaseAssetNameMatcher.group(2));
                var cpuArchitecture = mapToCpuArchitecture(releaseAssetNameMatcher.group(3));

                downloadUrls
                        .computeIfAbsent(javaMajorVersion, key -> new EnumMap<>(OperatingSystem.class))
                        .computeIfAbsent(operatingSystem, key -> new EnumMap<>(CpuArchitecture.class))
                        .put(cpuArchitecture, releaseAsset.getBrowserDownloadUrl());
            }

            if (downloadUrls.isEmpty()) {
                continue;
            }

            String firstWindowsDownloadUrl = downloadUrls.entrySet().stream().findFirst().map(entry -> entry.getValue().get(OperatingSystem.WINDOWS).get(CpuArchitecture.AMD64)).orElseThrow();
            DistributionInfo distributionInfo = extractDistributionInfo(firstWindowsDownloadUrl);

            for (Map.Entry<String, Map<OperatingSystem, Map<CpuArchitecture, String>>> entry : downloadUrls.entrySet()) {
                var distributionName = DISTRIBUTION_ID_BASE_NAME + entry.getKey();

                for (Map.Entry<OperatingSystem, Map<CpuArchitecture, String>> osEntry : entry.getValue().entrySet()) {
                    jdkVersions
                            .computeIfAbsent(distributionName, key -> SortedCollections.createSemverSortedMap())
                            .computeIfAbsent(distributionInfo.graalVmVersion(), key -> SortedCollections.createNaturallySortedMap())
                            .computeIfAbsent(osEntry.getKey(), key -> SortedCollections.createNaturallySortedMap())
                            .putAll(osEntry.getValue());
                }
            }

        }

        for (var distributionId : jdkVersions.keySet()) {
            var distributionIdMatcher = DISTRIBUTION_ID_PATTERN.matcher(distributionId);
            if (!distributionIdMatcher.find()) {
                continue;
            }

            var majorJavaVersion = distributionIdMatcher.group(1);
            jdkDistributionSynonyms.put(distributionId, SYNONYMS_BASES.stream()
                    .map(synonymBaseName -> synonymBaseName + majorJavaVersion)
                    .collect(SortedCollections.toNaturallySortedSet()));
        }

        return ImmutableToolsIndexV2.builder()
                .from(currentToolsIndex)
                .jdkVersions(jdkVersions)
                .jdkDistributionSynonyms(jdkDistributionSynonyms)
                .build();
    }

    private OperatingSystem mapToOperatingSystem(String operatingSystemName) {
        return switch (operatingSystemName) {
            case "darwin" -> OperatingSystem.MACOS;
            default -> OperatingSystem.valueOf(operatingSystemName.toUpperCase());
        };
    }

    private CpuArchitecture mapToCpuArchitecture(String cpuArchitectureName) {
        return switch (cpuArchitectureName) {
            case "aarch64" -> CpuArchitecture.AARCH64;
            default -> CpuArchitecture.AMD64;
        };
    }

    private DistributionInfo extractDistributionInfo(String windowsDistributionUrl) {
        try {
            URL zipUrl = new URL(windowsDistributionUrl);
            try (InputStream in = zipUrl.openStream();
                 ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(in))) {

                ZipEntry entry;
                while ((entry = zipIn.getNextEntry()) != null) {
                    if (entry.getName().endsWith(GRAAL_VM_VERSION_FILE_PATH)) {

                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        ByteArrayOutputStream fos = new ByteArrayOutputStream();
                        while ((bytesRead = zipIn.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                        fos.close();

                        String versionFileContent = fos.toString(StandardCharsets.UTF_8);

                        var versionMatcher = JAVA_VERSION_PATTERN.matcher(versionFileContent);
                        if (!versionMatcher.find()) {
                            versionMatcher = GRAALVM_VERSION_PATTERN.matcher(versionFileContent);
                            if (!versionMatcher.find()) {
                                throw new IllegalStateException("found release file, but could not extract GraalVM version");
                            }
                        }

                        String graalVmVersion = versionMatcher.group(1);

                        return new DistributionInfo(graalVmVersion);
                    }
                }
            }

            throw new IllegalStateException("could not find release file");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String extractJavaMajorVersionFromUrl(String windowsDistributionUrl) {
        var releaseAssetNameMatcher = RELEASE_ASSET_NAME_PATTERN.matcher(windowsDistributionUrl);
        if (!releaseAssetNameMatcher.find()) {
            throw new IllegalStateException("could not extract Java major version from URL " + windowsDistributionUrl);
        }

        return releaseAssetNameMatcher.group(1);
    }

    record DistributionInfo(String graalVmVersion) {
    }

}
