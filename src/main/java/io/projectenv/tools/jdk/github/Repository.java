package io.projectenv.tools.jdk.github;

import org.immutables.gson.Gson;
import org.immutables.value.Value;

@Gson.TypeAdapters(fieldNamingStrategy = true)
@Value.Immutable
public interface Repository {

    String getName();

}
