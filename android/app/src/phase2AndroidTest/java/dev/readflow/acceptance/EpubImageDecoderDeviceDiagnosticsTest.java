package dev.readflow.acceptance;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
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
            "Images/006.jpg",
            "Images/007.jpg",
            "Images/008.jpg",
            "Images/009.webp",
            "Images/016.png"
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
                Bitmap productionBitmap = (Bitmap) production.invoke(
                        null,
                        epub,
                        href,
                        1600,
                        4_000_000,
                        MAX_ENCODED_BYTES
                );
                logBitmap(href + " production", productionBitmap);
                logBitmap(href + " byte-default", decodeBytes(bytes, 1, null));
                logBitmap(href + " byte-argb", decodeBytes(bytes, 1, Bitmap.Config.ARGB_8888));
                logBitmap(href + " byte-rgb565", decodeBytes(bytes, 1, Bitmap.Config.RGB_565));
                logStream(href + " zip-default", zip, entry, false);
                logStream(href + " zip-buffered", zip, entry, true);
                logBounds(href, bytes);
            }
        }
    }

    private static Bitmap decodeBytes(byte[] bytes, int sample, Bitmap.Config config) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        if (config != null) options.inPreferredConfig = config;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static void logStream(String label, ZipFile zip, ZipEntry entry, boolean buffered)
            throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        try (InputStream raw = zip.getInputStream(entry);
             InputStream input = buffered ? new BufferedInputStream(raw) : raw) {
            logBitmap(label, BitmapFactory.decodeStream(input, null, options));
        }
    }

    private static void logBounds(String href, byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        Log.i(TAG, href + " bounds=" + options.outWidth + "x" + options.outHeight);
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
