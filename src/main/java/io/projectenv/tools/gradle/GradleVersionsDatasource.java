package io.projectenv.tools.gradle;

import io.projectenv.tools.ImmutableToolsIndexV2;
import io.projectenv.tools.SortedCollections;
import io.projectenv.tools.ToolsIndexExtender;
import io.projectenv.tools.ToolsIndexV2;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.SortedMap;
import java.util.stream.Collectors;

public class GradleVersionsDatasource implements ToolsIndexExtender {

    @Override
    public ToolsIndexV2 extendToolsIndex(ToolsIndexV2 currentToolsIndex) {
        try {
            Document doc = Jsoup.connect("https://gradle.org/releases/").get();
            SortedMap<String, String> downloadUrls = doc.getElementsByClass("resources-contents")
                    .stream()
                    .flatMap(element -> element.children().stream())
                    .filter(element -> "a".equals(element.tagName()) && element.hasAttr("name"))
                    .map(element -> element.attr("name"))
                    .collect(Collectors.toMap(
                            version -> version,
                            version -> MessageFormat.format("https://downloads.gradle.org/distributions/gradle-{0}-bin.zip", version),
                            (a, b) -> a,
                            SortedCollections::createSemverSortedMap));

            return ImmutableToolsIndexV2.builder()
                    .from(currentToolsIndex)
                    .gradleVersions(downloadUrls)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
