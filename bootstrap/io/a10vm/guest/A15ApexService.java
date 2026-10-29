package io.a10vm.guest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android 15 (API 35) in-process implementation of the binder service named
 * {@code apexservice} ({@code android.apex.IApexService}).
 *
 * <h2>Why this class exists</h2>
 * SystemServer's first bootstrap step after {@code StartServices} is {@code PlatformCompat}.
 * That timing block runs {@code CompatConfig.loadConfigFiles()}, which calls
 * {@code com.android.server.pm.ApexManager.getInstance().getActiveApexInfos()}:
 *
 * <pre>
 *   ApexManagerImpl.getActiveApexInfos()
 *     -> waitForApexService()
 *          ServiceManager.waitForService("apexservice")      // blocks 1 s per retry
 *          Binder.allowBlocking(binder)
 *          IApexService.Stub.asInterface(binder)
 *     -> IApexService.getActivePackages() -> ApexInfo[]
 * </pre>
 *
 * Later, while {@code PackageManagerService} is being constructed, the same manager is
 * asked for the APEX scan partitions ({@code getApexScanPartitions()}, which is built from
 * {@code getActivePackages()} module directories), and — separately — for every APEX
 * <b>package</b> ({@code getAllPackages()}), whose {@code modulePath} goes verbatim into
 * {@code PackageParser2.parsePackage()}.
 *
 * <h2>Two lists, two shapes (round 56)</h2>
 * The two calls are not interchangeable; the image's own bytecode says so
 * ({@code services.jar!classes2.dex}, A15 {@code BP1A.250505.005.D1}):
 *
 * <pre>
 *   InitAppsHelper.scanApexPackagesTraced()
 *     -&gt; ApexManagerImpl.getAllApexInfos()      // = IApexService.getAllPackages(), 1:1
 *     -&gt; InstallPackageHelper.scanApexPackages(ApexInfo[], …)
 *          for (ApexInfo i : infos) { parser.submit(new File(i.modulePath), parseFlags); }
 *          // nothing is appended to modulePath; the result list is sorted by
 *          // ApexInfo.isFactory (stable), and the first failed parse throws
 *          //   IllegalStateException("Unable to parse: " + i.modulePath)
 * </pre>
 *
 * So {@code getAllPackages()} must return <b>one entry per directory that directly
 * contains a base APK</b> — {@code /apex/&lt;name&gt;/app|priv-app/&lt;Pkg&gt;@&lt;ver&gt;} — not the
 * module root. Rounds 54/55 proved the negative half of that: module roots produce
 * {@code Missing base APK in /apex/com.android.adbd} then
 * {@code … /apex/com.android.btservices}.
 *
 * {@code getActivePackages()} keeps returning the module roots: its consumers
 * ({@code CompatConfig}, {@code DexOptHelper}, {@code InitAppsHelper.getApexScanPartitions()})
 * need {@code /apex/&lt;name&gt;}, and CompatConfig reads
 * {@code /apex/&lt;name&gt;/etc/compatconfig} from exactly that path.
 *
 * <h2>Why it must return the real list (round 51 correction)</h2>
 * Round 45 returned empty arrays, which was enough for {@code PlatformCompat} but is
 * <b>not</b> enough for PackageManagerService: with an empty list no APEX {@code priv-app}
 * directory is scanned, so the image's {@code PermissionController} is invisible and PKMS
 * aborts with
 *
 * <pre>
 *   java.lang.RuntimeException: There must be exactly one permissions manager; found []
 *       at com.android.server.pm.PackageManagerService.getRequiredPermissionControllerLPr
 * </pre>
 *
 * This runtime does have 32 expanded APEX modules under {@code /apex/<name>} (payload
 * expanded on the host, staged as ordinary files), each with its own
 * {@code priv-app/} and {@code javalib/}. The truthful answer is therefore the list from
 * {@code /apex/apex-info-list.xml} — the same file {@code prepare-a15.py} synthesises for
 * libvintf. For {@code getActivePackages()} the module root is the right value; for
 * {@code getAllPackages()} it is the per-package directory (see above).
 *
 * <h2>What is still not implemented</h2>
 * Sessions/staging ({@code getSessions}, {@code getStagedApexInfos}) stay empty: nothing is
 * staged through apexd in this runtime. The APEX payloads are already unpacked, so apexd's
 * real work (mount / verify / unmount, loop devices, {@code /data/apex}) is deliberately
 * absent. Round 43 proved that running the image's real apexd next to a live SystemServer
 * is a dead end; do not go back there.
 *
 * <h2>Evidence this class is doing its job</h2>
 * {@code vm/framework-server.log} must contain {@code a15_apexservice_registered=1},
 * {@code a15_apexservice_infos=32 scan=<package dirs>}, no
 * {@code Waited one second for apexservice}, no {@code There must be exactly one
 * permissions manager; found []}, and no {@code Unable to parse: /apex/}.
 */
