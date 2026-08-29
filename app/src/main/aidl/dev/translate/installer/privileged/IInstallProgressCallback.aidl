package dev.translate.installer.privileged;

oneway interface IInstallProgressCallback {
    void onProgress(int phase, String fileName, long processed, long total);
}
