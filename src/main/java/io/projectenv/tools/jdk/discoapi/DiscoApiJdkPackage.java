package io.projectenv.tools.jdk.discoapi;

import org.immutables.gson.Gson;
import org.immutables.value.Value;

@Gson.TypeAdapters(fieldNamingStrategy = true)
@Value.Immutable
public interface DiscoApiJdkPackage {

    String getEphemeralId();

    String getReleaseStatus();

    String getArchiveType();

    String getOperatingSystem();

    String getLibCType();

    String getArchitecture();

}
