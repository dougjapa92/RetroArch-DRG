package com.retroarch.browser.debug;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
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
                this,
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

        private final Activity ctx;
        private final String corePath;
        private final String contentPath;
        private File destination;
        private AtomicInteger processedFiles = new AtomicInteger(0);
        private int totalFiles = 0;

        private final String[] ASSET_FOLDERS = { "assets","autoconfig","cores","database","filters","info","overlays","shaders","system" };
        private final File BASE_DIR = new File(android.os.Environment.getExternalStorageDirectory(), "Android/media/com.retroarch");

        CoreSideloadWorkerTask(Activity ctx, String corePath, String contentPath) {
            this.ctx = ctx;
            this.corePath = corePath;
            this.contentPath = contentPath;
        }

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(ctx);
            progressDialog.setMessage("Configurando RetroArch DRG...");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
            progressDialog.show();

            if (!BASE_DIR.exists()) BASE_DIR.mkdirs();
        }

        @Override
        protected String doInBackground(Void... voids) {
            // Copia apenas o core
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

        @Override
        protected void onPostExecute(String s) {
            progressDialog.dismiss();

            // Inicia RetroArch com o core sideloaded usando o cfg existente
            Intent retro = new Intent(ctx, RetroActivityFuture.class);
            retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

            MainMenuActivity.startRetroActivity(
                    retro,
                    contentPath,
                    destination.getAbsolutePath(),
                    new File(BASE_DIR, "retroarch.cfg").getAbsolutePath(),
                    Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
                    BASE_DIR.getAbsolutePath(),
                    ctx.getApplicationInfo().sourceDir
            );

            ctx.startActivity(retro);
            ctx.finish();
        }
    }
}
