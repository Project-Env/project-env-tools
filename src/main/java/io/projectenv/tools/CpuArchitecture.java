package io.projectenv.tools;

import com.google.gson.annotations.SerializedName;
import org.apache.commons.lang3.SystemUtils;

public enum CpuArchitecture {

    @SerializedName("amd64")
    AMD64,
    @SerializedName("aarch64")
    AARCH64;

    public static CpuArchitecture getCurrentCpuArchitecture() {
        try {
            return valueOf(SystemUtils.OS_ARCH.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("unsupported CPU architecture " + SystemUtils.OS_ARCH);
        }
    }

}
