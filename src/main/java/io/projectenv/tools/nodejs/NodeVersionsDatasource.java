package io.projectenv.tools.nodejs;

import io.projectenv.core.commons.system.OperatingSystem;
import io.projectenv.tools.ImmutableToolsIndex;
import io.projectenv.tools.SortedCollections;
import io.projectenv.tools.ToolsIndex;
import io.projectenv.tools.ToolsIndexExtender;
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
    public ToolsIndex extendToolsIndex(ToolsIndex currentToolsIndex) {
        try {
            Document doc = Jsoup.connect("https://nodejs.org/download/release/").get();

            SortedMap<String, SortedMap<OperatingSystem, String>> downloadUrls = doc.getElementsByTag("a")
                    .stream()
                    .map(element -> element.attr("href"))
                    .map(VERSION_PATTERN::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> matcher.group(1))
                    .collect(Collectors.toMap(
                            version -> version,
                            version -> SortedCollections.createNaturallySortedMap(Map.of(
                                    OperatingSystem.MACOS, MessageFormat.format("https://nodejs.org/dist/v{0}/node-v{0}-darwin-x64.tar.xz", version),
                                    OperatingSystem.LINUX, MessageFormat.format("https://nodejs.org/dist/v{0}/node-v{0}-linux-x64.tar.xz", version),
                                    OperatingSystem.WINDOWS, MessageFormat.format("https://nodejs.org/dist/v{0}/node-v{0}-win-x64.zip", version)
                            )),
                            (a, b) -> a,
                            SortedCollections::createSemverSortedMap));

            return ImmutableToolsIndex.builder()
                    .from(currentToolsIndex)
                    .nodeVersions(downloadUrls)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
