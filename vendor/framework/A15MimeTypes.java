package io.a10vm.guest;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs a usable MIME database when the framework's own one comes back empty.
 *
 * <p>Android 15 builds {@code libcore.content.type.MimeMap} through
 * {@code android.content.type.DefaultMimeMapFactory#create()}, which parses three
 * <em>classpath resources</em>: {@code /res/debian.mime.types},
 * {@code /res/android.mime.types}, {@code /res/vendor.mime.types}. Those entries
 * do exist in the image's {@code system/framework/framework.jar}, but
 * {@code Class.getResourceAsStream} returns null for them in this container
 * (proved on device: the probe line printed
 * {@code a15_application_mime=fallback(replaced=false, png->null)} while the zip
 * really contains the entries). An empty map makes MediaProvider reject every
 * insert that carries an explicit MIME type:
 *
 * <pre>
 *   MediaProvider.ensureFileColumns -> MimeTypeMap.getSingleton()
 *     .getExtensionFromMimeType("image/png") -> null
 *   -> IllegalArgumentException: Unsupported MIME type image/png
 * </pre>
 *
 * <p>That is the screenshot failure. The framework ships the supported hook
 * {@code MimeMap.setDefaultSupplier(Supplier<MimeMap>)}, so this class parses the
 * same {@code res/*.mime.types} entries straight out of the jar and installs the
 * result. Nothing is re-implemented: the data still comes from the image; only the
 * broken resource lookup is bypassed. A tiny embedded table is used only if the
 * jar cannot be read at all.
 */
final class A15MimeTypes {
    private static final String[] RESOURCES = {
            "/res/debian.mime.types", "/res/android.mime.types", "/res/vendor.mime.types"};

    /** Last resort only: enough for MediaStore inserts of common phone formats. */
    private static final String[] EMBEDDED = {
            "image/jpeg jpg jpeg", "image/png png", "image/gif gif", "image/webp webp",
            "image/bmp bmp", "image/heic heic heif", "image/avif avif", "image/svg+xml svg",
            "video/mp4 mp4 m4v", "video/3gpp 3gp", "video/webm webm", "video/x-matroska mkv",
            "video/x-msvideo avi", "video/mpeg mpg", "video/quicktime mov",
            "audio/mpeg mp3", "audio/mp4 m4a", "audio/aac aac", "audio/ogg ogg",
            "audio/opus opus", "audio/flac flac", "audio/x-wav wav", "audio/amr amr",
            "text/plain txt", "text/html html", "text/xml xml", "text/csv csv",
            "application/pdf pdf", "application/json json", "application/zip zip",
            "application/octet-stream bin",
    };

    private static final String PROBE_MIME = "image/png";

    private A15MimeTypes() {}

    /** @return one line describing which MIME database the process ended up with. */
    static String install() {
        String step = "start";
        try {
            Class<?> typeMapClass = Class.forName("android.webkit.MimeTypeMap");
            Object typeMap = typeMapClass.getMethod("getSingleton").invoke(null);
            if (typeMap == null) return "unavailable:getSingleton=null";
            Method extensionOf = typeMapClass.getMethod("getExtensionFromMimeType", String.class);
            step = "probe";
            Object probe = extensionOf.invoke(typeMap, PROBE_MIME);
            if (probe != null) return "framework(png->" + probe + ")";

            step = "load-classes";
            Class<?> mimeMapClass = Class.forName("libcore.content.type.MimeMap");
            Class<?> builderClass = Class.forName("libcore.content.type.MimeMap$Builder");
            step = "builder";
            Object builder = mimeMapClass.getMethod("builder").invoke(null);
            if (builder == null) return "unavailable:builder=null";
            Method addMapping = builderClass.getMethod("addMimeMapping", String.class, String.class);

            step = "parse";
            int entries = 0;
            try {
                entries = parseJarResources(addMapping, builder);
            } catch (Throwable unreadable) {
                System.out.println("a15_application_mime_jar=unavailable:" + unreadable);
            }
            if (entries == 0) {
                for (String line : EMBEDDED) entries += addLine(line, addMapping, builder);
            }

            step = "build";
            Object built = builderClass.getMethod("build").invoke(builder);
            if (built == null) return "unavailable:build=null";

            step = "install";
            Method setDefault = mimeMapClass.getDeclaredMethod("setDefaultSupplier", Supplier.class);
            setDefault.setAccessible(true);
            setDefault.invoke(null, (Supplier<Object>) () -> built);

            step = "verify";
            Object verifyMap = typeMapClass.getMethod("getSingleton").invoke(null);
            Object again = verifyMap == null ? null : extensionOf.invoke(verifyMap, PROBE_MIME);
            if (again == null) {
                // MimeTypeMap keeps its own singleton; drop it and ask again.
                try {
                    Field cache = typeMapClass.getDeclaredField("sMimeTypeMap");
                    cache.setAccessible(true);
                    cache.set(null, null);
                } catch (Throwable ignored) {
                }
                Object fresh = typeMapClass.getMethod("getSingleton").invoke(null);
                again = fresh == null ? null : extensionOf.invoke(fresh, PROBE_MIME);
            }
            return "installed(entries=" + entries + ", png->" + again + ")";
        } catch (Throwable failure) {
            return "unavailable:step=" + step + " " + failure;
        }
    }
    private static int parseJarResources(Method addMapping, Object builder) throws Exception {
        int added = 0;
        try (ZipFile jar = new ZipFile("/system/framework/framework.jar")) {
            for (String resource : RESOURCES) {
                ZipEntry entry = jar.getEntry(resource.substring(1));
                if (entry == null) continue;
                try (InputStream stream = jar.getInputStream(entry);
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) added += addLine(line, addMapping, builder);
                }
            }
        }
        return added;
    }

    private static int addLine(String line, Method addMapping, Object builder) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return 0;
        String[] parts = trimmed.split("\\s+");
        if (parts.length < 2) return 0;
        int added = 0;
        for (int i = 1; i < parts.length; ++i) {
            try {
                addMapping.invoke(builder, parts[0], parts[i]);
                ++added;
            } catch (Throwable duplicateOrInvalid) {
                // Duplicate mappings across the three files are expected.
            }
        }
        return added;
    }
}