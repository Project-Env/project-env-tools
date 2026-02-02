package io.projectenv.tools.jdk.github;

import org.immutables.gson.Gson;
import org.immutables.value.Value;

import java.util.List;

@Gson.TypeAdapters(fieldNamingStrategy = true)
@Value.Immutable
public interface Release {

    String getTagName();

    boolean isPrerelease();

    List<ReleaseAsset> getAssets();

}
