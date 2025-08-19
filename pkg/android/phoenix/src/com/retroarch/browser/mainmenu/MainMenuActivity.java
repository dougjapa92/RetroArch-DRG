package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainMenuActivity extends PreferenceActivity {

    private static final int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    public static String PACKAGE_NAME;
    private SharedPreferences prefs;
    private boolean checkPermissions = false;
    private Handler mainHandler;

    private final String[] ASSET_FOLDERS = {
        "assets", "database", "filters", "info", "overlays", "shaders", "system"
    };

    private final File BASE_DIR = new File(Environment.getExternalStorageDirectory(), "Android/media/com.retroarch");
    private final File CONFIG_DIR = new File(Environment.getExternalStorageDirectory() + "/Android/data/com.retroarch/files");

    private String archCores;
    private String archAutoconfig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        mainHandler = new Handler(Looper.getMainLooper());

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        UserPreferences.updateConfigFile(this);

        String arch = System.getProperty("os.arch");
        if (arch.contains("64")) {
            archCores = "cores64";
            archAutoconfig = "autoconfig64";
        } else {
            archCores = "cores32";
            archAutoconfig = "autoconfig32";
        }

        checkRuntimePermissions();
    }

    private boolean addPermission(List<String> list, String permission) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                list.add(permission);
                return !shouldShowRequestPermissionRationale(permission);
            }
        }
        return true;
    }

    private void checkRuntimePermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            List<String> permissionsList = new ArrayList<>();
            List<String> permissionsNeeded = new ArrayList<>();

            if (!addPermission(permissionsList, Manifest.permission.READ_EXTERNAL_STORAGE))
                permissionsNeeded.add("Read External Storage");
            if (!addPermission(permissionsList, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                permissionsNeeded.add("Write External Storage");

            if (!permissionsList.isEmpty()) {
                checkPermissions = true;
                String message = "Você precisa conceder acesso a " + String.join(", ", permissionsNeeded);

                new AlertDialog.Builder(this)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("OK", (dialog, which) ->
                        requestPermissions(permissionsList.toArray(new String[0]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS))
                    .setNegativeButton("Sair", (dialog, which) -> finish())
                    .show();
            }
        }

        if (!checkPermissions) startExtractionOrRetro();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;

            if (allGranted) {
                prefs.edit().putInt("deniedCount", 0).apply();
                startExtractionOrRetro();
            } else {
                int deniedCount = prefs.getInt("deniedCount", 0) + 1;
                prefs.edit().putInt("deniedCount", deniedCount).apply();

                if (deniedCount >= 2) {
                    new AlertDialog.Builder(this)
                        .setTitle("Permissão Negada!")
                        .setMessage("Ative as permissões manualmente nas configurações ou reinstale o aplicativo.")
                        .setCancelable(false)
                        .setPositiveButton("Abrir Configurações", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            Uri uri = Uri.fromParts("package", getPackageName(), null);
                            intent.setData(uri);
                            startActivity(intent);
                        })
                        .setNegativeButton("Sair", (dialog, which) -> finish())
                        .show();
                } else {
                    new AlertDialog.Builder(this)
                        .setTitle("Permissões Necessárias!")
                        .setMessage("O aplicativo precisa das permissões de armazenamento para funcionar corretamente.")
                        .setCancelable(false)
                        .setPositiveButton("Conceder", (dialog, which) ->
                            requestPermissions(permissions, REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS))
                        .setNegativeButton("Sair", (dialog, which) -> finish())
                        .show();
                }
            }
        } else super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void startExtractionOrRetro() {
        if (prefs.getBoolean("firstRun", true)) startExtraction();
        else finalStartup();
    }

    private void startExtraction() {
        // Inflate dialog view
        View dialogView = LayoutInflater.from(this).inflate(android.R.layout.select_dialog_item, null);
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        TextView textView = new TextView(this);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Configurando RetroArch DRG...")
                .setView(progressBar)
                .setCancelable(false)
                .create();
        dialog.show();

        if (!BASE_DIR.exists()) BASE_DIR.mkdirs();
        removeUnusedArchFolders();

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(ASSET_FOLDERS.length + 2, 4));
        AtomicInteger processedFiles = new AtomicInteger(0);
        int totalFiles = countAllFiles(ASSET_FOLDERS) + countAllFiles(new String[]{archCores, archAutoconfig});

        for (String folder : ASSET_FOLDERS)
            executor.submit(() -> copyAssetFolder(folder, new File(BASE_DIR, folder), processedFiles, totalFiles, progressBar));

        executor.submit(() -> copyAssetFolder(archCores, new File(BASE_DIR, "cores"), processedFiles, totalFiles, progressBar));
        executor.submit(() -> copyAssetFolder(archAutoconfig, new File(BASE_DIR, "autoconfig"), processedFiles, totalFiles, progressBar));

        executor.shutdown();

        new Thread(() -> {
            while (!executor.isTerminated()) {
                mainHandler.post(() -> progressBar.setProgress((processedFiles.get() * 100) / totalFiles));
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
            mainHandler.post(() -> {
                try { updateRetroarchCfg(); } catch (IOException ignored) {}
                dialog.dismiss();
                prefs.edit().putBoolean("firstRun", false).apply();
                finalStartup();
            });
        }).start();
    }

    private void copyAssetFolder(String assetFolder, File targetFolder, AtomicInteger processedFiles, int totalFiles, ProgressBar progressBar) {
        try {
            String[] assets = getAssets().list(assetFolder);
            if (assets == null) return;
            if (!targetFolder.exists()) targetFolder.mkdirs();

            for (String asset : assets) {
                File outFile = new File(targetFolder, asset);
                String fullPath = assetFolder + "/" + asset;
                if (getAssets().list(fullPath).length > 0)
                    copyAssetFolder(fullPath, outFile, processedFiles, totalFiles, progressBar);
                else try (InputStream in = getAssets().open(fullPath);
                         FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[1024];
                    int read;
                    while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
                    processedFiles.incrementAndGet();
                }
            }
        } catch (IOException ignored) {}
    }

    private void removeUnusedArchFolders() {
        String[] coresFolders = {"cores32", "cores64"};
        String[] autoconfigFolders = {"autoconfig32", "autoconfig64"};
        for (String f : coresFolders) if (!f.equals(archCores)) deleteFolder(new File(BASE_DIR, f));
        for (String f : autoconfigFolders) if (!f.equals(archAutoconfig)) deleteFolder(new File(BASE_DIR, f));
    }

    private void deleteFolder(File folder) {
        if (!folder.exists()) return;
        File[] files = folder.listFiles();
        if (files != null) for (File f : files) if (f.isDirectory()) deleteFolder(f); else f.delete();
        folder.delete();
    }

    private int countAllFiles(String[] folders) {
        int c = 0;
        for (String f : folders) c += countFilesRecursive(f);
        return c;
    }

    private int countFilesRecursive(String folder) {
        try {
            String[] assets = getAssets().list(folder);
            if (assets == null || assets.length == 0) return 1;
            int total = 0;
            for (String a : assets) total += countFilesRecursive(folder + "/" + a);
            return total;
        } catch (IOException e) { return 0; }
    }

    private void updateRetroarchCfg() throws IOException {
        File cfg = new File(CONFIG_DIR, "retroarch.cfg");
        if (!cfg.exists()) cfg.getParentFile().mkdirs();
        if (!cfg.exists()) cfg.createNewFile();

        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(cfg))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }

        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            for (String folder : ASSET_FOLDERS) {
                if (line.startsWith(folder + "_directory")) {
                    line = folder + "_directory = \"" + new File(BASE_DIR, folder).getAbsolutePath() + "\"";
                    break;
                }
            }
            content.append(line).append("\n");
        }

        try (FileOutputStream out = new FileOutputStream(cfg, false)) {
            out.write(content.toString().getBytes());
        }
    }

    public void finalStartup() {
        Intent retro = new Intent(this, RetroActivityFuture.class);
        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        startRetroActivity(
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
        retro.putExtra("EXTERNAL", Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/" + PACKAGE_NAME + "/files");
    }
}
