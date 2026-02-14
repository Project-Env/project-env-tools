package io.projectenv.tools.github;

import org.immutables.gson.Gson;
import org.immutables.value.Value;

import java.util.List;

@Gson.TypeAdapters(fieldNamingStrategy = true)
@Value.Immutable
public interface Release {

    String getTagName();

    @Value.Default
    default boolean isPrerelease() {
        return false;
    }

    List<ReleaseAsset> getAssets();

}
