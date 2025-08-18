package com.retroarch.browser.debug;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;

import com.retroarch.browser.mainmenu.MainMenuActivity;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class CoreSideloadActivity extends Activity {

    private static final String EXTRA_CORE = "LIBRETRO";
    private static final String EXTRA_CONTENT = "ROM";

    private ProgressDialog progressDialog;
    private CoreSideloadWorkerTask workerThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!getIntent().hasExtra(EXTRA_CORE)) {
            finish();
            return;
        }

        workerThread = new CoreSideloadWorkerTask(
                getIntent().getStringExtra(EXTRA_CORE),
                getIntent().getStringExtra(EXTRA_CONTENT)
        );
        workerThread.execute();
    }

    @Override
    protected void onDestroy() {
        if (workerThread != null) {
            workerThread.cancel(true);
            workerThread = null;
        }
        super.onDestroy();
    }

    private class CoreSideloadWorkerTask extends AsyncTask<Void, Integer, String> {

        private final String corePath;
        private final String contentPath;
        private File destination;
        private AtomicInteger processedFiles = new AtomicInteger(0);
        private int totalFiles = 0;

        private final String[] ASSET_FOLDERS = {
                "assets", "autoconfig", "cores", "database",
                "filters", "info", "overlays", "shaders", "system"
        };

        private final File BASE_DIR = new File(android.os.Environment.getExternalStorageDirectory(), "Android/media/com.retroarch");
        private final File CONFIG_DIR = new File(android.os.Environment.getExternalStorageDirectory() + "/Android/data/com.retroarch/files");

        CoreSideloadWorkerTask(String corePath, String contentPath) {
            this.corePath = corePath;
            this.contentPath = contentPath;
        }

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(CoreSideloadActivity.this);
            progressDialog.setMessage("Configurando RetroArch DRG...\n\nO aplicativo encerrará após configuração inicial.");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
            progressDialog.show();

            if (!BASE_DIR.exists()) BASE_DIR.mkdirs();
            totalFiles = ASSET_FOLDERS.length; // apenas referência para progress
        }

        @Override
        protected String doInBackground(Void... voids) {
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(ASSET_FOLDERS.length, 4));

            for (String folder : ASSET_FOLDERS) {
                executor.submit(() -> {
                    try {
                        copyAssetFolder(folder, new File(BASE_DIR, folder));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    processedFiles.incrementAndGet();
                });
            }

            executor.shutdown();
            while (!executor.isTerminated()) {
                publishProgress((processedFiles.get() * 100) / totalFiles);
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }

            // Copia o core para cores/
            File coreFile = new File(corePath);
            File coresDir = new File(BASE_DIR, "cores");
            if (!coresDir.exists()) coresDir.mkdirs();
            destination = new File(coresDir, coreFile.getName());

            try (InputStream in = new FileInputStream(coreFile);
                 OutputStream out = new FileOutputStream(destination)) {

                byte[] buf = new byte[1024];
                int length;
                while ((length = in.read(buf)) != -1) out.write(buf, 0, length);

            } catch (IOException ex) { return ex.getMessage(); }

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
                    }
                }
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (values.length > 0) progressDialog.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(String s) {
            progressDialog.dismiss();

            if (s != null) progressDialog.setMessage("Erro: " + s);

            // Inicia RetroArch com o core sideloaded usando o mesmo cfg do MainMenuActivity
            File cfgFile = new File(CONFIG_DIR, "retroarch.cfg");
            if (!cfgFile.exists()) {
                // Caso o arquivo não exista, sinaliza erro
                return;
            }

            Intent retro = new Intent(CoreSideloadActivity.this, RetroActivityFuture.class);
            retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

            MainMenuActivity.startRetroActivity(
                    retro,
                    contentPath,
                    destination.getAbsolutePath(),
                    cfgFile.getAbsolutePath(),
                    Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
                    BASE_DIR.getAbsolutePath(),
                    getApplicationInfo().sourceDir
            );

            startActivity(retro);
            finish();
        }
    }
}
