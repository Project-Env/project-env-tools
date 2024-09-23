package io.projectenv.tools.jdk.github.impl;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.projectenv.tools.ProcessOutput;
import io.projectenv.tools.jdk.github.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.*;

public class SimpleGithubClient implements GithubClient {

    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    private final String authorizationHeader;

    private SimpleGithubClient(String authorizationHeader) {
        this.authorizationHeader = authorizationHeader;
    }

    public static SimpleGithubClient withAccessToken(String accessToken) {
        var authorizationHeader = "Bearer " + accessToken;

        return new SimpleGithubClient(authorizationHeader);
    }

    public static SimpleGithubClient withUsernamePassword(String username, String password) {
        var authorizationHeader = "Basic " + Base64.getEncoder().encodeToString(String.join(":", username, password).getBytes(StandardCharsets.UTF_8));

        return new SimpleGithubClient(authorizationHeader);
    }

    @Override
    public List<Release> getReleases(String owner, String repo) {
        int page = 0;

        List<Release> releases = new ArrayList<>();

        List<Release> releasesPage;
        do {
            releasesPage = callApi(formatUrl("https://api.github.com/repos/{0}/{1}/releases?page={2}", owner, repo, page), new TypeToken<List<Release>>() {
            }.getType());
            releases.addAll(releasesPage);
            page++;
        } while (!releasesPage.isEmpty());

        return releases;
    }

    @Override
    public List<Repository> getRepositories(String owner) {
        int page = 0;

        List<Repository> repositories = new ArrayList<>();

        List<Repository> repositoriesPage;
        do {
            repositoriesPage = callApi(formatUrl("https://api.github.com/orgs/{0}/repos?page={1}", owner, page), new TypeToken<List<Repository>>() {
            }.getType());
            repositories.addAll(repositoriesPage);
            page++;
        } while (!repositoriesPage.isEmpty());

        return repositories;
    }

    private String formatUrl(String url, Object... parameters) {
        String[] encodedParameters = Arrays.stream(parameters)
                .map(Objects::toString)
                .map(parameter -> URLEncoder.encode(parameter, StandardCharsets.UTF_8))
                .toArray(String[]::new);

        return MessageFormat.format(url, (Object[]) encodedParameters);
    }

    private <T> T callApi(String uri, Type responseType) {
        try {
            ProcessOutput.writeDebugMessage("calling Github API with URL {0}", uri);

            var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("Authorization", authorizationHeader)
                    .timeout(FIVE_MINUTES)
                    .GET()
                    .build();

            HttpResponse<InputStream> response = createHttpClient().send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Received a non expected status code " + response.statusCode() + " from Github API with URL " + uri);
            }

            try (Reader reader = new InputStreamReader(response.body())) {
                return createGson().fromJson(reader, responseType);
            }
        } catch (InterruptedException | IOException e) {
            Thread.currentThread().interrupt();

            throw new RuntimeException(e);
        }
    }

    private HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(FIVE_MINUTES)
                .build();
    }

    private static Gson createGson() {
        return new GsonBuilder()
                .registerTypeAdapterFactory(new GsonAdaptersRelease())
                .registerTypeAdapterFactory(new GsonAdaptersReleaseAsset())
                .registerTypeAdapterFactory(new GsonAdaptersRepository())
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();
    }

}
