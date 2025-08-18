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
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.content.pm.PackageManager;
import android.Manifest;

import java.io.*;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainMenuActivity extends PreferenceActivity {

    private final int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    public static String PACKAGE_NAME;
    boolean checkPermissions = false;
    private SharedPreferences prefs;

    private final String[] ASSET_FOLDERS = {
            "assets", "autoconfig", "cores", "database",
            "filters", "info", "overlays", "shaders", "system"
    };

    private final File TMP_DIR = new File("/data/data/com.retroarch");
    private final File BASE_DIR = new File(Environment.getExternalStorageDirectory(), "Android/media/com.retroarch");
    private final File CONFIG_DIR = new File(Environment.getExternalStorageDirectory() + "/Android/data/com.retroarch/files");

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        UserPreferences.updateConfigFile(this);

        checkRuntimePermissions();
    }

    private boolean addPermission(List<String> permissionsList, String permission) {
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
            List<String> permissionsNeeded = new ArrayList<>();
            final List<String> permissionsList = new ArrayList<>();

            if (!addPermission(permissionsList, Manifest.permission.READ_EXTERNAL_STORAGE))
                permissionsNeeded.add("Read External Storage");
            if (!addPermission(permissionsList, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                permissionsNeeded.add("Write External Storage");

            if (permissionsList.size() > 0) {
                checkPermissions = true;
                if (!permissionsNeeded.isEmpty()) {
                    String message = "Você precisa conceder acesso a " + permissionsNeeded.get(0);
                    for (int i = 1; i < permissionsNeeded.size(); i++)
                        message += ", " + permissionsNeeded.get(i);

                    new AlertDialog.Builder(this)
                            .setMessage(message)
                            .setCancelable(false)
                            .setPositiveButton("OK", (dialog, which) ->
                                    requestPermissions(permissionsList.toArray(new String[0]),
                                            REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS))
                            .setNegativeButton("Sair", (dialog, which) -> finish())
                            .show();
                } else {
                    requestPermissions(permissionsList.toArray(new String[0]),
                            REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                }
            }
        }

        if (!checkPermissions) startExtractionOrRetro();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) startExtractionOrRetro();
            else {
                new AlertDialog.Builder(this)
                        .setTitle("Permissões necessárias")
                        .setMessage("O aplicativo precisa das permissões de armazenamento para funcionar corretamente.")
                        .setCancelable(false)
                        .setPositiveButton("Conceder", (dialog, which) -> requestPermissions(permissions, REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS))
                        .setNegativeButton("Sair", (dialog, which) -> finish())
                        .show();
            }
        } else super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void startExtractionOrRetro() {
        boolean firstRun = prefs.getBoolean("firstRun", true);
        if (firstRun) new UnifiedExtractionTask().execute();
        else finalStartup();
    }

    private class UnifiedExtractionTask extends AsyncTask<Void, Integer, Boolean> {

        ProgressDialog progressDialog;
        AtomicInteger processedFiles = new AtomicInteger(0);
        int totalFiles = 0;

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(MainMenuActivity.this);
            progressDialog.setMessage("Configurando RetroArch DRG...");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
            progressDialog.show();

            if (!TMP_DIR.exists()) TMP_DIR.mkdirs();
            totalFiles = estimateTotalFiles(ASSET_FOLDERS);
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                // 1. Extrai assets para TMP_DIR
                ExecutorService extractExecutor = Executors.newFixedThreadPool(4);
                for (String folder : ASSET_FOLDERS) {
                    extractExecutor.submit(() -> {
                        try { copyAssetFolder(folder, new File(TMP_DIR, folder)); }
                        catch (IOException e) { e.printStackTrace(); }
                    });
                }
                extractExecutor.shutdown();
                while (!extractExecutor.isTerminated()) {
                    publishProgress(Math.min((processedFiles.get() * 100 / totalFiles), 100));
                    Thread.sleep(50);
                }

                // 2. Move TMP_DIR para BASE_DIR
                if (!BASE_DIR.exists()) BASE_DIR.mkdirs();
                moveDirectoryMultithread(TMP_DIR, BASE_DIR);

                // 3. Cria retroarch.cfg
                updateRetroarchCfg();

            } catch (Exception e) {
                Log.e("UnifiedExtractionTask", "Erro geral", e);
                return false;
            }
            return true;
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (values.length > 0) progressDialog.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            progressDialog.dismiss();
            prefs.edit().putBoolean("firstRun", false).apply();
            finalStartup();
        }

        private int estimateTotalFiles(String[] folders) {
            int count = 0;
            for (String folder : folders) {
                try {
                    String[] assets = getAssets().list(folder);
                    if (assets != null) count += assets.length;
                    else count++;
                } catch (IOException e) { count++; }
            }
            return Math.max(count, 1);
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
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                        }
                        processedFiles.incrementAndGet();
                    }
                }
            }
        }

        private void moveDirectoryMultithread(File srcDir, File dstDir) throws InterruptedException {
            File[] children = srcDir.listFiles();
            if (children == null) return;

            ExecutorService moveExecutor = Executors.newFixedThreadPool(4);
            for (File child : children) {
                moveExecutor.submit(() -> {
                    try { moveFile(child, new File(dstDir, child.getName())); }
                    catch (IOException e) { Log.e("UnifiedExtractionTask", "Erro movendo " + child.getName(), e); }
                });
            }
            moveExecutor.shutdown();
            while (!moveExecutor.isTerminated()) Thread.sleep(50);

            srcDir.delete();
        }

        private void moveFile(File src, File dst) throws IOException {
            if (src.isDirectory()) {
                if (!dst.exists()) dst.mkdirs();
                File[] files = src.listFiles();
                if (files != null) {
                    for (File f : files) moveFile(f, new File(dst, f.getName()));
                }
                src.delete();
            } else {
                if (!src.renameTo(dst)) {
                    try (InputStream in = new FileInputStream(src);
                         OutputStream out = new FileOutputStream(dst)) {
                        byte[] buf = new byte[65536];
                        int len;
                        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    }
                    src.delete();
                }
            }
        }

        private void updateRetroarchCfg() throws IOException {
            if (!CONFIG_DIR.exists()) CONFIG_DIR.mkdirs();
            File cfgFile = new File(CONFIG_DIR, "retroarch.cfg");
            if (!cfgFile.exists()) cfgFile.createNewFile();

            StringBuilder content = new StringBuilder("# RetroArch DRG cfg\n");
            for (String folder : ASSET_FOLDERS) {
                File f = new File(BASE_DIR, folder);
                if (f.exists()) content.append(folder).append("_directory = \"").append(f.getAbsolutePath()).append("\"\n");
            }

            try (FileOutputStream out = new FileOutputStream(cfgFile, false)) {
                out.write(content.toString().getBytes());
            }
        }
    }

    public void finalStartup() {
        Intent retro = new Intent(this, RetroActivityFuture.class);
        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        MainMenuActivity.startRetroActivity(
                retro,
                null,
                new File(BASE_DIR, "cores").getAbsolutePath(),
                new File(CONFIG_DIR, "retroarch.cfg").getAbsolutePath(),
                Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
                BASE_DIR.getAbsolutePath(),
                getApplicationInfo().sourceDir
        );
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
