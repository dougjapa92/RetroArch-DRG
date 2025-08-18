package com.retroarch.browser.mainmenu;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainMenuActivity extends Activity {

    private static final String TAG = "MainMenuActivity";
    public static final String RETROARCH_DIR =
            "/storage/emulated/0/Android/media/com.retroarch/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new ExtractAssetsTask(this).execute();
    }

    public static class ExtractAssetsTask extends AsyncTask<Void, Void, Boolean> {
        private final Context context;
        private ProgressDialog dialog;

        ExtractAssetsTask(Context ctx) {
            this.context = ctx;
        }

        @Override
        protected void onPreExecute() {
            dialog = new ProgressDialog(context);
            dialog.setMessage("Extraindo arquivos, aguarde...");
            dialog.setCancelable(false);
            dialog.show();
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                File baseDir = new File(RETROARCH_DIR, "assets");
                if (!baseDir.exists() && !baseDir.mkdirs()) {
                    Log.e(TAG, "Falha ao criar " + baseDir.getAbsolutePath());
                    return false;
                }

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

                // Atualizar retroarch.cfg
                File cfgFile = new File(RETROARCH_DIR, "retroarch.cfg");
                updateRetroarchCfg(cfgFile);

                return true;
            } catch (Exception e) {
                Log.e(TAG, "Erro na extração", e);
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean ok) {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
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

        private static void updateRetroarchCfg(File cfgFile) {
            try (FileOutputStream fos = new FileOutputStream(cfgFile, false)) {
                String cfg =
                        "audio_out_rate = \"48000\"\n" +
                        "audio_block_frames = \"192\"\n" +
                        "bundle_assets_dst_path = \"" + RETROARCH_DIR + "assets\"\n" +
                        "libretro_directory = \"" + RETROARCH_DIR + "cores\"\n" +
                        "system_directory = \"" + RETROARCH_DIR + "system\"\n" +
                        "screenshot_directory = \"" + RETROARCH_DIR + "screenshots\"\n" +
                        "savefile_directory = \"" + RETROARCH_DIR + "saves\"\n" +
                        "savestate_directory = \"" + RETROARCH_DIR + "states\"\n" +
                        "playlist_directory = \"" + RETROARCH_DIR + "playlists\"\n";
                fos.write(cfg.getBytes());
            } catch (Exception e) {
                Log.e(TAG, "Erro atualizando retroarch.cfg", e);
            }
        }
    }
} 
