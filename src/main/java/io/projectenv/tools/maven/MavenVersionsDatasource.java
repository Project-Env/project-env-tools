package io.projectenv.tools.maven;

import io.projectenv.tools.ImmutableToolsIndex;
import io.projectenv.tools.ToolsIndex;
import io.projectenv.tools.ToolsIndexExtender;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MavenVersionsDatasource implements ToolsIndexExtender {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+)/");

    @Override
    public ToolsIndex extendToolsIndex(ToolsIndex currentToolsIndex) {
        try {
            Document doc = Jsoup.connect("https://archive.apache.org/dist/maven/maven-3/").get();
            Map<String, String> downloadUrls = doc.getElementsByTag("a")
                    .stream()
                    .map(element -> element.attr("href"))
                    .map(VERSION_PATTERN::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> matcher.group(1))
                    .collect(Collectors.toMap(
                            version -> version,
                            version -> MessageFormat.format("https://archive.apache.org/dist/maven/maven-3/{0}/binaries/apache-maven-{0}-bin.zip", version),
                            (a, b) -> a,
                            TreeMap::new));

            return ImmutableToolsIndex.builder()
                    .from(currentToolsIndex)
                    .mavenVersions(downloadUrls)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
