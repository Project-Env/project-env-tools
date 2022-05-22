package io.projectenv.tools.jdk.discoapi;

import org.immutables.gson.Gson;
import org.immutables.value.Value;

@Gson.TypeAdapters(fieldNamingStrategy = true)
@Value.Immutable
public interface DiscoApiResult<T> {

    T getResult();

}
