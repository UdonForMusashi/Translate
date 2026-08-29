package dev.translate.installer.privileged;

import android.os.ParcelFileDescriptor;
import dev.translate.installer.privileged.IInstallProgressCallback;

interface IPrivilegedInstallerService {
    void destroy() = 16777114;
    int probe(int profileCode, long requiredBytes) = 1;
    int installFile(int profileCode, String fileName,
                    in ParcelFileDescriptor source, long size, String sha256,
                    IInstallProgressCallback callback) = 2;
    int verifyInstalled(int profileCode, in String[] fileNames,
                        in long[] sizes, in String[] sha256) = 3;
    int uninstall(int profileCode, in String[] fileNames,
                  in long[] sizes, in String[] sha256,
                  IInstallProgressCallback callback) = 4;
}
