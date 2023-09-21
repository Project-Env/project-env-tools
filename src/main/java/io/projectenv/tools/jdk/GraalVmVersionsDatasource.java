package io.projectenv.tools.jdk;

import io.projectenv.core.commons.process.ProcessOutput;
import io.projectenv.core.commons.system.OperatingSystem;
import io.projectenv.tools.ImmutableToolsIndex;
import io.projectenv.tools.SortedCollections;
import io.projectenv.tools.ToolsIndex;
import io.projectenv.tools.ToolsIndexExtender;
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

    private static final Pattern RELEASE_ASSET_NAME_PATTERN = Pattern.compile("graalvm-(?:ce|community)-(?:java|jdk-)(\\d+)[^-_]*[-_](\\w+)-(?:amd64|x64)[-_](?:[\\d.]+|bin)\\.(?:tar\\.gz|zip)$");

    private static final Pattern GRAAL_VM_VERSION_PATTERN = Pattern.compile("GRAALVM_VERSION=\"?([\\d.]+)\"?");

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
    public ToolsIndex extendToolsIndex(ToolsIndex currentToolsIndex) {
        SortedMap<String, SortedSet<String>> jdkDistributionSynonyms = SortedCollections.createNaturallySortedMap(currentToolsIndex.getJdkDistributionSynonyms());
        SortedMap<String, SortedMap<String, SortedMap<OperatingSystem, String>>> jdkVersions = SortedCollections.createNaturallySortedMap(currentToolsIndex.getJdkVersions());

        var releases = githubClient.getReleases("graalvm", "graalvm-ce-builds")
                .stream()
                .sorted(Comparator.comparing(Release::getTagName))
                .toList();

        for (var release : releases) {
            var downloadUrls = new HashMap<String, Map<OperatingSystem, String>>();
            for (var releaseAsset : release.getAssets()) {
                var releaseAssetNameMatcher = RELEASE_ASSET_NAME_PATTERN.matcher(releaseAsset.getName());
                if (!releaseAssetNameMatcher.find()) {
                    ProcessOutput.writeInfoMessage("skipping unknown release asset name {0}", releaseAsset.getName());
                    continue;
                }

                var javaMajorVersion = releaseAssetNameMatcher.group(1);
                var operatingSystem = mapToOperatingSystem(releaseAssetNameMatcher.group(2));

                downloadUrls
                        .computeIfAbsent(javaMajorVersion, key -> new EnumMap<>(OperatingSystem.class))
                        .put(operatingSystem, releaseAsset.getBrowserDownloadUrl());
            }

            if (downloadUrls.isEmpty()) {
                continue;
            }

            String firstWindowsDownloadUrl = downloadUrls.entrySet().stream().findFirst().map(entry -> entry.getValue().get(OperatingSystem.WINDOWS)).orElseThrow();
            DistributionInfo distributionInfo = extractDistributionInfo(firstWindowsDownloadUrl);

            for (Map.Entry<String, Map<OperatingSystem, String>> entry : downloadUrls.entrySet()) {
                var distributionName = DISTRIBUTION_ID_BASE_NAME + entry.getKey();

                jdkVersions
                        .computeIfAbsent(distributionName, key -> SortedCollections.createSemverSortedMap())
                        .computeIfAbsent(distributionInfo.graalVmVersion(), key -> SortedCollections.createNaturallySortedMap())
                        .putAll(entry.getValue());
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

        return ImmutableToolsIndex.builder()
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

                        var graalVmVersionMatcher = GRAAL_VM_VERSION_PATTERN.matcher(versionFileContent);
                        if (!graalVmVersionMatcher.find()) {
                            throw new IllegalStateException("found release file, but could not extract GraalVM version");
                        }
                        String graalVmVersion = graalVmVersionMatcher.group(1);

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