final class A15ApexService {
    /** apexd writes this file at boot; prepare-a15.py writes the same shape. */
    private static final String APEX_INFO_LIST = "/apex/apex-info-list.xml";
    private static final String ATTRIBUTE = "([A-Za-z0-9_]+)=\"([^\"]*)\"";
    /** Binary-XML attribute name of {@code android:sharedUserId}, both pool encodings. */
    private static final byte[] SHARED_USER_ID_ASCII =
            "sharedUserId".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] SHARED_USER_ID_UTF16 = {
            's', 0, 'h', 0, 'a', 0, 'r', 0, 'e', 0, 'd', 0, 'U', 0, 's', 0, 'e', 0, 'r', 0,
            'I', 0, 'd', 0};

    /**
     * Whether {@code getAllPackages()} answers with the per-package directories
     * (rounds 56-59) or with an empty list (round 60, the real-device shape).
     *
     * <p>Empty is correct here because APEX APKs are installed by the <b>partition</b> scan:
     * {@code InitAppsHelper.getApexScanPartitions()} turns every active apex into a
     * {@code ScanPartition} whose folder is {@code apexDirectory}, and that only works when
     * {@code preinstalledModulePath} is the real preinstalled path
     * {@code /system/apex/<name>.apex} (see {@link #preinstalledPath}). Round 57's probe
     * measured {@code a15_apex_scan_partitions=0/32} precisely because that field pointed at
     * {@code /apex/<name>}.
     *
     * <p>The per-package answer cannot coexist with that: it trips ApexManager's invariant
     * {@code "Two active packages have the same APEX module name"} (ApexManager.java:518-521)
     * for every module that bundles two APKs (adservices, cts.shim, permission, wifi, …),
     * which is what round 59 died on. Flip this constant back only if the partition scan is
     * ever proven not to run.
     */
    private static final boolean PACKAGE_SCAN = false;

    private A15ApexService() {
    }

    static void install() throws Exception {
        // @hide types: resolved through the boot class loader at runtime, never named
        // on javac's class path (android.jar has no android.apex package).
        Class<?> apexInfo = Class.forName("android.apex.ApexInfo");
        Class<?> sessionInfo = Class.forName("android.apex.ApexSessionInfo");
        // Two lists, because two very different consumers read them:
        //   getActivePackages -> ApexManagerImpl.getActiveApexInfos() -> CompatConfig /
        //                        DexOptHelper / getApexScanPartitions(): needs EVERY
        //                        module, addressed as /apex/<name> (it reads each
        //                        /apex/<name>/etc/compatconfig). Module root stays.
        //   getAllPackages    -> ApexManagerImpl.getAllApexInfos() ->
        //                        InitAppsHelper.scanApexPackagesTraced ->
        //                        InstallPackageHelper.scanApexPackages, which feeds each
        //                        modulePath verbatim to PackageParser2.parsePackage().
        //                        One entry per directory that directly contains a base
        //                        APK, i.e. /apex/<name>/app|priv-app/<Pkg>@<ver>.
        //                        (Rounds 54/55: module roots -> "Missing base APK".)
        Object all = PACKAGE_SCAN ? buildPackageDirs(apexInfo) : Array.newInstance(apexInfo, 0);
        Object active = build(apexInfo, false);
        Object noApexInfos = Array.newInstance(apexInfo, 0);
        Object noSessions = Array.newInstance(sessionInfo, 0);
        LocalGuestService.register("apexservice", "android.apex.IApexService",
                (proxy, method, arguments) ->
                        value(method.getName(), method, active, all, noApexInfos, noSessions));
        System.out.println("a15_apexservice_infos=" + Array.getLength(active)
                + " scan=" + Array.getLength(all));
    }

    private static Object value(String name, Method method, Object active, Object all,
            Object noApexInfos, Object noSessions) {
        switch (name) {
            // CompatConfig / DexOptHelper / getApexScanPartitions: every module root.
            case "getActivePackages":
                return active;
            // APEX package scan: one directory per bundled APK.
            case "getAllPackages":
                return all;
            // Nothing goes through apexd staging in this runtime.
            case "getStagedApexInfos":
                return noApexInfos;
            case "getSessions":
                return noSessions;
            default:
                return defaultValue(method.getReturnType());
        }
    }

    /**
     * Builds the {@code getActivePackages()} array: one {@code ApexInfo} per module, with
     * {@code modulePath=/apex/<name>}. That is the shape its consumers need
     * ({@code CompatConfig} reads {@code /apex/<name>/etc/compatconfig};
     * {@code getApexScanPartitions()} uses the module directory as a scan partition).
     */
    private static Object build(Class<?> apexInfoClass, boolean activeOnly) {
        List<Object> infos = new ArrayList<>();
        for (Map<String, String> attributes : readInfoList()) {
            if (activeOnly && !"true".equals(attributes.get("isActive"))) continue;
            String modulePath = attributes.get("modulePath");
            // preinstalledModulePath must be the *preinstalled apex file*
            // (/system/apex/<name>.apex), not the expanded directory: InitAppsHelper
            // .resolveApexToScanPartition() inside getApexScanPartitions() only turns an
            // active apex into a scan partition when this path sits under a system
            // partition, which is what makes PKMS scan /apex/<name>/{app,priv-app}.
            Object info = newApexInfo(apexInfoClass, attributes, modulePath,
                    preinstalledPath(attributes.get("moduleName"), modulePath));
            if (info != null) infos.add(info);
        }
        return toArray(apexInfoClass, infos);
    }

    /**
     * The image's real preinstalled apex path, {@code /system/apex/<name>.apex}.
     *
     * <p>This is deliberately unconditional (round 62): prepare-a15.py does not stage the
     * {@code *.apex} files into the instance (the payload is expanded into {@code /apex/<name>}
     * instead), but the *path* is what {@code InitAppsHelper.resolveApexToScanPartition()}
     * classifies — a path under {@code /system} is what makes
     * {@code getApexScanPartitions()} hand PKMS a partition for the module. Round 60 fell
     * back to {@code /apex/<name>} because the file was missing, so that round never tested
     * this route (probe still printed {@code a15_active_apex_preInstalledApexPath=/apex/…}).
     */
    private static String preinstalledPath(String moduleName, String modulePath) {
        if (moduleName == null) return modulePath;
        return "/system/apex/" + moduleName + ".apex";
    }

    /**
     * Builds the {@code getAllPackages()} array: <b>one {@code ApexInfo} per directory that
     * directly contains a base APK</b>, e.g.
     * {@code /apex/com.android.btservices/app/Bluetooth@BP1A.250505.005.D1} and
     * {@code /apex/com.android.permission/priv-app/PermissionController@BP1A.250505.005.D1}.
     *
     * <p>Why not the module root: {@code InstallPackageHelper.scanApexPackages} does
     * {@code parser.submit(new File(apexInfo.modulePath), flags)} for every entry and
     * nothing appends {@code /app} or {@code /priv-app}; {@code PackageParser2} then reads
     * the {@code *.apk} files <b>directly inside</b> that directory and fails with
     * {@code Missing base APK in <dir>} when there is none. Rounds 54/55 failed exactly
     * that way on the module roots {@code com.android.adbd} / {@code com.android.btservices}.
     */
    private static Object buildPackageDirs(Class<?> apexInfoClass) {
        List<Object> infos = new ArrayList<>();
        for (Map<String, String> attributes : readInfoList()) {
            String modulePath = attributes.get("modulePath");
            if (modulePath == null) continue;
            // ApexManager enforces **one active package per APEX module**
            // (ApexManager.java:518-521, "Two active packages have the same APEX module
            // name"). Real devices never hit it because there the APEX APKs are installed
            // by the partition scan; this runtime has no /apex partition
            // (round 60 probe: a15_apex_scan_partitions=0/32 even with the real
            // /system/apex/<name>.apex preinstalled path), so getAllPackages() has to carry
            // them — one per module, the rest reported as skipped.
            File primary = primaryPackage(modulePath);
            if (primary == null) continue;
            for (File packageDir : packageDirs(modulePath)) {
                if (packageDir.equals(primary)) continue;
                System.out.println("a15_apexservice_skip=" + packageDir.getPath()
                        + " reason=one-active-package-per-module");
            }
            if (declaresSharedUserId(primary)) {
                // Any APEX package that declares android:sharedUserId is unscannable on this
                // runtime: adjustScanFlags compares it against
                // `mPm.mSettings.getPackageLPr("android")` without a null check (Java line
                // 4924) and initSystemApps scans APEX packages (line 175) before /system
                // (line 178). Rounds 58/59 proved the filter is exactly "declares
                // sharedUserId". See STATUS "已知限制".
                System.out.println("a15_apexservice_skip=" + primary.getPath()
                        + " reason=shared-user-id");
                continue;
            }
            Object info = newApexInfo(apexInfoClass, attributes, primary.getPath(),
                    preinstalledPath(attributes.get("moduleName"), modulePath));
            if (info != null) infos.add(info);
        }
        return toArray(apexInfoClass, infos);
    }

    /**
     * The one package of a module that stays installed: {@code priv-app} before {@code app},
     * then the alphabetically first directory (e.g. PermissionController over
     * SafetyCenterResources, AdServicesApk over SdkSandbox, MediaProvider over Photopicker).
     */
    private static File primaryPackage(String modulePath) {
        File best = null;
        for (File candidate : packageDirs(modulePath)) {
            if (best == null) {
                best = candidate;
                continue;
            }
            boolean candidatePrivileged = candidate.getPath().contains("/priv-app/");
            boolean bestPrivileged = best.getPath().contains("/priv-app/");
            if (candidatePrivileged != bestPrivileged) {
                if (candidatePrivileged) best = candidate;
                continue;
            }
            if (candidate.getName().compareTo(best.getName()) < 0) best = candidate;
        }
        return best;
    }

    /** Looks for the {@code sharedUserId} attribute name in the base APK's manifest pool. */
    private static boolean declaresSharedUserId(File packageDir) {
        File base = null;
        File[] apks = packageDir.listFiles();
        if (apks == null) return false;
        for (File apk : apks) {
            if (apk.isFile() && apk.getName().endsWith(".apk")) {
                base = apk;
                break;
            }
        }
        if (base == null) return false;
        byte[] manifest;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(base)) {
            java.util.zip.ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) return false;
            try (java.io.InputStream input = zip.getInputStream(entry)) {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                // The binary-XML string pool sits at the front; 1 MiB is far more than
                // any manifest needs and keeps this cheap.
                while ((read = input.read(chunk)) > 0 && buffer.size() <= (1 << 20)) {
                    buffer.write(chunk, 0, read);
                }
                manifest = buffer.toByteArray();
            }
        } catch (java.io.IOException error) {
            System.out.println("a15_apexservice_manifest_failed=" + base + " " + error);
            return false;
        }
        return contains(manifest, SHARED_USER_ID_ASCII)
                || contains(manifest, SHARED_USER_ID_UTF16);
    }

    private static boolean contains(byte[] data, byte[] needle) {
        outer:
        for (int index = 0; index + needle.length <= data.length; ++index) {
            for (int offset = 0; offset < needle.length; ++offset) {
                if (data[index + offset] != needle[offset]) continue outer;
            }
            return true;
        }
        return false;
    }

    /**
     * The directories inside one APEX module that hold an APK:
     * {@code <module>/priv-app/<Pkg>@<ver>}, {@code <module>/app/<Pkg>@<ver>}, or the
     * {@code app}/{@code priv-app} directory itself for a flat {@code <dir>/*.apk} layout.
     */
    private static List<File> packageDirs(String modulePath) {
        List<File> dirs = new ArrayList<>();
        for (String sub : new String[] {"priv-app", "app"}) {
            File root = new File(modulePath, sub);
            if (!root.isDirectory()) continue;
            if (containsApk(root)) {
                dirs.add(root);
                continue;
            }
            File[] entries = root.listFiles();
            if (entries == null) continue;
            for (File entry : entries) {
                if (entry.isDirectory() && containsApk(entry)) dirs.add(entry);
            }
        }
        return dirs;
    }

    /** True when {@code dir} directly contains at least one {@code .apk} file. */
    private static boolean containsApk(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) return false;
        for (File entry : entries) {
            if (entry.isFile() && entry.getName().endsWith(".apk")) return true;
        }
        return false;
    }

    private static Object newApexInfo(Class<?> apexInfoClass, Map<String, String> attributes,
            String modulePath, String preinstalledModulePath) {
        if (modulePath == null) return null;
        try {
            Object info = apexInfoClass.getDeclaredConstructor().newInstance();
            set(info, "moduleName", attributes.get("moduleName"));
            set(info, "modulePath", modulePath);
            // ApexManager$ActiveApexInfo.<init> does `new File(preinstalledModulePath)`
            // unconditionally (ApexManager.java:137), so leaving this field null makes
            // PlatformCompat die with:
            //   java.lang.NullPointerException
            //     at java.io.File.<init>(File.java:288)
            //     at com.android.server.pm.ApexManager$ActiveApexInfo.<init>
            set(info, "preinstalledModulePath", preinstalledModulePath);
            set(info, "versionCode", parseLong(attributes.get("versionCode")));
            set(info, "versionName", attributes.get("versionName"));
            set(info, "isFactory", "true".equals(attributes.get("isFactory")));
            set(info, "isActive", "true".equals(attributes.get("isActive")));
            // ApexInfo.hasClassPathJars tells apexd's consumers whether the module
            // contributes boot class path jars; the expanded layout makes it visible.
            set(info, "hasClassPathJars",
                    preinstalledModulePath != null
                            && new File(preinstalledModulePath, "javalib").isDirectory());
            return info;
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("a15_apexservice_info_failed=" + error);
            return null;
        }
    }

    private static Object toArray(Class<?> apexInfoClass, List<Object> infos) {
        Object array = Array.newInstance(apexInfoClass, infos.size());
        for (int index = 0; index < infos.size(); ++index) Array.set(array, index, infos.get(index));
        return array;
    }

    private static List<Map<String, String>> readInfoList() {
        List<Map<String, String>> entries = new ArrayList<>();
        Pattern pattern = Pattern.compile(ATTRIBUTE);
        File file = new File(APEX_INFO_LIST);
        if (!file.isFile()) {
            System.err.println("a15_apexservice_list_missing=" + APEX_INFO_LIST);
            return entries;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("<apex-info ")) continue;
                Map<String, String> attributes = new HashMap<>();
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()) attributes.put(matcher.group(1), matcher.group(2));
                if (attributes.containsKey("moduleName")) entries.add(attributes);
            }
        } catch (IOException error) {
            System.err.println("a15_apexservice_list_failed=" + error);
        }
        return entries;
    }

    private static long parseLong(String text) {
        try {
            return text == null ? 0L : Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
     * Sets one field of the @hide Parcelable. Field sets differ between API levels
     * (A15's {@code ApexInfo} has moduleName/modulePath/versionCode/versionName/isFactory/
     * isActive/hasClassPathJars), so a missing field is not an error: the real
     * {@code writeToParcel} of the class stays the single writer of the wire format.
     */
    private static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            if (field.getType() == boolean.class) field.setBoolean(target, (Boolean) value);
            else if (field.getType() == long.class) field.setLong(target, (Long) value);
            else if (field.getType() == int.class) field.setInt(target, (Integer) value);
            else field.set(target, value);
        } catch (ReflectiveOperationException ignored) { }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return Integer.valueOf(0);
        if (type == long.class) return Long.valueOf(0L);
        if (type == float.class) return Float.valueOf(0f);
        if (type == double.class) return Double.valueOf(0d);
        if (type == short.class) return Short.valueOf((short) 0);
        if (type == byte.class) return Byte.valueOf((byte) 0);
        if (type == char.class) return Character.valueOf((char) 0);
        // Parcelable results (getActivePackage, getStagedSessionInfo,
        // installAndActivatePackage) and strings: null is the AIDL "absent" value.
        return null;
    }
}
