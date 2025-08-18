package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.provider.Settings;

import java.io.*;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import android.content.pm.PackageManager;
import android.Manifest;
import android.content.DialogInterface;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.util.Log;

public final class MainMenuActivity extends PreferenceActivity {
    final private int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    public static String PACKAGE_NAME;
    boolean checkPermissions = false;
    private ProgressDialog progressDialog;

    private final String[] ASSET_FOLDERS = {
            "assets", "autoconfig", "cores", "database",
            "filters", "info", "overlays", "shaders", "system"
    };

    public void showMessageOKCancel(String message, DialogInterface.OnClickListener onClickListener) {
        new AlertDialog.Builder(this).setMessage(message)
                .setPositiveButton("OK", onClickListener).setCancelable(false)
                .setNegativeButton("Cancel", null).create().show();
    }

    private boolean addPermission(List<String> permissionsList, String permission) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(permission);
                if (!shouldShowRequestPermissionRationale(permission))
                    return false;
            }
        }
        return true;
    }

    public void checkRuntimePermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            List<String> permissionsNeeded = new ArrayList<>();
            final List<String> permissionsList = new ArrayList<>();

            if (!addPermission(permissionsList, Manifest.permission.READ_EXTERNAL_STORAGE))
                permissionsNeeded.add("Read External Storage");
            if (!addPermission(permissionsList, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                permissionsNeeded.add("Write External Storage");

            if (permissionsList.size() > 0) {
                checkPermissions = true;
                if (permissionsNeeded.size() > 0) {
                    String message = "You need to grant access to " + permissionsNeeded.get(0);
                    for (int i = 1; i < permissionsNeeded.size(); i++)
                        message = message + ", " + permissionsNeeded.get(i);

                    showMessageOKCancel(message,
                            (dialog, which) -> {
                                if (which == AlertDialog.BUTTON_POSITIVE) {
                                    requestPermissions(permissionsList.toArray(new String[0]),
                                            REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                                }
                            });
                } else {
                    requestPermissions(permissionsList.toArray(new String[0]),
                            REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                }
            }
        }

        if (!checkPermissions) {
            new AssetTask().execute();
        }
    }

    public void finalStartup() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        Intent retro = new Intent(this, RetroActivityFuture.class);
        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        startRetroActivity(
                retro,
                null,
                prefs.getString("libretro_path", getApplicationInfo().dataDir + "/cores/"),
                UserPreferences.getDefaultConfigPath(this),
                Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
                getApplicationInfo().dataDir,
                getApplicationInfo().sourceDir);
        startActivity(retro);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS:
                for (int i = 0; i < permissions.length; i++) {
                    if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                        checkPermissions = true;
                        showMessageOKCancel("Permissions are required to continue.",
                                (dialog, which) -> checkRuntimePermissions());
                        return;
                    }
                }
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
                break;
        }
        new AssetTask().execute();
    }

    public static void startRetroActivity(Intent retro, String contentPath, String corePath,
                                          String configFilePath, String imePath, String dataDirPath, String dataSourcePath) {
        if (contentPath != null) retro.putExtra("ROM", contentPath);
        retro.putExtra("LIBRETRO", corePath);
        retro.putExtra("CONFIGFILE", configFilePath);
        retro.putExtra("IME", imePath);
        retro.putExtra("DATADIR", dataDirPath);
        retro.putExtra("APK", dataSourcePath);
        retro.putExtra("SDCARD", Environment.getExternalStorageDirectory().getAbsolutePath());
        String external = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/" + PACKAGE_NAME + "/files";
        retro.putExtra("EXTERNAL", external);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        UserPreferences.updateConfigFile(this);
        checkRuntimePermissions();
    }

    private class AssetTask extends AsyncTask<Void, Integer, Void> {
        private final File srcDir = new File("/data/data/com.retroarch");
        private final File destDir = new File(Environment.getExternalStorageDirectory(), "Android/media/com.retroarch");

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(MainMenuActivity.this);
            progressDialog.setMessage("Preparando arquivos...");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setMax(100);
            progressDialog.setProgress(0);
            progressDialog.setCancelable(false);
            progressDialog.show();
        }

        @Override
        protected Void doInBackground(Void... voids) {
            try {
                if (!srcDir.exists()) return null;

                // Multithread para mover subpastas
                if (destDir.exists()) deleteRecursively(destDir);
                if (!destDir.getParentFile().exists()) destDir.getParentFile().mkdirs();
                File[] children = srcDir.listFiles();
                if (children != null) {
                    AtomicInteger progress = new AtomicInteger(0);
                    ExecutorService executor = Executors.newFixedThreadPool(Math.min(children.length, 4));

                    for (File child : children) {
                        executor.submit(() -> {
                            try {
                                moveFile(child, new File(destDir, child.getName()));
                            } catch (Exception e) {
                                Log.e("AssetTask", "Erro movendo " + child.getName(), e);
                            } finally {
                                int prog = progress.incrementAndGet() * 100 / children.length;
                                publishProgress(prog);
                            }
                        });
                    }

                    executor.shutdown();
                    while (!executor.isTerminated()) Thread.sleep(50);
                }

                // Cria retroarch.cfg no diretório padrão
                File cfgDir = new File(Environment.getExternalStorageDirectory(),
                        "Android/data/com.retroarch/files");
                if (!cfgDir.exists()) cfgDir.mkdirs();
                File cfgFile = new File(cfgDir, "retroarch.cfg");
                if (!cfgFile.exists()) cfgFile.createNewFile();

                try (FileOutputStream out = new FileOutputStream(cfgFile, false)) {
                    StringBuilder content = new StringBuilder("# RetroArch DRG cfg\n");
                    for (String folder : ASSET_FOLDERS) {
                        File f = new File(destDir, folder);
                        if (f.exists()) content.append(folder).append("_directory = \"").append(f.getAbsolutePath()).append("\"\n");
                    }
                    out.write(content.toString().getBytes());
                }

            } catch (Exception e) {
                Log.e("AssetTask", "Erro geral", e);
            }
            return null;
        }

        private void moveFile(File src, File dst) throws IOException {
            if (src.isDirectory()) {
                if (!dst.exists()) dst.mkdirs();
                File[] files = src.listFiles();
                if (files != null) {
                    for (File f : files) moveFile(f, new File(dst, f.getName()));
                }
                src.delete();
            } else {
                if (!src.renameTo(dst)) {
                    try (InputStream in = new FileInputStream(src);
                         OutputStream out = new FileOutputStream(dst)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    }
                    src.delete();
                }
            }
        }

        private void deleteRecursively(File file) {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) for (File child : children) deleteRecursively(child);
            }
            file.delete();
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (values.length > 0) {
                progressDialog.setProgress(values[0]);
                progressDialog.setMessage("Extraindo... " + values[0] + "%");
            }
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            progressDialog.dismiss();
            finalStartup();
        }
    }
}
