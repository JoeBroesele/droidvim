package jackpal.androidterm;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.FileObserver;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

class SyncFileObserverMru {
    private final Uri uri;
    private final String path;
    SyncFileObserverMru() {
        this.uri = null;
        this.path = null;
    }

    SyncFileObserverMru(Uri uri, String path) {
        this.uri = uri;
        this.path = path;
    }

    Uri getUri() {
        return uri;
    }

    String getPath() {
        return path;
    }
}

public class SyncFileObserver extends RecursiveFileObserver {
    static class Info {
        private String uriString;
        private String hash;
        private long time;

        Info() {
            this.uriString = null;
            this.hash = null;
            this.time = -1;
        }

        Info(Uri uri, String hash) {
            this.uriString = uri.toString();
            this.hash = hash;
            this.time = System.currentTimeMillis();
        }

        Info(Uri uri, String hash, long millis) {
            this.uriString = uri.toString();
            this.hash = hash;
            this.time = millis;
        }

        Uri getUri() {
            return Uri.parse(uriString);
        }

        void setUri(Uri uri) {
            if (uri != null) uriString = uri.toString();
        }

        String getHash() {
            return hash;
        }

        void setHash(String hash) {
            if (hash != null) this.hash = hash;
        }

        long getTime() {
            return time;
        }

        void setTime(long millis) {
            time = millis;
        }

        int compareTo(Info value) {
            long c = value.getTime() - time;
            if (c == 0) return 0;
            else return (c > 0 ? 1 : -1);
        }

        @NonNull
        @Override
        public String toString() {
            String str = null;
            try {
                JSONObject jsonOneData = new JSONObject();
                jsonOneData.put("uriString", this.uriString);
                jsonOneData.put("hash", this.hash);
                jsonOneData.put("time", String.valueOf(this.time));
                str = jsonOneData.toString(4);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            assert str != null;
            return str;
        }
    }

    static private final Map<String, Info> mHashMap = new HashMap<>();
    private final File mCacheDir;
    private static String mBackupFilesDir = "/storage/emulated/0";
    private ContentResolver mContentResolver = null;
    private static Object mObjectActivity = null;
    private final boolean mConfirmDeleteFromStorage = false;
    private boolean mActive;

    SyncFileObserver(String path) {
        this(path, ALL_EVENTS);
    }

    private SyncFileObserver(String path, int mask) {
        super(path, mask);
        mCacheDir = new File(path);
        mActive = true;
    }

    static public void setBackupFilesDir(String path) {
        mBackupFilesDir = path;
    }

    private boolean isConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = null;
        if (cm != null) info = cm.getActiveNetworkInfo();
        if (info != null) {
            return info.isConnected();
        }
        return false;
    }

    @Override
    public void onEvent(int event, String path) {
        if (!mActive || !mHashMap.containsKey(path)) return;
        Info info = mHashMap.get(path);
        if (info == null) return;
        switch (event) {
            // case FileObserver.DELETE_SELF:
            case FileObserver.DELETE:
                confirmDelete(info.getUri(), new File(path), mContentResolver);
                break;
            case FileObserver.OPEN:
                info.setTime(System.currentTimeMillis());
                break;
            // case FileObserver.MODIFY:
            case FileObserver.CLOSE_WRITE:
                info.setTime(System.currentTimeMillis());
                flushCache(info.getUri(), new File(path), mContentResolver);
                break;
            // case FileObserver.ACCESS:
            //     info.setTime(System.currentTimeMillis());
            //     break;
            default:
                break;
        }
    }

    void setContentResolver(ContentResolver cr) {
        mContentResolver = cr;
    }

    void setActivity(AppCompatActivity activity) {
        mObjectActivity = activity;
        if (activity != null) setContentResolver(activity.getContentResolver());
    }

    AppCompatActivity getActivity() {
        return (AppCompatActivity) mObjectActivity;
    }

    String getObserverDir() {
        return mCacheDir.getAbsolutePath();
    }

