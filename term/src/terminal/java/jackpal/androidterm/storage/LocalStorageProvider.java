package jackpal.androidterm.storage;
import static android.os.Build.VERSION.SDK_INT;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Log;
import android.webkit.MimeTypeMap;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Set;
import org.apache.commons.io.FileUtils;

import jackpal.androidterm.BuildConfig;
import jackpal.androidterm.R;

import static android.content.Context.MODE_PRIVATE;
import static jackpal.androidterm.StaticConfig.SCOPED_STORAGE;

/**
 * Manages documents and exposes them to the Android system for sharing.
 */
@TargetApi(Build.VERSION_CODES.KITKAT)
public class LocalStorageProvider extends DocumentsProvider {
    private static final String TAG = "LocalStorageProvider";
    private static final String TITLE = "Terminal Emulator";
    private static final String mSUMMARY = "HOME";
    private static final String PREF_KEY_SHOW_DOTFILES = "pref_key_show_dotfiles";
    private static final boolean SHOW_DOTFILES_DEFAULT = true;
    @SuppressLint("SdCardPath")
    private static final String BASE_DEFAULT_DIR = "/data/data/" + BuildConfig.APPLICATION_ID + "/app_HOME";
    private static final String PREF_KEY_HOME_PATH = "home_path";

    // Use these as the default columns to return information about a root if no specific
    // columns are requested in a query.
    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
    };

    // Use these as the default columns to return information about a document if no specific
    // columns are requested in a query.
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
    };

    // No official policy on how many to return, but make sure you do limit the number of recent
    // and search results.
    private static final int MAX_SEARCH_RESULTS = 50;
    private static final int MAX_LAST_MODIFIED = 5;

    // A file object at the root of the file hierarchy.  Depending on your implementation, the root
    // does not need to be an existing file system directory.  For example, a tag-based document
    // provider might return a directory containing all tags, represented as child directories.
    private File mBaseDir;

    @Override
    public boolean onCreate() {
        Log.v(TAG, "onCreate");
        setBaseDir();
        return true;
    }

    private void setBaseDir() {
        try {
            mBaseDir = new File(BASE_DEFAULT_DIR);
            if (getContext() != null) {
                mBaseDir = new File(getContext().getDir("HOME", MODE_PRIVATE).getAbsolutePath());
                SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getContext());
                String appHOME = pref.getString(PREF_KEY_HOME_PATH, mBaseDir.getAbsolutePath());
                if (appHOME != null) mBaseDir = new File(appHOME);
            }
        } catch (Exception e) {
            // Do nothing
        }
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        Log.v(TAG, "queryRoots");

        // Create a cursor with either the requested fields, or the default projection.  This
        // cursor is returned to the Android system picker UI and used to display all roots from
        // this provider.
        final MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));

        // If user is not logged in, return an empty root cursor.  This removes our provider from
        // the list entirely.
        if (!isUserLoggedIn()) {
            return result;
        }

        // It's possible to have multiple roots (e.g. for multiple accounts in the same app) -
        // just add multiple cursor rows.
        // Construct one row for a root called "MyCloud".
        final MatrixCursor.RowBuilder row = result.newRow();

        row.add(Root.COLUMN_ROOT_ID, getDocIdForFile(mBaseDir));
        row.add(Root.COLUMN_SUMMARY, mSUMMARY);

        // FLAG_SUPPORTS_CREATE means at least one directory under the root supports creating
        // documents.  FLAG_SUPPORTS_RECENTS means your application's most recently used
        // documents will show up in the "Recents" category.  FLAG_SUPPORTS_SEARCH allows users
        // to search all documents the application shares. FLAG_SUPPORTS_IS_CHILD allows
        // testing parent child relationships, available after SDK 21 (Lollipop).
        if (SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE |
                    Root.FLAG_SUPPORTS_RECENTS |
                    Root.FLAG_SUPPORTS_SEARCH );
        } else {
            row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE |
                    Root.FLAG_SUPPORTS_RECENTS |
                    Root.FLAG_SUPPORTS_SEARCH |
                    Root.FLAG_SUPPORTS_IS_CHILD);
        }
        row.add(Root.FLAG_LOCAL_ONLY);

        // COLUMN_TITLE is the root title (e.g. what will be displayed to identify your provider).
        row.add(Root.COLUMN_TITLE, TITLE);

        // This document id must be unique within this provider and consistent across time.  The
        // system picker UI may save it and refer to it later.
        row.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(mBaseDir));

        // The child MIME types are used to filter the roots and only present to the user roots
        // that contain the desired type somewhere in their file hierarchy.
        row.add(Root.COLUMN_MIME_TYPES, getChildMimeTypes(mBaseDir));
        row.add(Root.COLUMN_AVAILABLE_BYTES, mBaseDir.getFreeSpace());
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher);

        /*
        if (SCOPED_STORAGE) {
            try {
                final MatrixCursor.RowBuilder row2 = result.newRow();
                String summary = "$APPEXTFILES";
                File rootDir = null;
                rootDir = new File(getContext().getExternalFilesDir(null).getAbsolutePath());
                if (rootDir != null && rootDir.canWrite()) {
                    row2.add(Root.COLUMN_TITLE, TITLE);
                    row2.add(Root.COLUMN_ICON, R.drawable.ic_folder);
                    row2.add(Root.COLUMN_SUMMARY, summary);
                    row2.add(Root.COLUMN_FLAGS, FLAGS);
                    row2.add(Root.COLUMN_ROOT_ID, getDocIdForFile(rootDir));
                    row2.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(rootDir));
                    row2.add(Root.COLUMN_MIME_TYPES, getChildMimeTypes(rootDir));
                    row2.add(Root.COLUMN_AVAILABLE_BYTES, rootDir.getFreeSpace());
                }
            } catch (Exception e) {
                // Do nothing
            }
        }
        */
        return result;
    }

    @Override
    public Cursor queryRecentDocuments(String rootId, String[] projection)
            throws FileNotFoundException {
        Log.v(TAG, "queryRecentDocuments");

        // This example implementation walks a local file structure to find the most recently
        // modified files.  Other implementations might include making a network call to query a
        // server.

        // Create a cursor with the requested projection, or the default projection.
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));

        final File parent = getFileForDocId(rootId);

        // Create a queue to store the most recent documents, which orders by last modified.
        PriorityQueue<File> lastModifiedFiles = new PriorityQueue<File>(5, new Comparator<File>() {
            public int compare(File i, File j) {
                return Long.compare(i.lastModified(), j.lastModified());
            }
        });

        // Iterate through all files and directories in the file structure under the root.  If
        // the file is more recent than the least recently modified, add it to the queue,
        // limiting the number of results.
        final LinkedList<File> pending = new LinkedList<File>();

        // Start by adding the parent to the list of files to be processed
        pending.add(parent);

        // Do while we still have unexamined files
        while (!pending.isEmpty()) {
            // Take a file from the list of unprocessed files
            final File file = pending.removeFirst();
            if (file.isDirectory()) {
                // If it's a directory, add all its children to the unprocessed list
                Collections.addAll(pending, file.listFiles());
            } else {
                // If it's a file, add it to the ordered queue.
                lastModifiedFiles.add(file);
            }
        }

        // Add the most recent files to the cursor, not exceeding the max number of results.
        int includedCount = 0;
        while (includedCount < MAX_LAST_MODIFIED + 1 && !lastModifiedFiles.isEmpty()) {
            final File file = lastModifiedFiles.remove();
            includeFile(result, null, file);
            includedCount++;
        }
        return result;
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection)
            throws FileNotFoundException {
        Log.v(TAG, "querySearchDocuments");

        // Create a cursor with the requested projection, or the default projection.
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        final File parent = getFileForDocId(rootId);

        // This example implementation searches file names for the query and doesn't rank search
        // results, so we can stop as soon as we find a sufficient number of matches.  Other
        // implementations might use other data about files, rather than the file name, to
        // produce a match; it might also require a network call to query a remote server.

        // Iterate through all files in the file structure under the root until we reach the
        // desired number of matches.
        final LinkedList<File> pending = new LinkedList<File>();

        // Start by adding the parent to the list of files to be processed
        pending.add(parent);

        boolean secureMode = isSecureMode();
        // Do while we still have unexamined files, and fewer than the max search results
        while (!pending.isEmpty() && result.getCount() < MAX_SEARCH_RESULTS) {
            // Take a file from the list of unprocessed files
            final File file = pending.removeFirst();

            // Avoid folders outside the $HOME folders linked in to symlinks (to avoid e.g. search
            // through the whole SD card).
            // boolean isInsideHome;
            // try {
            //     isInsideHome = file.getCanonicalPath().startsWith(mBaseDir.getCanonicalPath());
            // } catch (IOException e) {
            //     isInsideHome = true;
            // }
            // if (!isInsideHome) continue;
            String filePath = file.getAbsolutePath();
            if (secureMode && filePath.startsWith(mBaseDir.getAbsolutePath() + "/.")) continue;
            if (file.isDirectory()) {
                // If it's a directory, add all its children to the unprocessed list
                Collections.addAll(pending, file.listFiles());
            } else {
                // If it's a file and it matches, add it to the result cursor.
                if (file.getName().toLowerCase().contains(query)) {
                    includeFile(result, null, file);
                }
            }
        }
        return result;
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint,
                                                     CancellationSignal signal)
            throws FileNotFoundException {
        Log.v(TAG, "openDocumentThumbnail");

        final File file = getFileForDocId(documentId);
        final ParcelFileDescriptor pfd =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        Log.v(TAG, "queryDocument");

        // Create a cursor with the requested projection, or the default projection.
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
                                      String sortOrder) throws FileNotFoundException {
        Log.v(TAG, "queryChildDocuments, parentDocumentId: " +
                parentDocumentId +
                " sortOrder: " +
                sortOrder);

        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File parent = getFileForDocId(parentDocumentId);
        boolean isHome = parent.getAbsolutePath().equals(mBaseDir.getAbsolutePath());
        boolean secureMode = isSecureMode();
        for (File file : parent.listFiles()) {
            if (!secureMode || !(isHome && file.getName().startsWith("."))) {
                includeFile(result, null, file);
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(final String documentId, final String mode,
                                             CancellationSignal signal)
            throws FileNotFoundException {
        Log.v(TAG, "openDocument, mode: " + mode);
        // It's OK to do network operations in this method to download the document, as long as you
        // periodically check the CancellationSignal.  If you have an extremely large file to
        // transfer from the network, a better solution may be pipes or sockets
        // (see ParcelFileDescriptor for helper methods).

        final File file = getFileForDocId(documentId);
        final int accessMode = ParcelFileDescriptor.parseMode(mode);

        final boolean isWrite = (mode.indexOf('w') != -1);
        if (isWrite) {
            // Attach a close listener if the document is opened in write mode.
            try {
                Handler handler = new Handler(getContext().getMainLooper());
                return ParcelFileDescriptor.open(file, accessMode, handler,
                        new ParcelFileDescriptor.OnCloseListener() {
                    @Override
                    public void onClose(IOException e) {

                        // Update the file with the cloud server.  The client is done writing.
                        Log.i(TAG, "A file with id " + documentId + " has been closed!  Time to " +
                                "update the server.");
                    }

                });
            } catch (IOException e) {
                throw new FileNotFoundException("Failed to open document with id " + documentId +
                        " and mode " + mode);
            }
        } else {
            return ParcelFileDescriptor.open(file, accessMode);
        }
    }


    public boolean isChildFile(File parentFile, File childFile){
        File realFileParent = childFile.getParentFile();
        return realFileParent == null || realFileParent.equals(parentFile);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        Log.v(TAG, "isChildDocument");
        try {
            File parentFile = getFileForDocId(parentDocumentId);
            File childFile = getFileForDocId(documentId);
            return isChildFile(parentFile, childFile);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "FileNotFound in isChildDocument: " + e.getMessage());
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public String createDocument(String documentId, String mimeType, String displayName)
            throws FileNotFoundException {
        Log.v(TAG, "createDocument");

        String canonicalName = displayName.replaceAll("\\\\", "_");
        File file = new File(documentId, canonicalName);
        String parentDir = file.getParent();
        if (parentDir == null || (isSecureMode() && canonicalName.startsWith(".") && parentDir.equals(mBaseDir.getAbsolutePath()))) {
            throw new FileNotFoundException("Failed to create document with id " + documentId);
        }

        File parent = getFileForDocId(documentId);
        file = new File(parent.getPath(), displayName);
        try {
            // Create the new File to copy into
            boolean wasNewFileCreated = false;
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                file.mkdirs();
                if (file.isDirectory()) wasNewFileCreated = true;
            } else {
                file.createNewFile();
                if (file.isFile() && file.setWritable(true) && file.setReadable(true)) {
                    wasNewFileCreated = true;
                }
            }

            if (!wasNewFileCreated) {
                throw new FileNotFoundException("Failed to create document with name " +
                        displayName +" and documentId " + documentId);
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create document with name " +
                    displayName +" and documentId " + documentId);
        }
        return getDocIdForFile(file);
    }

    @Override
    public String renameDocument(String documentId, String displayName)
            throws FileNotFoundException {
        Log.v(TAG, "renameDocument");
        if (displayName == null) {
            throw new FileNotFoundException("Failed to rename document, new name is null");
        }

        // Create the destination file in the same directory as the source file
        File sourceFile = getFileForDocId(documentId);
        File sourceParentFile = sourceFile.getParentFile();
        if (sourceParentFile == null) {
            throw new FileNotFoundException("Failed to rename document. File has no parent.");
        }
        File destFile = new File(sourceParentFile.getPath(), displayName);

        // Try to do the rename
        try {
            boolean renameSucceeded = sourceFile.renameTo(destFile);
            if (!renameSucceeded) {
                throw new FileNotFoundException("Failed to rename document. Renamed failed.");
            }
        } catch (Exception e) {
            Log.w(TAG, "Rename exception : " + e.getLocalizedMessage() + e.getCause());
            throw new FileNotFoundException("Failed to rename document. Error: " + e.getMessage());
        }

        return getDocIdForFile(destFile);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        Log.v(TAG, "deleteDocument");
        File file = getFileForDocId(documentId);
        deleteFileOrFolder(file);
    }

    @Override
    public void removeDocument(String documentId, String parentDocumentId)
            throws FileNotFoundException {
        Log.v(TAG, "removeDocument");

        File file = getFileForDocId(documentId);
        deleteDocument(file.getAbsolutePath());
    }

    /**
     * overload copyDocument to insist that the parent matches
     */
    public String copyDocument(String sourceDocumentId, String sourceParentDocumentId,
                               String targetParentDocumentId) throws FileNotFoundException {
        Log.v(TAG, "copyDocument with document parent");
        if (!isChildDocument(sourceParentDocumentId, sourceDocumentId)) {
            throw new FileNotFoundException("Failed to copy document with id " +
                    sourceDocumentId + ". Parent is not: " + sourceParentDocumentId);
        }
        return copyDocument(sourceDocumentId, targetParentDocumentId);
    }

    @Override
    public String copyDocument(String sourceDocumentId, String targetParentDocumentId)
            throws FileNotFoundException {
        Log.v(TAG, "copyDocument");

        File parent = getFileForDocId(targetParentDocumentId);
        File oldFile = getFileForDocId(sourceDocumentId);
        File newFile = new File(parent.getPath(), oldFile.getName());
        try {
            // Create the new File to copy into
            boolean wasNewFileCreated = false;
            newFile.createNewFile();
            if (newFile.isFile() && newFile.setWritable(true) && newFile.setReadable(true)) {
                wasNewFileCreated = true;
            }

            if (!wasNewFileCreated) {
                throw new FileNotFoundException("Failed to copy document " + sourceDocumentId +
                        ". Could not create new file.");
            }

            // Copy the bytes into the new file
            try (InputStream inStream = new FileInputStream(oldFile)) {
                try (OutputStream outStream = new FileOutputStream(newFile)) {
                    // Transfer bytes from in to out
                    byte[] buf = new byte[4096]; // ideal range for network: 2-8k, disk: 8-64k
                    int len;
                    while ((len = inStream.read(buf)) > 0) {
                        outStream.write(buf, 0, len);
                    }
                }
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to copy document: " + sourceDocumentId +
                    ". " + e.getMessage());
        }
        return getDocIdForFile(newFile);
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId,
                               String targetParentDocumentId) throws FileNotFoundException {
        Log.v(TAG, "moveDocument");
        try {
            // Copy document, insisting that the parent is correct
            String newDocumentId = copyDocument(sourceDocumentId, sourceParentDocumentId,
                    targetParentDocumentId);
            // Remove old document
            removeDocument(sourceDocumentId,sourceParentDocumentId);
            return newDocumentId;
        } catch (FileNotFoundException e) {
            throw new FileNotFoundException("Failed to move document " + sourceDocumentId);
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        return getTypeForFile(file);
    }

    /**
     * @param projection the requested root column projection
     * @return either the requested root column projection, or the default projection if the
     * requested projection is null.
     */
    private static String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    /**
     * Get a file's MIME type
     *
     * @param file the File object whose type we want
     * @return the MIME type of the file
     */
    private static String getTypeForFile(File file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        } else {
            return getTypeForName(file.getName());
        }
    }

    /**
     * Get the MIME data type of a document, given its filename.
     *
     * @param name the filename of the document
     * @return the MIME data type of a document
     */
    private static String getTypeForName(String name) {
        final int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = name.substring(lastDot + 1);
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    /**
     * Gets a string of unique MIME data types a directory supports, separated by newlines.  This
     * should not change.
     *
     * @param parent the File for the parent directory
     * @return a string of the unique MIME data types the parent directory supports
     */
    private String getChildMimeTypes(File parent) {
        Set<String> mimeTypes = new HashSet<String>();
        mimeTypes.add("image/*");
        mimeTypes.add("text/*");
        mimeTypes.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        // Flatten the list into a string and insert newlines between the MIME type strings.
        StringBuilder mimeTypesString = new StringBuilder();
        for (String mimeType : mimeTypes) {
            mimeTypesString.append(mimeType).append("\n");
        }

        return mimeTypesString.toString();
    }

    /**
     * Get the document ID given a File.  The document id must be consistent across time.  Other
     * applications may save the ID and use it to reference documents later.
     * <p/>
     * This implementation is specific to this demo.  It assumes only one root and is built
     * directly from the file structure.  However, it is possible for a document to be a child of
     * multiple directories (for example "android" and "images"), in which case the file must have
     * the same consistent, unique document ID in both cases.
     *
     * @param file the File whose document ID you want
     * @return the corresponding document ID
     */
    private String getDocIdForFile(File file) {
        String path = file.getAbsolutePath();
        return path;
    }

    /**
     * Add a representation of a file to a cursor.
     *
     * @param result the cursor to modify
     * @param docId  the document ID representing the desired file (may be null if given file)
     * @param file   the File object representing the desired file (may be null if given docID)
     * @throws FileNotFoundException
     */
    private void includeFile(MatrixCursor result, String docId, File file)
            throws FileNotFoundException {
        if (docId == null) {
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
        }

        int flags = 0;

        if (file.isDirectory()) {
            // Request the folder to lay out as a grid rather than a list. This also allows a larger
            // thumbnail to be displayed for each image.
            //            flags |= Document.FLAG_DIR_PREFERS_GRID;

            // Add FLAG_DIR_SUPPORTS_CREATE if the file is a writable directory.
            if (file.isDirectory() && file.canWrite()) {
                flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
                flags |= Document.FLAG_SUPPORTS_DELETE;
            }
        } else if (file.canWrite()) {
            // If the file is writable set FLAG_SUPPORTS_WRITE and
            // FLAG_SUPPORTS_DELETE
            flags |= Document.FLAG_SUPPORTS_WRITE;
            flags |= Document.FLAG_SUPPORTS_DELETE;

            // Add SDK specific flags if appropriate
            if (SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                flags |= Document.FLAG_SUPPORTS_RENAME;
            }
            if (SDK_INT >= Build.VERSION_CODES.N) {
                flags |= Document.FLAG_SUPPORTS_REMOVE;
                flags |= Document.FLAG_SUPPORTS_MOVE;
                flags |= Document.FLAG_SUPPORTS_COPY;
            }
        }

        final String displayName = file.getName();
        final String mimeType = getTypeForFile(file);

        if (mimeType.startsWith("image/")) {
            // Allow the image to be represented by a thumbnail rather than an icon
            flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
        }

        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_SIZE, file.length());
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);

        // Add a custom icon
        row.add(Document.COLUMN_ICON, R.mipmap.ic_launcher);
    }

    /**
     * Translate your custom URI scheme into a File object.
     *
     * @param docId the document ID representing the desired file
     * @return a File represented by the given document ID
     * @throws java.io.FileNotFoundException
     */
    private File getFileForDocId(String docId) throws FileNotFoundException {
        final File file = new File(docId);
        if (!file.exists()) throw new FileNotFoundException(file.getAbsolutePath() + " not found");
        return file;
    }

    /**
     * Placeholder function to determine whether the user is logged in.
     */
    private boolean isUserLoggedIn() {
        return true;
    }

    private boolean isSecureMode() {
        try {
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getContext());
            return !pref.getBoolean(PREF_KEY_SHOW_DOTFILES, SHOW_DOTFILES_DEFAULT);
        } catch (Exception e) {
            // Do nothing
        }
        return true;
    }

    /*
     * This function requires "implementation 'commons-io:commons-io:2.6'" in build.gradle
     * for the following reasons.
     * File.delete() cannot delete a non-empty directory.
     * In directory deletion simply using recursion, if the symbolic link is deleted,
     * the target directory is also deleted.
     */
    private void deleteFileOrFolder(File fileOrDirectory) {
        if (fileOrDirectory == null || !fileOrDirectory.exists()) return;
        try {
            if (fileOrDirectory.isDirectory()) {
                FileUtils.deleteDirectory(fileOrDirectory);
            } else {
                if (!fileOrDirectory.delete()) {
                    Log.v(TAG, "Unable to delete " + (fileOrDirectory.isDirectory() ? "directory " : "file ") + fileOrDirectory.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            Log.v(TAG, "Exception in FileUtils.deleteDirectory(). " + e.toString() + " " + fileOrDirectory.getAbsolutePath());
        }
    }

}
