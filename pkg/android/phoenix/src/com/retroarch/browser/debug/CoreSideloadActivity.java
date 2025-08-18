package com.retroarch.browser.debug;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;

import com.retroarch.browser.mainmenu.MainMenuActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CoreSideloadActivity extends Activity {

    private static final String TAG = "CoreSideloadActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new ExtractAndLaunchTask(this).execute();
    }

    private static class ExtractAndLaunchTask extends AsyncTask<Void, Void, Boolean> {
        private final Context context;
        private ProgressDialog dialog;

        ExtractAndLaunchTask(Context ctx) {
            this.context = ctx;
        }

        @Override
        protected void onPreExecute() {
            dialog = new ProgressDialog(context);
            dialog.setMessage("Preparando RetroArch...");
            dialog.setCancelable(false);
            dialog.show();
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                File baseDir = new File(MainMenuActivity.RETROARCH_DIR, "assets");
                if (!baseDir.exists() && !baseDir.mkdirs()) return false;

                String[] assetFiles = context.getAssets().list("android");
                if (assetFiles == null) return false;

                ExecutorService executor = Executors.newFixedThreadPool(
                        Math.max(2, Runtime.getRuntime().availableProcessors())
                );

                for (String name : assetFiles) {
                    executor.submit(() -> copyAsset(context, "android/" + name,
                            new File(baseDir, name)));
                }

                executor.shutdown();
                while (!executor.isTerminated()) {
                    Thread.sleep(100);
                }

                // atualizar retroarch.cfg
                File cfgFile = new File(MainMenuActivity.RETROARCH_DIR, "retroarch.cfg");
                MainMenuActivity.ExtractAssetsTask.updateRetroarchCfg(cfgFile);

                return true;
            } catch (Exception e) {
                Log.e(TAG, "Erro preparando RetroArch", e);
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean ok) {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();

            if (ok) {
                // Aqui chamamos a RetroActivity diretamente
                try {
                    Intent launchIntent = new Intent();
                    launchIntent.setClassName(context, "com.retroarch.browser.retroactivity.RetroActivityFuture");
                    context.startActivity(launchIntent);
                } catch (Exception e) {
                    Log.e(TAG, "Falha ao iniciar RetroArch", e);
                }
            }

            if (context instanceof Activity) ((Activity) context).finish();
        }

        private static void copyAsset(Context ctx, String assetName, File outFile) {
            try (InputStream is = ctx.getAssets().open(assetName);
                 OutputStream os = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) > 0) {
                    os.write(buffer, 0, read);
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro copiando " + assetName, e);
            }
        }
    }
} 
