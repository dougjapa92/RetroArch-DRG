package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.content.pm.PackageManager;
import android.Manifest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainMenuActivity extends PreferenceActivity {

    private static final String TAG = "MainMenuActivity";
    private SharedPreferences prefs;
    private boolean firstRun;
    private ProgressDialog progressDialog;

    private final String[] ASSET_FOLDERS = {
            "assets", "autoconfig", "cores", "database",
            "filters", "info", "overlays", "shaders", "system"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        firstRun = prefs.getBoolean("firstRun", true);

        if (firstRun) {
            new UnifiedExtractionTask().execute();
        } else {
            // Somente inicia o RetroArch se não for primeira execução
            startRetroArch();
        }
    }

    private class UnifiedExtractionTask extends AsyncTask<Void, String, Void> {
        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(MainMenuActivity.this);
            progressDialog.setMessage("Extraindo arquivos...");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setIndeterminate(false);
            progressDialog.setCancelable(false);
            progressDialog.show();
        }

        @Override
        protected Void doInBackground(Void... params) {
            try {
                // 1. Extrair para /data/data/com.retroarch
                File internalDir = getFilesDir().getParentFile();
                File sourceDir = new File(internalDir, "com.retroarch");
                if (!sourceDir.exists()) sourceDir.mkdirs();

                for (String folder : ASSET_FOLDERS) {
                    copyAssetFolder(folder, new File(sourceDir, folder));
                }

                // 2. Mover para /Android/media/com.retroarch
                File mediaDir = new File(Environment.getExternalStorageDirectory(),
                        "Android/media/com.retroarch");
                if (!mediaDir.exists()) mediaDir.mkdirs();

                moveDirectory(sourceDir, mediaDir);

                // 3. Criar retroarch.cfg em /Android/data/com.retroarch/files
                File dataDir = new File(Environment.getExternalStorageDirectory(),
                        "Android/data/com.retroarch/files");
                if (!dataDir.exists()) dataDir.mkdirs();

                File cfg = new File(dataDir, "retroarch.cfg");
                if (!cfg.exists()) {
                    FileOutputStream fos = new FileOutputStream(cfg);
                    fos.write(("system_directory = \"" + 
                               mediaDir.getAbsolutePath() + "/system\"\n").getBytes());
                    fos.close();
                }

            } catch (Exception e) {
                Log.e(TAG, "Erro na extração", e);
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }

            prefs.edit().putBoolean("firstRun", false).apply();

            // Inicia RetroArch uma única vez
            startRetroArch();

            // Mostra diálogo de encerramento de 5s e fecha app
            final ProgressDialog closingDialog = new ProgressDialog(MainMenuActivity.this);
            closingDialog.setMessage("Encerrando...");
            closingDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            closingDialog.setIndeterminate(true);
            closingDialog.setCancelable(false);
            closingDialog.show();

            new Handler().postDelayed(() -> {
                if (closingDialog.isShowing()) closingDialog.dismiss();
                finishAffinity(); // encerra app completamente
                System.exit(0);
            }, 5000); // 5 segundos
        }
    }

    private void copyAssetFolder(String assetFolder, File outDir) throws IOException {
        if (!outDir.exists()) outDir.mkdirs();
        String[] assets = getAssets().list(assetFolder);
        if (assets == null || assets.length == 0) return;

        for (String file : assets) {
            InputStream in = getAssets().open(assetFolder + "/" + file);
            File outFile = new File(outDir, file);
            FileOutputStream out = new FileOutputStream(outFile);

            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            out.flush();
            out.close();
        }
    }

    private void moveDirectory(File source, File target) {
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        if (!target.exists()) target.mkdirs();

        File[] files = source.listFiles();
        if (files == null) return;

        for (File file : files) {
            executor.submit(() -> {
                try {
                    File newFile = new File(target, file.getName());
                    if (file.isDirectory()) {
                        moveDirectory(file, newFile);
                        file.delete();
                    } else {
                        if (!newFile.exists()) {
                            file.renameTo(newFile);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao mover: " + file.getName(), e);
                }
            });
        }
        executor.shutdown();
        while (!executor.isTerminated()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
        }
    }

    private void startRetroArch() {
        Intent intent = new Intent(this, RetroActivityFuture.class);
        startActivity(intent);
    }
}
