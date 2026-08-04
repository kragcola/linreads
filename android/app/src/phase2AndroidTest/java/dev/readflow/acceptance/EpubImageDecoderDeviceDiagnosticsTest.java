package dev.readflow.acceptance;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;

/**
 * Device-only evidence for the Huawei image decode failure seen while opening the real 86 EPUB.
 * This test deliberately records variants without changing reader state or production behavior.
 */
@RunWith(AndroidJUnit4.class)
public final class EpubImageDecoderDeviceDiagnosticsTest {
    private static final String TAG = "EpubImageDecodeDiag";
    private static final long MAX_ENCODED_BYTES = 20L * 1024L * 1024L;
    private static final List<String> HREFS = Arrays.asList(
            "OEBPS/Images/006.jpg",
            "OEBPS/Images/007.jpg",
            "OEBPS/Images/008.jpg",
            "OEBPS/Images/009.webp"
    );

    @Test
    public void recordsProductionAndBitmapFactoryDecodeVariants() throws Exception {
        File epub = new File(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "epub_-1871112171.epub"
        );
        Assert.assertTrue("real 86 EPUB cache must exist", epub.isFile());
        Method production = Class.forName("dev.readflow.render.epub.EpubImageDecoderKt")
                .getDeclaredMethod(
                        "decodeEpubImage",
                        File.class,
                        String.class,
                        int.class,
                        int.class,
                        long.class
                );
        production.setAccessible(true);

        try (ZipFile zip = new ZipFile(epub)) {
            for (String href : HREFS) {
                ZipEntry entry = zip.getEntry(href);
                Assert.assertNotNull("missing EPUB entry " + href, entry);
                byte[] bytes;
                try (InputStream input = zip.getInputStream(entry)) {
                    bytes = readAll(input);
                }
                int sample = logBounds(href, bytes);
                Bitmap productionBitmap = (Bitmap) production.invoke(
                        null,
                        epub,
                        href,
                        1600,
                        4_000_000,
                        MAX_ENCODED_BYTES
                );
                logBitmap(href + " production", productionBitmap);
                logBitmap(href + " bytes-default sample=" + sample,
                        decodeBytes(bytes, sample, null, false));
                logBitmap(href + " bytes-argb8888 sample=" + sample,
                        decodeBytes(bytes, sample, Bitmap.Config.ARGB_8888, false));
                logBitmap(href + " bytes-argb8888-mutable sample=" + sample,
                        decodeBytes(bytes, sample, Bitmap.Config.ARGB_8888, true));
                logStream(href + " zip-default sample=" + sample,
                        zip, entry, sample, null, false);
                logStream(href + " zip-argb8888 sample=" + sample,
                        zip, entry, sample, Bitmap.Config.ARGB_8888, false);
                logStream(href + " zip-argb8888-mutable sample=" + sample,
                        zip, entry, sample, Bitmap.Config.ARGB_8888, true);
            }
        }
    }

    private static Bitmap decodeBytes(
            byte[] bytes,
            int sample,
            Bitmap.Config config,
            boolean mutable
    ) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        if (config != null) options.inPreferredConfig = config;
        options.inMutable = mutable;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static void logStream(
            String label,
            ZipFile zip,
            ZipEntry entry,
            int sample,
            Bitmap.Config config,
            boolean mutable
    ) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        if (config != null) options.inPreferredConfig = config;
        options.inMutable = mutable;
        try (InputStream input = zip.getInputStream(entry)) {
            logBitmap(label, BitmapFactory.decodeStream(input, null, options));
        }
    }

    private static int logBounds(String href, byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        Log.i(TAG, href + " bounds=" + options.outWidth + "x" + options.outHeight);
        return sampleSize(options.outWidth, options.outHeight);
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        while (width / sample > 1600
                || height / sample > 1600
                || (long) (width / sample) * (height / sample) > 4_000_000L) {
            sample *= 2;
        }
        return sample;
    }

    private static void logBitmap(String label, Bitmap bitmap) {
        if (bitmap == null) {
            Log.e(TAG, label + " result=null");
            return;
        }
        Log.i(TAG, label + " result=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " config=" + bitmap.getConfig()
                + " bytes=" + bitmap.getAllocationByteCount());
        bitmap.recycle();
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > 0) output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
