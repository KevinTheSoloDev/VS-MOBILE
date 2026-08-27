package git.artdeell.dnbootstrap;

import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import git.artdeell.dnbootstrap.assets.AppDirs;
import git.artdeell.dnbootstrap.utils.SymlinkUtil;

public class DotnetStarter {

    private static final String TAG = "DotnetStarter";

    /*
     * The bundled runtime is Mono, not CoreCLR -- tune it through Mono's knobs.
     *
     * assets/dotnet-runtime.tgz ships libcoreclr.so 8.0.22 (arm64) that is
     * actually a Mono build (source paths inside it read
     * /home/maks/dotnet/runtime-8.0/src/mono). Verified against the binary:
     *
     *   exports  mono_gc_*, mono_aot_*, mono_interp_*
     *   contains SGen strings (GC_MAJOR / GC_MINOR / GC_NEW_BRIDGE)
     *   contains zero CoreCLR JIT symbols
     *            (ICorJitCompiler, corjit, WKS::gc_heap,
     *             TieredCompilation, OnStackReplacement -> 0 hits)
     *   recognises MONO_ENV_OPTIONS
     *
     * Consequence: every DOTNET_GC*, DOTNET_Tiered*, DOTNET_TC*,
     * DOTNET_EnableHWIntrinsic and DOTNET_JitEnableArm64Simd variable is
     * silently ignored by this runtime. Only the diagnostics variables below
     * are actually read by Mono.
     *
     * Each token here was confirmed present in that exact libcoreclr.so:
     *   --gc=[sgen,boehm]
     *   --gc-params= nursery-size=N | minor=[simple,split]
     *                major=[marksweep,marksweep-conc,marksweep-par]
     *   --optimize=  inline cfold deadce consprop copyprop fcmov leaf loop
     *                float32 simd abcrem ssapre
     *
     * LLVM is NOT available in this build: `nm -D` shows no LLVMInitialize*
     * symbols, only mono_set_use_llvm/mono_use_llvm, and the binary carries
     * "--aot=llvm requires a runtime compiled with llvm support." So --llvm is
     * deliberately not passed.
     *
     * These are startup tuning knobs. Measure them; do not trust them.
     */
    private static final String DEFAULT_MONO_ENV_OPTIONS =
            "--gc=sgen"
            + " --gc-params=nursery-size=16m,minor=split,major=marksweep-conc"
            + " --optimize=inline,cfold,deadce,consprop,copyprop,fcmov,leaf,loop,float32,simd,abcrem,ssapre";

    /**
     * Escape hatch: drop {@code mono-env.txt} into the app's files dir and its
     * first non-empty, non-# line replaces the defaults entirely. A single blank
     * line disables runtime tuning. Lets people tune without rebuilding the APK.
     */
    private static final String MONO_ENV_OVERRIDE_FILE = "mono-env.txt";

    private static File findCertsDir() {
        File certsDir = new File("/apex/com.android.conscrypt/cacerts/");
        if(certsDir.exists()) return certsDir;
        certsDir = new File("/system/etc/security/cacerts");
        if(certsDir.exists()) return certsDir;
        return null;
    }

    private static String readMonoEnvOverride(AppDirs appDirs) {
        File override = new File(appDirs.base, MONO_ENV_OVERRIDE_FILE);
        if(!override.isFile()) return null;
        try(BufferedReader reader = new BufferedReader(new FileReader(override))) {
            String line;
            while((line = reader.readLine()) != null) {
                line = line.trim();
                if(line.isEmpty() || line.startsWith("#")) continue;
                return line;
            }
        }catch (IOException e) {
            Log.w(TAG, "Could not read " + MONO_ENV_OVERRIDE_FILE + ", using defaults", e);
            return null;
        }
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

            // ── Mono runtime tuning (the only mechanism this runtime honours) ──
            String monoEnvOptions = readMonoEnvOverride(appDirs);
            if(monoEnvOptions == null) {
                monoEnvOptions = DEFAULT_MONO_ENV_OPTIONS;
                Log.i(TAG, "MONO_ENV_OPTIONS (default): " + monoEnvOptions);
            } else {
                Log.i(TAG, "MONO_ENV_OPTIONS (override): " + monoEnvOptions);
            }

            // Pick up AOT images if tools/aot/build-aot.sh produced them.
            // Mono silently ignores images whose version or dependency GUIDs do
            // not match, so a stale or empty directory is harmless.
            File aotDir = new File(trueVsDir, "aot");
            if(aotDir.isDirectory()) {
                File[] images = aotDir.listFiles((d, n) -> n.endsWith(".so"));
                if(images != null && images.length > 0) {
                    monoEnvOptions += " --aot-path=" + aotDir.getAbsolutePath();
                    Log.i(TAG, "AOT images found: " + images.length + " in " + aotDir);
                } else {
                    Log.i(TAG, "aot/ exists but holds no .so files; not passing --aot-path");
                }
            }

            if(!monoEnvOptions.isEmpty()) Os.setenv("MONO_ENV_OPTIONS", monoEnvOptions, true);

            // Cooperative suspension avoids the signal-based preemption path,
            // which costs extra syscalls at every GC safepoint.
            Os.setenv("MONO_THREADS_SUSPEND", "coop", true);

            // ── Diagnostics: these ARE read by Mono (verified in libcoreclr.so) ──
            Os.setenv("DOTNET_EnableDiagnostics", "0", true);
            Os.setenv("DOTNET_EnableEventPipe", "0", true);
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