    private static int mMaxSyncFiles = 300;

    public void setMaxSyncFiles(int max) {
        mMaxSyncFiles = Math.max(max, 100);
    }

    LinkedList<SyncFileObserverMru> getMRU() {
        if (mCacheDir == null) return null;
        List<Map.Entry<String, Info>> list_entries = new ArrayList<>(mHashMap.entrySet());

        Collections.sort(list_entries, (obj1, obj2) -> obj1.getValue().compareTo(obj2.getValue()));
        LinkedList<SyncFileObserverMru> mru = new LinkedList<>();//[1]
        for (Map.Entry<String, Info> map : list_entries) {
            Info info = map.getValue();
            mru.add(new SyncFileObserverMru(info.getUri(), map.getKey()));
        }
        return mru;
    }

    void clearOldCache() {
        if (mCacheDir == null) return;
        if (mHashMap.size() <= mMaxSyncFiles) return;

        List<Map.Entry<String, Info>> list_entries = new ArrayList<>(mHashMap.entrySet());

        Collections.sort(list_entries, (obj1, obj2) -> obj1.getValue().compareTo(obj2.getValue()));
        int size = list_entries.size() - 1;
        int minSize = (size * 3) / 4;
        for (int i = size; i > minSize; i--) {
            String path = list_entries.get(i).getKey();
            stopWatching(path);
            list_entries.remove(i);
            new File(path).delete();
        }
        mHashMap.clear();
        for (Map.Entry<String, Info> map : list_entries) {
            mHashMap.put(map.getKey(), map.getValue());
        }
        deleteEmptyDirectory(mCacheDir);
    }

    void clearCache() {
        mActive = true;
        if (mCacheDir == null) return;
        mHashMap.clear();
        if (mCacheDir.isDirectory()) deleteFileOrFolderRecursive(mCacheDir);
        mCacheDir.mkdirs();
        stopWatching();
    }

