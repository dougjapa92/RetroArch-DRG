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
import android.os.Handler;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainMenuActivity extends PreferenceActivity {

    private static final String TAG = "MainMenuActivity";
    private ProgressDialog progressDialog;
    private SharedPreferences prefs;

    private final String[] ASSET_FOLDERS = {
            "assets", "autoconfig", "cores", "database",
            "filters", "info", "overlays", "shaders", "system"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean firstRun = prefs.getBoolean("firstRun", true);

        if (firstRun) {
            new UnifiedExtractionTask().execute();
        } else {
            launchRetroArchWithClosing();
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
        protected Void doInBackground(Void... voids) {
            try {
                File mediaDir = new File(Environment.getExternalStorageDirectory(),
                        "Android/media/com.retroarch");
                if (!mediaDir.exists()) mediaDir.mkdirs();

                int total = ASSET_FOLDERS.length;
                int count = 0;

                ExecutorService executor = Executors.newFixedThreadPool(4);

                for (String folder : ASSET_FOLDERS) {
                    int finalCount = ++count;
                    executor.execute(() -> {
                        copyAssetFolder(folder, new File(mediaDir, folder));
                        publishProgress("Extraindo: " + folder,
                                String.valueOf((finalCount * 100) / total));
                    });
                }

                executor.shutdown();
                while (!executor.isTerminated()) {
                    Thread.sleep(200);
                }

                // Criar retroarch.cfg apontando para Android/data
                createRetroarchCfg();

            } catch (Exception e) {
                Log.e(TAG, "Erro na extração", e);
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            if (values.length == 2) {
                progressDialog.setMessage(values[0]);
                progressDialog.setProgress(Integer.parseInt(values[1]));
            }
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            progressDialog.dismiss();
            prefs.edit().putBoolean("firstRun", false).apply();

            // Após extração e retroarch.cfg, inicia RetroArch e o encerramento
            launchRetroArchWithClosing();
        }
    }

    private void copyAssetFolder(String assetFolder, File destDir) {
        try {
            String[] files = getAssets().list(assetFolder);
            if (!destDir.exists()) destDir.mkdirs();
            if (files == null || files.length == 0) return;

            for (String filename : files) {
                try (InputStream in = getAssets().open(assetFolder + "/" + filename);
                     FileOutputStream out = new FileOutputStream(new File(destDir, filename))) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            Log.e(TAG, "Erro copiando asset: " + assetFolder, e);
        }
    }

    private void createRetroarchCfg() {
        try {
            File cfgDir = new File(getExternalFilesDir(null), "");
            if (!cfgDir.exists()) cfgDir.mkdirs();

            File cfgFile = new File(cfgDir, "retroarch.cfg");
            if (!cfgFile.exists()) {
                try (FileOutputStream out = new FileOutputStream(cfgFile)) {
                    String content =
                            "system_directory = \"/storage/emulated/0/Android/media/com.retroarch/system\"\n" +
                            "savestate_directory = \"/storage/emulated/0/Android/data/com.retroarch/files/states\"\n" +
                            "savefile_directory = \"/storage/emulated/0/Android/data/com.retroarch/files/savefiles\"\n";
                    out.write(content.getBytes());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro criando retroarch.cfg", e);
        }
    }

    private void launchRetroArchWithClosing() {
        // Inicia RetroArch normalmente
        Intent intent = new Intent(this, RetroActivityFuture.class);
        startActivity(intent);

        // ProgressDialog "Encerrando..." após 5s
        new Handler().postDelayed(() -> {
            ProgressDialog closingDialog = new ProgressDialog(MainMenuActivity.this);
            closingDialog.setMessage("Encerrando...");
            closingDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            closingDialog.setIndeterminate(true);
            closingDialog.setCancelable(false);
            closingDialog.show();

            new Handler().postDelayed(() -> {
                closingDialog.dismiss();
                finishAffinity(); // Fecha totalmente o app
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }, 5000); // 5 segundos
        }, 5000); // 5 segundos de delay para garantir que RetroArch iniciou
    }
} 
