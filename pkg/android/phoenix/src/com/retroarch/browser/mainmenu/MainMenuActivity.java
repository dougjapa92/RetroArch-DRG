package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import android.content.pm.PackageManager;
import android.Manifest;
import android.content.DialogInterface;
import android.app.AlertDialog;
import android.util.Log;

public final class MainMenuActivity extends PreferenceActivity {
    private static final int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    private static final String MEDIA_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/media/com.retroarch";
    private static final String EXTRACT_FLAG = "extracted_once";
    public static String PACKAGE_NAME;
    private boolean checkPermissions = false;
    private ProgressDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        UserPreferences.updateConfigFile(this);
        checkRuntimePermissions();
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

    private boolean addPermission(java.util.List<String> permissionsList, String permission) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(permission);
                return !shouldShowRequestPermissionRationale(permission);
            }
        }
        return true;
    }

    public void checkRuntimePermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            java.util.List<String> permissionsNeeded = new java.util.ArrayList<>();
            final java.util.List<String> permissionsList = new java.util.ArrayList<>();

            if (!addPermission(permissionsList, Manifest.permission.READ_EXTERNAL_STORAGE))
                permissionsNeeded.add("Read External Storage");
            if (!addPermission(permissionsList, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                permissionsNeeded.add("Write External Storage");

            if (!permissionsList.isEmpty()) {
                checkPermissions = true;
                if (!permissionsNeeded.isEmpty()) {
                    String message = "Você precisa conceder acesso a " + permissionsNeeded.get(0);
                    for (int i = 1; i < permissionsNeeded.size(); i++)
                        message += ", " + permissionsNeeded.get(i);

                    showMessageOKCancel(message, (dialog, which) -> {
                        if (which == AlertDialog.BUTTON_POSITIVE)
                            requestPermissions(permissionsList.toArray(new String[0]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                    });
                } else {
                    requestPermissions(permissionsList.toArray(new String[0]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                }
            }
        }
        if (!checkPermissions) startExtractionIfNeeded();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean allGranted = true;
        if (requestCode == REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS) {
            for (int i = 0; i < permissions.length; i++)
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) allGranted = false;

            if (!allGranted) {
                new AlertDialog.Builder(this)
                    .setMessage("Permissões negadas. Habilite nas configurações ou reinstale o aplicativo.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (dialog, which) -> finish())
                    .create()
                    .show();
                return;
            }
        }
        startExtractionIfNeeded();
    }

    private void startExtractionIfNeeded() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.getBoolean(EXTRACT_FLAG, false)) {
            new ExtractAssetsTask().execute();
        } else {
            finalStartup();
        }
    }

    private class ExtractAssetsTask extends AsyncTask<Void, Integer, Void> {
        private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        private final AtomicInteger totalFiles = new AtomicInteger(0);
        private final AtomicInteger processedFiles = new AtomicInteger(0);

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(MainMenuActivity.this);
            progressDialog.setMessage("Configurando RetroArch DRG...\n\nO aplicativo encerrará após configuração inicial.");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
            progressDialog.show();
        }

        @Override
        protected Void doInBackground(Void... voids) {
            copyFolder("", new File(MEDIA_DIR));
            executor.shutdown();
            while (!executor.isTerminated()) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
            return null;
        }

        private void copyFolder(String assetPath, File outDir) {
            try {
                String[] list = getAssets().list(assetPath);
                if (list == null || list.length == 0) {
                    totalFiles.incrementAndGet();
                    executor.execute(() -> copyFile(assetPath, new File(MEDIA_DIR, assetPath)));
                    return;
                }
                for (String name : list) {
                    String newPath = assetPath.isEmpty() ? name : assetPath + "/" + name;
                    copyFolder(newPath, outDir);
                }
            } catch (IOException e) {
                Log.e("MainMenuActivity", "Erro ao listar/copyFolder: " + assetPath, e);
            }
        }

        private void copyFile(String assetPath, File outFile) {
            try {
                if (!outFile.getParentFile().exists()) outFile.getParentFile().mkdirs();
                InputStream in = getAssets().open(assetPath);
                OutputStream out = new FileOutputStream(outFile);
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                in.close();
                out.flush();
                out.close();
            } catch (IOException e) {
                Log.e("MainMenuActivity", "Erro ao copiar asset: " + assetPath, e);
            }
            int progress = (processedFiles.incrementAndGet() * 100) / totalFiles.get();
            publishProgress(progress);
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            progressDialog.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            progressDialog.dismiss();
            updateRetroarchCfg();
            PreferenceManager.getDefaultSharedPreferences(MainMenuActivity.this)
                .edit().putBoolean(EXTRACT_FLAG, true).apply();
            finalStartup();
        }
    }

    private void updateRetroarchCfg() {
        File cfgFile = new File(getFilesDir(), "retroarch.cfg");
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("system_directory = \"" + MEDIA_DIR + "/system\"\n");
            sb.append("core_assets_directory = \"" + MEDIA_DIR + "/assets\"\n");
            sb.append("autoconfig_directory = \"" + MEDIA_DIR + "/autoconfig\"\n");
            sb.append("cores_directory = \"" + MEDIA_DIR + "/cores\"\n");
            sb.append("database_directory = \"" + MEDIA_DIR + "/database\"\n");
            sb.append("filters_directory = \"" + MEDIA_DIR + "/filters\"\n");
            sb.append("info_directory = \"" + MEDIA_DIR + "/info\"\n");
            sb.append("overlays_directory = \"" + MEDIA_DIR + "/overlays\"\n");
            sb.append("shaders_directory = \"" + MEDIA_DIR + "/shaders\"\n");

            FileOutputStream fos = new FileOutputStream(cfgFile, false);
            fos.write(sb.toString().getBytes());
            fos.close();
        } catch (IOException e) {
            Log.e("MainMenuActivity", "Erro ao atualizar retroarch.cfg", e);
        }
    }

    private void finalStartup() {
        Intent retro = new Intent(this, RetroActivityFuture.class);
        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        retro.putExtra("LIBRETRO", MEDIA_DIR + "/cores");
        retro.putExtra("CONFIGFILE", new File(getFilesDir(), "retroarch.cfg").getAbsolutePath());
        retro.putExtra("IME", Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD));
        retro.putExtra("DATADIR", getApplicationInfo().dataDir);
        retro.putExtra("APK", getApplicationInfo().sourceDir);
        retro.putExtra("SDCARD", Environment.getExternalStorageDirectory().getAbsolutePath());
        retro.putExtra("EXTERNAL", MEDIA_DIR);
        startActivity(retro);
        finish();
    }
} 
