package io.projectenv.tools.nodejs;

import io.projectenv.tools.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Map;
import java.util.SortedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NodeVersionsDatasource implements ToolsIndexExtender {

    private static final Pattern VERSION_PATTERN = Pattern.compile("v(\\d+\\.\\d+\\.\\d+)/");

    @Override
    public ToolsIndexV2 extendToolsIndex(ToolsIndexV2 currentToolsIndex) {
        try {
            Document doc = Jsoup.connect("https://nodejs.org/download/release/").get();

            SortedMap<String, SortedMap<OperatingSystem, SortedMap<CpuArchitecture, String>>> downloadUrls = doc.getElementsByTag("a")
                    .stream()
                    .map(element -> element.attr("href"))
                    .map(VERSION_PATTERN::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> matcher.group(1))
                    .collect(Collectors.toMap(
                            version -> version,
                            version -> SortedCollections.createNaturallySortedMap(Map.of(
                                    OperatingSystem.MACOS, SortedCollections.createNaturallySortedMap(Map.of(
                                            CpuArchitecture.AMD64, MessageFormat.format("https://nodejs.org/dist/v{0}/node-v{0}-darwin-x64.tar.xz", version),
                                            CpuArchitecture.AARCH64, MessageFormat.format("https://nodejs.org/dist/v{0}/node-v{0}-darwin-arm64.tar.xz", version)
                                    )),
                                    OperatingSystem.LINUX, SortedCollections.createNaturallySortedMap(Map.of(
                                            CpuArchitecture.AMD64, MessageFormat.format("https://nodejs.org/dist/v{0}/node-v{0}-linux-x64.tar.xz", version),
                                            CpuArchitecture.AARCH64, MessageFormat.format("https://nodejs.org/dist/v{0}/node-v{0}-linux-arm64.tar.xz", version)
                                    )),
                                    OperatingSystem.WINDOWS, SortedCollections.createNaturallySortedMap(Map.of(
                                            CpuArchitecture.AMD64, MessageFormat.format("https://nodejs.org/dist/v{0}/node-v{0}-win-x64.zip", version)
                                    ))
                            )),
                            (a, b) -> a,
                            SortedCollections::createSemverSortedMap));

            return ImmutableToolsIndexV2.builder()
                    .from(currentToolsIndex)
                    .nodeVersions(downloadUrls)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
