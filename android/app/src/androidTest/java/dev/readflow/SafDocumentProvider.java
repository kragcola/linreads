package dev.readflow;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

public final class SafDocumentProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        rootDir().mkdirs();
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = fileFor(uri);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        int flags;
        if (mode != null && mode.contains("w")) {
            flags = ParcelFileDescriptor.MODE_CREATE
                | ParcelFileDescriptor.MODE_TRUNCATE
                | ParcelFileDescriptor.MODE_WRITE_ONLY;
        } else {
            flags = ParcelFileDescriptor.MODE_READ_ONLY;
        }
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
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
        File file = fileFor(uri);
        String[] columns = projection != null && projection.length > 0
            ? projection
            : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            String column = columns[index];
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row[index] = file.getName();
            } else if (OpenableColumns.SIZE.equals(column)) {
                row[index] = file.exists() ? file.length() : 0L;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = fileFor(uri).getName().toLowerCase(Locale.US);
        return name.endsWith(".zip") ? "application/zip" : "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        File file = fileFor(uri);
        return file.exists() && file.delete() ? 1 : 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private File fileFor(Uri uri) {
        if (uri.getPathSegments().size() != 1) {
            throw new IllegalArgumentException("Expected one path segment in " + uri);
        }
        String fileName = Uri.decode(uri.getPathSegments().get(0));
        if (fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("Nested paths are not supported: " + fileName);
        }
        return new File(rootDir(), fileName);
    }

    private File rootDir() {
        return new File(getContext().getFilesDir(), "saf-documents");
    }
}
