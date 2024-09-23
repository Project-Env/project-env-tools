package io.projectenv.tools;

import com.google.gson.annotations.SerializedName;

public enum OperatingSystem {

    @SerializedName("macos")
    MACOS,
    @SerializedName("windows")
    WINDOWS,
    @SerializedName("linux")
    LINUX;

}