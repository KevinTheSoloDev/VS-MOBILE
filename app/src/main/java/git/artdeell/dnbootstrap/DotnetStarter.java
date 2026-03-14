package git.artdeell.dnbootstrap;

import android.system.Os;

import java.io.File;
import java.io.IOException;

import git.artdeell.dnbootstrap.assets.AppDirs;
import git.artdeell.dnbootstrap.utils.SymlinkUtil;

public class DotnetStarter {

    private static File findCertsDir() {
        File certsDir = new File("/apex/com.android.conscrypt/cacerts/");
        if(certsDir.exists()) return certsDir;
        certsDir = new File("/system/etc/security/cacerts");
        if(certsDir.exists()) return certsDir;
        return null;
    }

    public static void kickstart(AppDirs appDirs, File appNativeDir) throws IOException {

        File homeDir = new File(appDirs.base, "home");
        File certsDir = findCertsDir();

        if(certsDir == null) throw new IOException("Cannot start: can't find HTTPS certificate directory");
        File trueVsDir = new File(appDirs.vs, "vintagestory");

        try {
            Os.setenv("HOME", homeDir.getAbsolutePath(), true);
            Os.setenv("FONTCONFIG_PATH", appDirs.fontconfig.getAbsolutePath(), true);
            Os.setenv("SSL_CERT_DIR", certsDir.getAbsolutePath(), true);
            Os.setenv("LIBGL_NOERROR", "1", true);

            // ── GC tuning ──────────────────────────────────────────────────
            Os.setenv("DOTNET_GCHeapHardLimit", "536870912", true);
            Os.setenv("DOTNET_GCConserveMemory", "5", true);
            Os.setenv("DOTNET_GCHighMemPercent", "65", true);
            Os.setenv("DOTNET_GCServer", "0", true);
            Os.setenv("DOTNET_GCConcurrent", "1", true);
            Os.setenv("DOTNET_GCHeapCount", "2", true);
            Os.setenv("DOTNET_GCRegionsView", "0", true);

            // ── Threading ──────────────────────────────────────────────────
            Os.setenv("DOTNET_Thread_UseAllCpuGroups", "0", true);
            Os.setenv("DOTNET_ThreadPool_UnfairSemaphoreSpinLimit", "0", true);

            // ── Tiered JIT / startup ───────────────────────────────────────
            Os.setenv("DOTNET_TieredCompilation", "1", true);
            Os.setenv("DOTNET_TieredPGO", "1", true);
            Os.setenv("DOTNET_ReadyToRun", "1", true);

            // ── Diagnostics ────────────────────────────────────────────────
            Os.setenv("DOTNET_EnableDiagnostics", "0", true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        SymlinkUtil symlinkUtil = new SymlinkUtil(trueVsDir, appNativeDir);

        symlinkUtil.symlinkLibrary("libopenal.so", "libopenal.so.1");
        symlinkUtil.symlinkLibrary("libcairo.so", "libcairo.so.2");

        MainActivity.runDotnet(appDirs.runtime.getAbsolutePath(), trueVsDir.getAbsolutePath());
        System.exit(0);
    }
}