    /*
     * CAUTION: This function deletes reference directory of symbolic link. (Android N and earlier)
     */
    private boolean deleteFileOrFolderRecursive(File fileOrDirectory) {
        if (fileOrDirectory == null || !fileOrDirectory.exists()) return true;
        try {
            if (fileOrDirectory.isDirectory()) {
                File[] files = fileOrDirectory.listFiles();
                if (files != null && files.length > 0) {
                    for (File file : files) {
                        deleteFileOrFolderRecursive(file);
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return Files.deleteIfExists(fileOrDirectory.toPath());
            } else {
                return fileOrDirectory.delete();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteEmptyDirectory(File d) {
        if (d == null) return;
        File[] files = d.listFiles();
        if (files == null) {
            return;
        } else if (files.length == 0) {
            d.delete();
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                deleteEmptyDirectory(f);
            }
        }
    }

    private final static String HASH_ERROR = "HASH_ERROR";
    private final static String HASH_ALGORITHM = "SHA-1";

    public static String normalizePath(String srcPath) {
        if (srcPath == null) return "";
        String path = srcPath.replaceAll("%+", "/");
        path = path.replaceAll("//+", "/");
        path = new File(path).getAbsolutePath();
        return path;
    }

    boolean putUriAndLoad(Uri uri, String srcPath) {
        mActive = true;
        try {
            mContentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            mContentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            e.printStackTrace();
        }
        String path = srcPath.replaceAll("//+", "/");
        path = new File(path).getAbsolutePath();
        String hash = makeCache(uri, new File(path));
        if (!hash.equals(HASH_ERROR)) {
            mHashMap.put(path, new Info(uri, hash, System.currentTimeMillis()));
            return true;
        }
        return false;
    }

    boolean add(SyncFileObserverMru mru) {
        return putUriAndLoad(mru.getUri(), mru.getPath());
    }

    void remove(SyncFileObserverMru mru) {
        mHashMap.remove(mru.getPath());
    }

    private String makeCache(final Uri uri, final File dst) {
        return makeCache(uri, dst, mContentResolver);
    }

    static public String ErrorState = "";

    private String makeCache(final Uri uri, final File dst, final ContentResolver contentResolver) {
        if (dst == null || uri == null || contentResolver == null) return "";

        String hashValue = "";
        ErrorState = hashValue;
        mActive = false;
        File temp = new File(mCacheDir, ".temp");
        File parent = temp.getParentFile();
        if (parent != null) parent.mkdirs();
        deleteFileOrFolderRecursive(temp);
        try {
            MessageDigest md;
            try {
                md = MessageDigest.getInstance(HASH_ALGORITHM);
            } catch (NoSuchAlgorithmException e) {
                md = null;
            }

            InputStream is = contentResolver.openInputStream(uri);
            BufferedInputStream reader = new BufferedInputStream(is);

            OutputStream os = new FileOutputStream(temp);
            BufferedOutputStream writer = new BufferedOutputStream(os);
            byte[] buf = new byte[4096];
            int len;
            while ((len = reader.read(buf)) != -1) {
                if (md != null) md.update(buf, 0, len);
                writer.write(buf, 0, len);
            }
            writer.flush();
            writer.close();
            reader.close();
            if (md != null) {
                byte[] digest = md.digest();
                hashValue = toHexString(digest);
            }
            deleteFileOrFolderRecursive(dst);
            parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            renameFile(temp, dst);
        } catch (Exception e) {
            hashValue = HASH_ERROR;
            ErrorState = e.getLocalizedMessage();
        }
        if (dst.getAbsolutePath().startsWith(getObserverDir())) {
            startWatching();
        } else {
            startWatching(dst.getAbsolutePath());
        }
        mActive = true;
        return hashValue;
    }

    private void errorDialog(final AppCompatActivity activity, final String title, final String message) {
        if (activity != null) activity.runOnUiThread(() -> {
            if (!mDialogIsActive) {
                AlertDialog.Builder bld = new AlertDialog.Builder(activity);
                bld.setIcon(android.R.drawable.ic_dialog_alert);
                if (title != null) bld.setTitle(title);
                if (message != null) bld.setMessage(message);
                bld.setPositiveButton(android.R.string.ok, (dialog, id) -> {
                    dialog.cancel();
                    mDialogIsActive = false;
                });
                bld.setOnCancelListener(dialog -> mDialogIsActive = false);
                bld.setCancelable(false);
                try {
                    bld.create().show();
                    mDialogIsActive = true;
                } catch (Exception e) {
                    // do nothing
                }
            }
        });
    }

    private boolean renameFile(File src, File dst) {
        try {
            deleteFileOrFolderRecursive(dst);
            return src.renameTo(dst);
        } catch (Exception e) {
            return false;
        }
    }

    static String digest(InputStream is)
            throws NoSuchAlgorithmException, IOException {
        MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);

        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) {
            md.update(buf, 0, len);
        }
        byte[] digest = md.digest();
        return toHexString(digest);
    }

    private static String toHexString(byte[] digest) {
        StringBuilder buff = new StringBuilder();
        for (byte b : digest) {
            buff.append(String.format("%1$02x", b));
        }
        return buff.toString();
    }

    public static final int HASH_CHECK_MODE_NONE  = 0;
    public static final int HASH_CHECK_MODE_READ  = 1;
    public static final int HASH_CHECK_MODE_WRITE = 2;
    public static final int HASH_CHECK_MODE_READ_WRITE = HASH_CHECK_MODE_READ + HASH_CHECK_MODE_WRITE;
    private static final int HASH_CHECK_MODE_WRITE_SEC = 3000;
    private static int HASH_CHECK_MODE = HASH_CHECK_MODE_NONE;
    private static int CLOUD_STORAGE_HASH_CHECK_MODE = HASH_CHECK_MODE_NONE;

    /*
     * HASH_CHECK_MODE
     * HASH_CHECK_MODE_NONE  : No check
     * HASH_CHECK_MODE_READ  : Check destination hash before Write.
     * HASH_CHECK_MODE_WRITE : Write check after 3 seconds.
     */
    static public void setHashCheckMode(int mode) {
        HASH_CHECK_MODE = mode;
    }

    static public void setCloudStorageHashCheckMode(int mode) {
        CLOUD_STORAGE_HASH_CHECK_MODE = mode;
    }

    private void flushCache(final Uri uri, final File file, final ContentResolver contentResolver) {
        flushCache(uri, file, contentResolver, false);
    }

    final String APP_DROPBOX = "com.dropbox";
    final String APP_GOOGLEDRIVE = "com.google.android.apps.docs";
    final String APP_ONEDRIVE = "com.microsoft.skydrive";
    private void flushCache(final Uri uri, final File file, final ContentResolver contentResolver, boolean overWrite) {
        if (contentResolver == null) return;
        int hashCheckMode = HASH_CHECK_MODE;
        if (uri.toString().startsWith("content://" + APP_DROPBOX)) hashCheckMode = CLOUD_STORAGE_HASH_CHECK_MODE;
        if (uri.toString().startsWith("content://" + APP_GOOGLEDRIVE)) hashCheckMode = CLOUD_STORAGE_HASH_CHECK_MODE;
        if (uri.toString().startsWith("content://" + APP_ONEDRIVE)) hashCheckMode = CLOUD_STORAGE_HASH_CHECK_MODE;
        if (overWrite) hashCheckMode = HASH_CHECK_MODE_NONE;
        if (hashCheckMode == HASH_CHECK_MODE_NONE) {
            doFlushCache(uri, file, contentResolver);
            return;
        }

        try {
            final String oldHash = mHashMap.get(file.getAbsolutePath()).getHash();
            InputStream dstIs = contentResolver.openInputStream(uri);
            if (dstIs != null) {
                boolean readCheck = (hashCheckMode & HASH_CHECK_MODE_READ) != 0;
                boolean writeCheck = (hashCheckMode & HASH_CHECK_MODE_WRITE) != 0;
                String hashDst = getHash(dstIs);
                if (hashDst.equals("") && !writeCheck) {
                    doFlushCache(uri, file, contentResolver);
                    return;
                }
                InputStream srcIs = new FileInputStream(file.getAbsolutePath());
                final String hashSrc = getHash(srcIs);
                if (hashDst.equals(hashSrc)) return;
                if (readCheck && !hashDst.equals(oldHash)) {
                    hashErrorDialog((AppCompatActivity) mObjectActivity, uri, file, contentResolver);
                } else {
                    doFlushCache(uri, file, contentResolver);
                    if (writeCheck) {
                        new Thread() {
                            @Override
                            public void run() {
                                try {
                                    Thread.sleep(HASH_CHECK_MODE_WRITE_SEC);
                                    InputStream is = contentResolver.openInputStream(uri);
                                    String hashDst = getHash(is);
                                    if (!hashDst.equals(hashSrc)) {
                                        String title = ((AppCompatActivity) mObjectActivity).getString(R.string.storage_write_check_error_title);
                                        String message = ((AppCompatActivity) mObjectActivity).getString(R.string.storage_write_check_error);
                                        errorDialog((AppCompatActivity) mObjectActivity, title, message);
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }.start();
                    }
                }
            } else {
                urlErrorDialog((AppCompatActivity) mObjectActivity, contentResolver);
            }
        } catch (Exception e) {
            doFlushCache(uri, file, contentResolver);
            flushCacheErrorDialog((AppCompatActivity) mObjectActivity, e.getMessage(), uri, file, contentResolver, e);
        }
    }

    private String getHash(InputStream is) {
        String hash = "";
        try {
            hash = digest(is);
            is.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return hash;
    }

    private static boolean mDialogIsActive = false;

    private void doFlushCache(final Uri uri, final File file, final ContentResolver contentResolver) {
        if (contentResolver == null) return;
        if (file == null || !file.exists()) return;

        ParcelFileDescriptor pfd = null;
        FileInputStream fis = null;
        FileOutputStream fos = null;
        BufferedInputStream reader;
        BufferedOutputStream writer;

        boolean isWriteError = false;
        try {
            if (uri.toString().startsWith("content://" + APP_ONEDRIVE)) {
                pfd = contentResolver.openFileDescriptor(uri, "w");
                if (pfd.canDetectErrors()) pfd.checkError();
                fos = new FileOutputStream(pfd.getFileDescriptor());
                fos.getChannel().truncate(0);
            } else {
                pfd = contentResolver.openFileDescriptor(uri, "wt");
                if (pfd.canDetectErrors()) pfd.checkError();
                fos = new FileOutputStream(pfd.getFileDescriptor());
            }
            writer = new BufferedOutputStream(fos);
            reader = new BufferedInputStream(new FileInputStream(file));
            isWriteError = true;
            copyFileToStream(file, reader, writer);
        } catch (Exception e) {
            flushCacheError(e.getMessage(), uri, file, contentResolver, isWriteError);
        } finally {
            try {
                if (pfd != null) pfd.close();
                if (fos != null) fos.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void flushCacheError(final String errorMessage, final Uri uri, final File file, final ContentResolver contentResolver, boolean isWriteError) {
        if (errorMessage == null) return;
        String message = errorMessage;
        if (isWriteError) {
            Calendar cal = Calendar.getInstance();
            String dst = String.valueOf(cal.get(Calendar.YEAR)) +
                    String.valueOf(cal.get(Calendar.MONTH) + 1) +
                    String.valueOf(cal.get(Calendar.DATE)) +
                    String.valueOf(cal.get(Calendar.HOUR_OF_DAY)) +
                    String.valueOf(cal.get(Calendar.MINUTE)) +
                    String.format("%02d", cal.get(Calendar.SECOND)) +
                    "-" + file.getName();
            File dstFile = new File(mBackupFilesDir, dst);
            AppCompatActivity activity = (AppCompatActivity) mObjectActivity;
            try {
                copyFile(file, dstFile);
                message = "The backup files are located in the following directory:\n";
                if (activity != null) message = activity.getString(R.string.storage_write_backup_file);
                message += dstFile.getAbsolutePath();
            } catch (Exception backupError) {
                message = " !!! IMPORTANT !!!\nFile has been lost.\nSave the contents of this buffer.";
                if (activity != null) message = activity.getString(R.string.storage_write_backup_file_error);
            }
        }
        writeErrorDialog((AppCompatActivity) mObjectActivity, file, contentResolver, message);
    }

    private void copyFile(File src, File dst) throws Exception {
        copyFile(new FileInputStream(src), new FileOutputStream(dst));
    }

    private void copyFile(FileInputStream src, FileOutputStream dst) throws Exception {
        try (InputStream in = src) {
            try (OutputStream out = dst) {
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
        }
    }

    private void copyFileToStream(File file, BufferedInputStream reader, BufferedOutputStream writer)
            throws Exception {
        try {
            MessageDigest md;
            try {
                md = MessageDigest.getInstance(HASH_ALGORITHM);
            } catch (NoSuchAlgorithmException e) {
                md = null;
            }

            byte[] buf = new byte[1024];
            int len;
            while ((len = reader.read(buf)) != -1) {
                if (md != null) md.update(buf, 0, len);
                writer.write(buf, 0, len);
            }
            if (md != null) {
                byte[] digest = md.digest();
                String hashValue = toHexString(digest);
                mHashMap.get(file.getAbsolutePath()).setHash(hashValue);
            }
            writer.close();
            reader.close();
        } finally {
            try {
                writer.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                reader.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void urlErrorDialog(final AppCompatActivity activity, final ContentResolver contentResolver) {
        if (activity != null) activity.runOnUiThread(() -> {
            if (!mDialogIsActive) {
                AlertDialog.Builder bld = new AlertDialog.Builder(activity);
                bld.setIcon(android.R.drawable.ic_dialog_alert);
                bld.setTitle(R.string.storage_write_check_error_title);
                bld.setMessage(R.string.storage_url_error);
                bld.setPositiveButton(android.R.string.ok, (dialog, id) -> {
                    dialog.cancel();
                    mDialogIsActive = false;
                });
                bld.setNeutralButton(R.string.file_chooser, (dialog, id) -> {
                    dialog.cancel();
                    mDialogIsActive = false;
                    try {
                        Term term = (Term) activity;
                        term.intentFilePicker();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                bld.setOnCancelListener(dialog -> mDialogIsActive = false);
                bld.setCancelable(false);
                try {
                    bld.create().show();
                    mDialogIsActive = true;
                } catch (Exception e) {
                    // do nothing
                }
            }
        });
    }

    private void flushCacheErrorDialog(final AppCompatActivity activity, final String errorMessage, final Uri uri, final File file, final ContentResolver contentResolver, final Exception e) {
        if (activity != null) activity.runOnUiThread(() -> {
            if (!mDialogIsActive) {
                AlertDialog.Builder bld = new AlertDialog.Builder(activity);
                bld.setIcon(android.R.drawable.ic_dialog_alert);
                bld.setTitle(R.string.storage_write_error_title);
                bld.setMessage(errorMessage + "\n" + R.string.storage_flush_cache_error + "\n\n" + e.getMessage());
                bld.setPositiveButton(android.R.string.ok, (dialog, id) -> {
                    dialog.cancel();
                    mDialogIsActive = false;
                    // doFlushCache(uri, file, contentResolver);
                });
                bld.setOnCancelListener(dialog -> mDialogIsActive = false);
                bld.setCancelable(false);
                try {
                    bld.create().show();
                    mDialogIsActive = true;
                } catch (Exception e1) {
                    // do nothing
                }
            }
        });
    }

    private void hashErrorDialog(final AppCompatActivity activity, final Uri uri, final File file, final ContentResolver contentResolver) {
        if (activity != null) activity.runOnUiThread(() -> {
            if (!mDialogIsActive) {
                AlertDialog.Builder bld = new AlertDialog.Builder(activity);
                bld.setIcon(android.R.drawable.ic_dialog_alert);
                bld.setTitle(R.string.storage_hash_error_title);
                bld.setMessage(R.string.storage_hash_error);
                bld.setNegativeButton(android.R.string.no, (dialog, id) -> {
                    dialog.cancel();
                    mDialogIsActive = false;
                });
                bld.setNeutralButton(R.string.storage_hash_overwrite, (dialog, id) -> {
                    dialog.cancel();
                    mDialogIsActive = false;
                    flushCache(uri, file, contentResolver, true);
                });
                bld.setPositiveButton(R.string.file_chooser, (dialog, id) -> {
                    dialog.cancel();
                    mDialogIsActive = false;
                    try {
                        Term term = (Term) activity;
                        term.intentFilePicker();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                bld.setOnCancelListener(dialog -> mDialogIsActive = false);
                bld.setCancelable(false);
                try {
                    bld.create().show();
                    mDialogIsActive = true;
                } catch (Exception e) {
                    // do nothing
                }
            }
        });
    }

    private void writeErrorDialog(final AppCompatActivity activity, File file, ContentResolver contentResolver, String errorMessage) {
        if (activity != null) activity.runOnUiThread(() -> {
            if (!mDialogIsActive) {
                String title = activity.getString(R.string.storage_write_error_title);
                String message = activity.getString(R.string.storage_write_error);
                message += "\n\n" + errorMessage;
                boolean isConnected = mObjectActivity != null && isConnected(activity.getApplicationContext());
                if (!isConnected) {
                    title = activity.getString(R.string.storage_offline_write_error_title);
                    message = activity.getString(R.string.storage_offline_write_error);
                }
                AlertDialog.Builder bld = new AlertDialog.Builder(activity);
                bld.setIcon(android.R.drawable.ic_dialog_alert);
                bld.setTitle(title);
                bld.setMessage(message);
                bld.setPositiveButton(android.R.string.ok, (dialog, id) -> {
                    dialog.dismiss();
                    mDialogIsActive = false;
                });
                if (isConnected) {
                    bld.setNeutralButton(R.string.file_chooser, (dialog, id) -> {
                        dialog.dismiss();
                        mDialogIsActive = false;
                        try {
                            Term term = (Term) activity;
                            term.intentFilePicker();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }
                bld.setOnCancelListener(dialog -> mDialogIsActive = false);
                bld.setCancelable(false);
                try {
                    bld.create().show();
                    mDialogIsActive = true;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @SuppressLint("NewApi")
    private void confirmDelete(final Uri uri, final File path, final ContentResolver contentResolver) {
        if (!mConfirmDeleteFromStorage || mObjectActivity == null) return;
        final AppCompatActivity activity = (AppCompatActivity) mObjectActivity;
        activity.runOnUiThread(() -> {
            String file = path.getName();
            final AlertDialog.Builder b = new AlertDialog.Builder(activity);
            b.setIcon(android.R.drawable.ic_dialog_alert);
            b.setTitle(R.string.storage_delete_file);
            b.setMessage(file);
            b.setPositiveButton(R.string.delete_file, (dialog, id) -> {
                try {
                    DocumentsContract.deleteDocument(contentResolver, uri);
                    deleteEmptyDirectory(mCacheDir);
                    dialog.dismiss();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            b.setNegativeButton(android.R.string.no, null);
            b.show();
        });
    }

    void restoreStartWatching() {
        if (mHashMap.size() > 0) {
            String cacheDir = mCacheDir.getAbsolutePath();
            for (Map.Entry<String, Info> entry : mHashMap.entrySet()) {
                String path = entry.getKey();
                if (!path.startsWith(cacheDir)) startWatching(path);
            }
        }
        startWatching();
    }

    boolean restoreHashMap(File hashMapFile) {
        if (hashMapFile.exists()) {
            try {
                FileInputStream fis = new FileInputStream(hashMapFile.getAbsolutePath());
                int size = fis.available();
                byte[] buffer = new byte[size];
                fis.read(buffer);
                fis.close();

                String json = new String(buffer);
                JSONObject jsonObject = new JSONObject(json);
                JSONArray jsonArray = jsonObject.getJSONArray("mHashMap");
                for (int i = 0; i < jsonArray.length(); i++) {
                    try {
                        JSONObject jsonOneRecord = jsonArray.getJSONObject(i);
                        String path = (String) jsonOneRecord.get("path");
                        Info info = new Info();
                        info.uriString = (String) jsonOneRecord.get("info.uriString");
                        info.hash = (String) jsonOneRecord.get("info.hash");
                        info.time = Long.parseLong((String) jsonOneRecord.get("info.time"));
                        mHashMap.put(path, info);
                    } catch (Exception e) {
                        // invalid data
                        e.printStackTrace();
                    }
                }
                fis.close();
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }
        mActive = true;
        return true;
    }

    boolean saveHashMap(File hashMapFile) {
        try {
            JSONObject jsonObject = new JSONObject();
            JSONArray jsonArray = new JSONArray();

            if (mHashMap.size() == 0) return true;
            for (Map.Entry<String, Info> entry : mHashMap.entrySet()) {
                JSONObject jsonOneData = new JSONObject();
                jsonOneData.put("path", entry.getKey());
                Info info = entry.getValue();
                jsonOneData.put("info.uriString", info.uriString);
                jsonOneData.put("info.hash", info.hash);
                jsonOneData.put("info.time", String.valueOf(info.time));
                jsonArray.put(jsonOneData);
            }
            jsonObject.put("mHashMap", jsonArray);

            FileWriter fileWriter = new FileWriter(hashMapFile);
            BufferedWriter bw = new BufferedWriter(fileWriter);
            PrintWriter pw = new PrintWriter(bw);
            String str = jsonObject.toString(4);
            pw.write(str);
            pw.close();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
}

