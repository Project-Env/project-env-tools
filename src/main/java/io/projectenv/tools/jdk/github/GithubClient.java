package io.projectenv.tools.jdk.github;

import java.util.List;

public interface GithubClient {

    List<Release> getReleases(String owner, String repo);

    List<Repository> getRepositories(String owner);

}
