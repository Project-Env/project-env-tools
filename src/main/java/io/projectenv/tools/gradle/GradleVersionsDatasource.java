package io.projectenv.tools.gradle;

import io.projectenv.tools.ImmutableToolsIndex;
import io.projectenv.tools.ToolsIndex;
import io.projectenv.tools.ToolsIndexExtender;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class GradleVersionsDatasource implements ToolsIndexExtender {

    @Override
    public ToolsIndex extendToolsIndex(ToolsIndex currentToolsIndex) {
        try {
            Document doc = Jsoup.connect("https://gradle.org/releases/").get();
            Map<String, String> downloadUrls = doc.getElementsByClass("resources-contents")
                    .stream()
                    .flatMap(element -> element.children().stream())
                    .filter(element -> "a".equals(element.tagName()) && element.hasAttr("name"))
                    .map(element -> element.attr("name"))
                    .sorted()
                    .collect(Collectors.toMap(
                            version -> version,
                            version -> MessageFormat.format("https://downloads.gradle-dn.com/distributions/gradle-{0}-bin.zip", version),
                            (a, b) -> a,
                            LinkedHashMap::new));

            return ImmutableToolsIndex.builder()
                    .from(currentToolsIndex)
                    .gradleVersions(downloadUrls)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
