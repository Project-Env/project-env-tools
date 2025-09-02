package io.projectenv.tools.maven;

import io.projectenv.tools.ImmutableToolsIndexV2;
import io.projectenv.tools.SortedCollections;
import io.projectenv.tools.ToolsIndexExtender;
import io.projectenv.tools.ToolsIndexV2;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.SortedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MavenVersionsDatasource implements ToolsIndexExtender {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+(?:-rc-\\d+|))/");
    private static final String ARCHIVE_BASE_URL = "https://archive.apache.org/dist/maven/maven-3/";
    private static final String DOWNLOADS_BASE_URL = "https://downloads.apache.org/maven/maven-3/";
    private static final String DOWNLOADS_BASE_URL_4 = "https://downloads.apache.org/maven/maven-4/";
    private static final String ARCHIVE_BASE_URL_4 = "https://archive.apache.org/dist/maven/maven-4/";

    private SortedMap<String, String> fetchVersions(String baseUrl) throws IOException {
        Document doc = Jsoup.connect(baseUrl).get();
        return doc.getElementsByTag("a")
                .stream()
                .map(element -> element.attr("href"))
                .map(VERSION_PATTERN::matcher)
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .collect(Collectors.toMap(
                        version -> version,
                        version -> MessageFormat.format(baseUrl + "{0}/binaries/apache-maven-{0}-bin.zip", version),
                        (a, b) -> a,
                        SortedCollections::createSemverSortedMap));
    }

    @Override
    public ToolsIndexV2 extendToolsIndex(ToolsIndexV2 currentToolsIndex) {
        try {
            SortedMap<String, String> downloadsUrls3 = fetchVersions(DOWNLOADS_BASE_URL);
            SortedMap<String, String> archiveUrls3 = fetchVersions(ARCHIVE_BASE_URL);
            SortedMap<String, String> downloadsUrls4 = fetchVersions(DOWNLOADS_BASE_URL_4);
            SortedMap<String, String> archiveUrls4 = fetchVersions(ARCHIVE_BASE_URL_4);

            SortedMap<String, String> merged = SortedCollections.createSemverSortedMap();
            merged.putAll(archiveUrls3);
            merged.putAll(archiveUrls4);
            merged.putAll(downloadsUrls3);
            merged.putAll(downloadsUrls4);

            return ImmutableToolsIndexV2.builder()
                    .from(currentToolsIndex)
                    .mavenVersions(merged)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
