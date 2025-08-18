package com.retroarch.browser.debug;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.retroarch.browser.mainmenu.MainMenuActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class CoreSideloadActivity extends Activity {

    private ProgressDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new AssetExtractionTask().execute();
    }

    private class AssetExtractionTask extends AsyncTask<Void, Integer, String> {

        private final String[] ASSET_FOLDERS = {
                "assets", "autoconfig", "cores", "database",
                "filters", "info", "overlays", "shaders", "system"
        };
        private final File BASE_DIR = new File(android.os.Environment.getExternalStorageDirectory(), "Android/media/com.retroarch");
        private AtomicInteger processedFiles = new AtomicInteger(0);
        private int totalFiles = 0;

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(CoreSideloadActivity.this);
            progressDialog.setMessage("Configurando RetroArch DRG...\n\nO aplicativo encerrará após configuração inicial.");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
            progressDialog.show();

            if (!BASE_DIR.exists()) BASE_DIR.mkdirs();
            totalFiles = countAllFiles();
        }

        private int countAllFiles() {
            int count = 0;
            for (String folder : ASSET_FOLDERS) {
                count += countFilesRecursive(folder);
            }
            return Math.max(count, 1);
        }

        private int countFilesRecursive(String assetFolder) {
            try {
                String[] assets = getAssets().list(assetFolder);
                if (assets == null || assets.length == 0) return 1;
                int total = 0;
                for (String asset : assets) total += countFilesRecursive(assetFolder + "/" + asset);
                return total;
            } catch (IOException e) {
                return 0;
            }
        }

        @Override
        protected String doInBackground(Void... voids) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(CoreSideloadActivity.this);
            boolean alreadyExtracted = prefs.getBoolean("assets_extracted", false);
            if (alreadyExtracted) return null; // não extrai novamente

            ExecutorService executor = Executors.newFixedThreadPool(Math.min(ASSET_FOLDERS.length, 4));

            for (String folder : ASSET_FOLDERS) {
                executor.submit(() -> {
                    try {
                        copyAssetFolder(folder, new File(BASE_DIR, folder));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }

            executor.shutdown();
            while (!executor.isTerminated()) {
                publishProgress((processedFiles.get() * 100) / totalFiles);
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }

            try { generateRetroarchCfg(); } catch (IOException e) { return e.getMessage(); }

            prefs.edit().putBoolean("assets_extracted", true).apply(); // marca extração completa
            return null;
        }

        private void copyAssetFolder(String assetFolder, File targetFolder) throws IOException {
            String[] assets = getAssets().list(assetFolder);
            if (!targetFolder.exists()) targetFolder.mkdirs();

            if (assets != null && assets.length > 0) {
                for (String asset : assets) {
                    String fullPath = assetFolder + "/" + asset;
                    File outFile = new File(targetFolder, asset);

                    if (getAssets().list(fullPath).length > 0) {
                        copyAssetFolder(fullPath, outFile);
                    } else {
                        try (InputStream in = getAssets().open(fullPath);
                             FileOutputStream out = new FileOutputStream(outFile)) {
                            byte[] buffer = new byte[1024];
                            int read;
                            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                        }
                        processedFiles.incrementAndGet();
                    }
                }
            }
        }

        private void generateRetroarchCfg() throws IOException {
            File cfgFile = new File(BASE_DIR, "retroarch.cfg");
            if (!cfgFile.exists()) cfgFile.createNewFile();

            try (FileOutputStream out = new FileOutputStream(cfgFile, false)) {
                StringBuilder content = new StringBuilder("# RetroArch DRG cfg\n");
                for (String folder : ASSET_FOLDERS) {
                    content.append(folder).append("_directory = \"")
                            .append(new File(BASE_DIR, folder).getAbsolutePath())
                            .append("\"\n");
                }
                out.write(content.toString().getBytes());
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (values.length > 0) progressDialog.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(String s) {
            progressDialog.dismiss();
            if (s != null) Toast.makeText(CoreSideloadActivity.this, "Erro: " + s, Toast.LENGTH_LONG).show();

            // encerra app após extração
            finishAffinity();
            System.exit(0);
        }
    }
}
