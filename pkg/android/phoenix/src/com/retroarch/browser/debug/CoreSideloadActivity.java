package com.retroarch.browser.debug;

import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class CoreSideloadActivity extends Activity
{
    private static final String EXTRA_CORE = "LIBRETRO";
    private static final String EXTRA_CONTENT = "ROM";

    private TextView textView;
    private CoreSideloadWorkerTask workerThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        textView = new TextView(this);
        setContentView(textView);

        if (!getIntent().hasExtra(EXTRA_CORE)) {
            textView.setText("Missing extra \"LIBRETRO\"");
            return;
        }

        workerThread = new CoreSideloadWorkerTask(
                this,
                textView,
                getIntent().getStringExtra(EXTRA_CORE),
                getIntent().getStringExtra(EXTRA_CONTENT));
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

    private static class CoreSideloadWorkerTask extends AsyncTask<Void, Integer, String>
    {
        private TextView progressTextView;
        private String core;
        private String content;
        private Activity ctx;
        private File destination;

        public CoreSideloadWorkerTask(Activity ctx, TextView progressTextView, String corePath, String contentPath)
        {
            this.progressTextView = progressTextView;
            this.core = corePath;
            this.ctx = ctx;
            this.content = contentPath;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            progressTextView.setText("Sideloading (multithread)...");
        }

        @Override
        protected String doInBackground(Void... voids) {
            try {
                File coreFile = new File(core);
                File corePath = new File(UserPreferences.getPreferences(ctx)
                        .getString("libretro_path", ctx.getApplicationInfo().dataDir + "/cores/"));

                if (!coreFile.exists())
                    return "Input file doesn't exist (" + core + ")";
                if (!corePath.exists())
                    return "Destination directory doesn't exist (" + corePath.getAbsolutePath() + ")";

                destination = new File(corePath, coreFile.getName());

                long fileSize = coreFile.length();
                int threads = Runtime.getRuntime().availableProcessors();
                long chunkSize = fileSize / threads;

                ExecutorService executor = Executors.newFixedThreadPool(threads);
                List<Future<Boolean>> futures = new ArrayList<>();

                for (int i = 0; i < threads; i++) {
                    final int index = i;
                    futures.add(executor.submit(new Callable<Boolean>() {
                        @Override
                        public Boolean call() {
                            try (RandomAccessFile in = new RandomAccessFile(coreFile, "r");
                                 RandomAccessFile out = new RandomAccessFile(destination, "rw")) {

                                long start = index * chunkSize;
                                long end = (index == threads - 1) ? fileSize : (start + chunkSize);

                                byte[] buffer = new byte[8192];
                                in.seek(start);
                                out.seek(start);

                                long pos = start;
                                while (pos < end) {
                                    int toRead = (int) Math.min(buffer.length, end - pos);
                                    int read = in.read(buffer, 0, toRead);
                                    if (read == -1) break;
                                    out.write(buffer, 0, read);
                                    pos += read;
                                }
                                return true;
                            } catch (Exception e) {
                                e.printStackTrace();
                                return false;
                            }
                        }
                    }));
                }

                // Aguarda todas as threads
                for (Future<Boolean> f : futures) {
                    if (!f.get()) {
                        executor.shutdown();
                        return "Erro em thread de cópia";
                    }
                }

                executor.shutdown();

                return null; // sucesso
            } catch (Exception e) {
                e.printStackTrace();
                return e.getMessage();
            }
        }

        @Override
        protected void onPostExecute(String s) {
            super.onPostExecute(s);

            if (s == null) {
                progressTextView.setText("Done!");

                Intent retro = new Intent(ctx, RetroActivityFuture.class);
                retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                retro.putExtra("ROM", content);
                retro.putExtra("LIBRETRO", destination.getAbsolutePath());
                retro.putExtra("CONFIGFILE", UserPreferences.getDefaultConfigPath(ctx));
                retro.putExtra("IME", Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD));
                retro.putExtra("DATADIR", ctx.getApplicationInfo().dataDir);
                retro.putExtra("APKPATH", ctx.getApplicationInfo().sourceDir);

                Log.d("sideload", "Running RetroArch with core " + destination.getAbsolutePath());

                ctx.startActivity(retro);
                ctx.finish();
            } else {
                progressTextView.setText("Error: " + s);
            }
        }
    }
} 
