package io.projectenv.tools.jdk.discoapi;

import java.util.List;

public interface DiscoApiClient {

    DiscoApiResult<List<DiscoApiDistribution>> getDistributions(String distro);

    DiscoApiResult<List<DiscoApiJdkPackage>> getJdkPackages(String version, String distro, String architecture, String operatingSystem, String archiveType, String releaseType);

    DiscoApiResult<List<DiscoApiJdkPackageDownloadInfo>> getJdkPackageDownloadInfo(String ephemeralId);

}
