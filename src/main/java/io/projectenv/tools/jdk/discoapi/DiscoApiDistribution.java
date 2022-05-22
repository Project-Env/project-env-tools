package io.projectenv.tools.jdk.discoapi;

import org.immutables.gson.Gson;
import org.immutables.value.Value;

import java.util.List;

@Gson.TypeAdapters(fieldNamingStrategy = true)
@Value.Immutable
public interface DiscoApiDistribution {

    String getName();

    String getApiParameter();

    List<String> getSynonyms();

    List<String> getVersions();

}
