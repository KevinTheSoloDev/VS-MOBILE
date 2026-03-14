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
            Os.setenv("DOTNET_GCServer", "0", true);         // workstation GC — server GC spins threads 24/7, too hot for mobile
            Os.setenv("DOTNET_GCConcurrent", "1", true);
            Os.setenv("DOTNET_GCHeapCount", "2", true);
            Os.setenv("DOTNET_GCRegionsView", "0", true);
            Os.setenv("DOTNET_GCLatencyLevel", "1", true);   // low-latency GC mode — shorter pauses, better frame pacing

            // ── Threading ──────────────────────────────────────────────────
            Os.setenv("DOTNET_Thread_UseAllCpuGroups", "0", true);
            Os.setenv("DOTNET_ThreadPool_UnfairSemaphoreSpinLimit", "0", true);
            Os.setenv("DOTNET_ThreadPool_MinThreads", "4", true);  // VS uses threads for chunk gen/lighting; ensure enough are ready

            // ── Tiered JIT / startup ───────────────────────────────────────
            Os.setenv("DOTNET_TieredCompilation", "1", true);
            Os.setenv("DOTNET_TieredPGO", "1", true);
            Os.setenv("DOTNET_ReadyToRun", "1", true);
            Os.setenv("DOTNET_TC_QuickJitForLoops", "1", true);  // faster JIT for loop-heavy code (chunk gen, lighting, noise)

            // ── ARM64 SIMD / hardware intrinsics ──────────────────────────
            Os.setenv("DOTNET_EnableHWIntrinsic", "1", true);      // enable ARM hardware intrinsics
            Os.setenv("DOTNET_JitEnableArm64Simd", "1", true);     // explicit ARM64 NEON SIMD in JIT — faster math, noise, lighting

            // ── Diagnostics ────────────────────────────────────────────────
            Os.setenv("DOTNET_EnableDiagnostics", "0", true);
            Os.setenv("COMPlus_PerfMapEnabled", "0", true);        // disable perf map file generation
            Os.setenv("COMPlus_EnableEventLog", "0", true);        // disable event log overhead
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
