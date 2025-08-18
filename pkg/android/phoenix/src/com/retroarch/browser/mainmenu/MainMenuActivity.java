package com.retroarch.browser.mainmenu;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;

import com.retroarch.browser.retroactivity.RetroActivityFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class MainMenuActivity extends Activity {

    private static final int REQUEST_CODE_PERMISSIONS = 124;
    private static String PACKAGE_NAME;
    private static final String CUSTOM_BASE_DIR = Environment.getExternalStorageDirectory().getAbsolutePath()
            + "/Android/media/com.retroarch/RetroArch-DRG";
    private ProgressDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        checkPermissionsAndStart();
    }

    private void checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean read = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            boolean write = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

            if (!read || !write) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_CODE_PERMISSIONS);
                return;
            }
        }
        startExtractionTask();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    showMessageOKCancel("Permissão necessária para continuar: " + permissions[i],
                            (dialog, which) -> checkPermissionsAndStart());
                    return;
                }
            }
        }
        startExtractionTask();
    }

    private void showMessageOKCancel(String message, DialogInterface.OnClickListener onClickListener) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("OK", onClickListener)
                .setCancelable(false)
                .setNegativeButton("Cancel", null)
                .create()
                .show();
    }

    private void startExtractionTask() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean firstRun = prefs.getBoolean("first_run_extraction_done", false);

        if (firstRun) {
            startRetroActivityFuture();
            return;
        }

        progressDialog = new ProgressDialog(this);
        progressDialog.setTitle("Extraindo RetroArch-DRG");
        progressDialog.setMessage("Extraindo base.apk");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        progressDialog.setCancelable(false);
        progressDialog.show();

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                try {
                    String[] folders = new String[]{"assets", "autoconfig", "cores", "database", "filters", "info", "overlays", "shaders", "system"};
                    for (String folder : folders) {
                        copyAssetFolder(folder, new File(CUSTOM_BASE_DIR, folder));
                    }
                    generateRetroArchConfigOnce();
                } catch (Exception e) {
                    Log.e("MainMenuActivity", "Erro na extração", e);
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                PreferenceManager.getDefaultSharedPreferences(MainMenuActivity.this)
                        .edit()
                        .putBoolean("first_run_extraction_done", true)
                        .apply();
                startRetroActivityFuture();
            }
        }.execute();
    }

    private void copyAssetFolder(String assetPath, File outDir) throws IOException {
        String[] list = getAssets().list(assetPath);
        if (list == null || list.length == 0) {
            copyAssetFile(assetPath, outDir);
        } else {
            if (!outDir.exists()) outDir.mkdirs();
            for (String child : list) {
                String childPath = assetPath + "/" + child;
                copyAssetFolder(childPath, new File(outDir, child));
            }
        }
    }

    private void copyAssetFile(String assetPath, File outFile) throws IOException {
        if (!outFile.getParentFile().exists()) outFile.getParentFile().mkdirs();
        InputStream in = getAssets().open(assetPath);
        OutputStream out = new FileOutputStream(outFile, false);
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        out.flush();
        out.close();
    }

    private void generateRetroArchConfigOnce() {
        File cfg = new File(Environment.getExternalStorageDirectory().getAbsolutePath()
                + "/Android/data/com.retroarch/files/retroarch.cfg");
        if (cfg.exists()) return; // Não sobrescreve se já existir

        try {
            if (!cfg.getParentFile().exists()) cfg.getParentFile().mkdirs();
            FileOutputStream out = new FileOutputStream(cfg, false);
            String content = ""
                    + "system_directory = \"" + CUSTOM_BASE_DIR + "/system\"\n"
                    + "core_directory = \"" + CUSTOM_BASE_DIR + "/cores\"\n"
                    + "assets_directory = \"" + CUSTOM_BASE_DIR + "/assets\"\n"
                    + "database_directory = \"" + CUSTOM_BASE_DIR + "/database\"\n"
                    + "filters_directory = \"" + CUSTOM_BASE_DIR + "/filters\"\n"
                    + "info_directory = \"" + CUSTOM_BASE_DIR + "/info\"\n"
                    + "overlays_directory = \"" + CUSTOM_BASE_DIR + "/overlays\"\n"
                    + "shaders_directory = \"" + CUSTOM_BASE_DIR + "/shaders\"\n";
            out.write(content.getBytes());
            out.flush();
            out.close();
        } catch (IOException e) {
            Log.e("MainMenuActivity", "Erro ao criar retroarch.cfg", e);
        }
    }

    private void startRetroActivityFuture() {
        Intent retro = new Intent(this, RetroActivityFuture.class);
        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        startRetroActivity(retro,
                null,
                CUSTOM_BASE_DIR + "/cores/",
                Environment.getExternalStorageDirectory().getAbsolutePath()
                        + "/Android/data/com.retroarch/files/retroarch.cfg",
                Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
                CUSTOM_BASE_DIR,
                getApplicationInfo().sourceDir);

        startActivity(retro);
        finish();
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
} 
