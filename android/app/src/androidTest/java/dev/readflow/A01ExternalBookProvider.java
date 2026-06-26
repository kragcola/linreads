package dev.readflow;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class A01ExternalBookProvider extends ContentProvider {

    private static final String TXT_SENTINEL = "A01 TXT external provider import opens reader.";
    private static final String MD_SENTINEL = "A01 Markdown external provider import opens reader.";
    private static final String EPUB_SENTINEL = "A01 EPUB external provider import opens reader.";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        if (mode == null || !mode.contains("r")) {
            throw new IllegalArgumentException("A01 provider is read-only");
        }
        byte[] bytes = bytesFor(uri);
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to create A01 provider pipe", error);
        }
        new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output =
                     new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                output.write(bytes);
            } catch (IOException ignored) {
                // Test fixture provider: read errors surface to the consumer side.
            }
        }).start();
        return pipe[0];
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) {
        return new AssetFileDescriptor(
            openFile(uri, mode),
            0,
            AssetFileDescriptor.UNKNOWN_LENGTH
        );
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder
    ) {
        String fileName = fileNameFor(uri);
        long size = bytesFor(uri).length;
        String[] columns = projection != null && projection.length > 0
            ? projection
            : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            String column = columns[index];
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row[index] = fileName;
            } else if (OpenableColumns.SIZE.equals(column)) {
                row[index] = size;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String extension = extensionFor(uri);
        switch (extension) {
            case "txt":
                return "text/plain";
            case "md":
            case "markdown":
                return "text/markdown";
            case "epub":
                return "application/epub+zip";
            case "pdf":
                return "application/pdf";
            default:
                return "application/octet-stream";
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private String fileNameFor(Uri uri) {
        if (uri.getPathSegments().size() != 1) {
            throw new IllegalArgumentException("Expected one path segment in " + uri);
        }
        String decoded = Uri.decode(uri.getPathSegments().get(0));
        int slash = Math.max(decoded.lastIndexOf('/'), decoded.lastIndexOf('\\'));
        return slash >= 0 ? decoded.substring(slash + 1) : decoded;
    }

    private String extensionFor(Uri uri) {
        String fileName = fileNameFor(uri);
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    private byte[] bytesFor(Uri uri) {
        switch (extensionFor(uri)) {
            case "txt":
                return (TXT_SENTINEL + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case "md":
            case "markdown":
                return ("# A01 Markdown\n\n" + MD_SENTINEL + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case "epub":
                return epubBytes();
            case "pdf":
                return pdfBytes();
            default:
                return new byte[0];
        }
    }

    private byte[] epubBytes() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            addText(
                zip,
                "META-INF/container.xml",
                "<container>\n" +
                    "  <rootfiles>\n" +
                    "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n" +
                    "  </rootfiles>\n" +
                    "</container>"
            );
            addText(
                zip,
                "OEBPS/content.opf",
                "<package version=\"3.0\">\n" +
                    "  <manifest>\n" +
                    "    <item id=\"c0\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/>\n" +
                    "  </manifest>\n" +
                    "  <spine>\n" +
                    "    <itemref idref=\"c0\"/>\n" +
                    "  </spine>\n" +
                    "</package>"
            );
            addText(
                zip,
                "OEBPS/chapter.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n" +
                    "  <body>\n" +
                    "    <p>" + EPUB_SENTINEL + "</p>\n" +
                    "  </body>\n" +
                    "</html>"
            );
        } catch (IOException error) {
            throw new IllegalStateException("Unable to create A01 EPUB fixture", error);
        }
        return output.toByteArray();
    }

    private void addText(ZipOutputStream zip, String path, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private byte[] pdfBytes() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(1200, 1800, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(42f);
        paint.setAntiAlias(true);
        page.getCanvas().drawColor(Color.WHITE);
        page.getCanvas().drawText("A01 incoming PDF page", 72f, 120f, paint);
        document.finishPage(page);
        try {
            document.writeTo(output);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to create A01 PDF fixture", error);
        } finally {
            document.close();
        }
        return output.toByteArray();
    }
}
