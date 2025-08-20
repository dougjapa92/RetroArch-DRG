package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.media.AudioManager;
import android.os.Build;
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

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainMenuActivity extends PreferenceActivity {

    private final int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    public static String PACKAGE_NAME;
    private SharedPreferences prefs;

    private final String[] ASSET_FOLDERS = {
            "assets", "database", "filters", "info", "overlays", "shaders", "system", "config", "remaps", "cheats"
    };

    private final Map<String, String> ASSET_FLAGS = new HashMap<String, String>() {{
        put("assets", "assets_directory");
        put("database", "database_directory");
        put("filters", "filters_directory");
        put("info", "info_directory");
        put("overlays", "overlays_directory");
        put("shaders", "shaders_directory");
        put("system", "system_directory");
        put("config", "rgui_config_directory");
        put("remaps", "input_remapping_directory");
        put("cheats", "cheat_database_path");
    }};

    private final File BASE_DIR = new File(Environment.getExternalStorageDirectory(), "Android/media/com.retroarch");
    private final File CONFIG_DIR = new File(Environment.getExternalStorageDirectory() + "/Android/data/com.retroarch/files");

    private String archCores;
    private String archAutoconfig;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger processedFiles = new AtomicInteger(0);
    private volatile int totalFiles = 0;
    private final Map<String, String[]> assetCache = new ConcurrentHashMap<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        UserPreferences.updateConfigFile(this);

        String arch = System.getProperty("os.arch");
        archCores = arch != null && arch.contains("64") ? "cores64" : "cores32";

        checkRuntimePermissions();
    }

    private void checkRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissionsList = new ArrayList<>();

            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }

            if (!permissionsList.isEmpty()) {
                requestPermissions(permissionsList.toArray(new String[0]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                return;
            }
        }

        startExtractionOrRetro();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) if (result != PackageManager.PERMISSION_GRANTED) allGranted = false;

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
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void startExtractionOrRetro() {
        boolean firstRun = prefs.getBoolean("firstRun", true);
        if (firstRun) startExtractionTask();
        else finalStartup();
    }

    private ExecutorService buildSafeExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Math.min(Math.max(2, cores - 1), 6);
        int queueSize = threads * 4;
        return new ThreadPoolExecutor(
                threads,
                threads,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueSize),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private void startExtractionTask() {
        ProgressDialog progressDialog = new ProgressDialog(MainMenuActivity.this);
        progressDialog.setTitle("Configurando RetroArch DRG...");
        String archMessage = archCores.equals("cores64") ? "\nArquitetura dos Cores:\n   - arm64-v8a (64-bit)" : "\nArquitetura dos Cores:\n   - armeabi-v7a (32-bit)";
        progressDialog.setMessage(archMessage + "\n\nClique em \"Sair do RetroArch\" após a configuração ou force o encerramento do aplicativo.");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setCancelable(false);
        progressDialog.show();

        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                if (!BASE_DIR.exists()) BASE_DIR.mkdirs();
                removeUnusedArchFolders();

                archAutoconfig = (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) ? "autoconfig-legacy" : "autoconfig";

                AssetManager am = getAssets();
                List<String> roots = new ArrayList<>();
                for (String f : ASSET_FOLDERS) roots.add(f);
                roots.add(archCores);
                roots.add(archAutoconfig);
                for (String root : roots) preloadAssets(am, root);

                totalFiles = 0;
                for (String root : roots) totalFiles += countFilesRecursive(root);

                ExecutorService executor = buildSafeExecutor();

                for (String folder : ASSET_FOLDERS) {
                    File target = new File(BASE_DIR, folder);
                    executor.submit(() -> copyAssetFolder(am, folder, target));
                }
                executor.submit(() -> copyAssetFolder(am, archCores, new File(BASE_DIR, "cores")));
                executor.submit(() -> copyAssetFolder(am, archAutoconfig, new File(BASE_DIR, "autoconfig")));

                while (!executor.awaitTermination(120, TimeUnit.MILLISECONDS)) {
                    int progress = (totalFiles == 0) ? 0 : (processedFiles.get() * 100) / totalFiles;
                    uiHandler.post(() -> progressDialog.setProgress(progress));
                }

                updateRetroarchCfg();
                prefs.edit().putBoolean("firstRun", false).apply();
                uiHandler.post(() -> {
                    progressDialog.dismiss();
                    finalStartup();
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                uiHandler.post(progressDialog::dismiss);
                finish();
            } catch (Exception e) {
                e.printStackTrace();
                uiHandler.post(progressDialog::dismiss);
                finish();
            }
        });
    }

    private void preloadAssets(AssetManager am, String path) throws IOException {
        String[] list = am.list(path);
        if (list == null) return;
        if (list.length > 0) {
            assetCache.put(path, list);
            for (String name : list) {
                String full = path + "/" + name;
                String[] sub = am.list(full);
                if (sub != null && sub.length > 0) preloadAssets(am, full);
            }
        }
    }

    private int countFilesRecursive(String path) throws IOException {
        String[] list = assetCache.get(path);
        if (list == null) return 0;
        int count = 0;
        for (String name : list) {
            String full = path + "/" + name;
            if (assetCache.containsKey(full)) {
                if (path.startsWith("assets") || path.startsWith("overlays")) {
                    count += countFilesRecursive(full);
                } else {
                    count += list.length;
                    break;
                }
            } else {
                count += 1;
            }
        }
        return count;
    }

    private void copyAssetFolder(AssetManager am, String assetDir, File outDir) {
        try {
            String[] assets = assetCache.get(assetDir);
            if (assets == null) return;
            if (!outDir.exists()) outDir.mkdirs();

            boolean nomediaCreated = false;
            boolean checkMedia = assetDir.startsWith("assets") || assetDir.startsWith("overlays");

            for (String entry : assets) {
                String fullPath = assetDir + "/" + entry;
                File outFile = new File(outDir, entry);

                if (assetCache.containsKey(fullPath)) {
                    buildSafeExecutor().submit(() -> copyAssetFolder(am, fullPath, outFile));
                } else {
                    // Ignora global.glslp se for armeabi-v7a
                    if ("config".equals(assetDir) && "global.glslp".equals(entry) && "cores32".equals(archCores)) {
                        processedFiles.incrementAndGet();
                        continue;
                    }

                    try (InputStream in = am.open(fullPath);
                         FileOutputStream out = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    }

                    if (checkMedia && !nomediaCreated && isMedia(entry)) {
                        File nomedia = new File(outDir, ".nomedia");
                        if (!nomedia.exists()) {
                            try { nomedia.createNewFile(); } catch (IOException ignored) {}
                        }
                        nomediaCreated = true;
                    }

                    processedFiles.incrementAndGet();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean isMedia(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".gif") || lower.endsWith(".webp") ||
               lower.endsWith(".mp4") || lower.endsWith(".mkv") ||
               lower.endsWith(".avi") || lower.endsWith(".mov");
    }

    private void removeUnusedArchFolders() {
        String[] coresFolders = {"cores32", "cores64"};
        String[] autoconfigFolders = {"autoconfig-legacy", "autoconfig"};

        for (String folder : coresFolders) if (!folder.equals(archCores)) deleteFolder(new File(BASE_DIR, folder));
        for (String folder : autoconfigFolders) if (!folder.equals(archAutoconfig)) deleteFolder(new File(BASE_DIR, folder));
    }

    private void deleteFolder(File folder) {
        if (folder.exists()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) deleteFolder(file);
                    else file.delete();
                }
            }
            folder.delete();
        }
    }

    private void updateRetroarchCfg() throws IOException {
        File originalCfg = new File(CONFIG_DIR, "retroarch.cfg");
        if (!originalCfg.exists()) originalCfg.getParentFile().mkdirs();

        Map<String, String> cfgFlags = new HashMap<>();
        cfgFlags.put("menu_scale_factor", "0.600000");
        cfgFlags.put("ozone_menu_color_theme", "10");
        cfgFlags.put("input_overlay_opacity", "0.700000");
        cfgFlags.put("input_overlay_hide_when_gamepad_connected", "true");
        cfgFlags.put("video_smooth", "false");
        cfgFlags.put("aspect_ratio_index", "1");
        cfgFlags.put("netplay_nickname", "RetroGameBox");
        cfgFlags.put("menu_enable_widgets", "true");
        cfgFlags.put("pause_nonactive", "false");
        cfgFlags.put("menu_mouse_enable", "false");
        cfgFlags.put("input_player1_analog_dpad_mode", "1");
        cfgFlags.put("input_player2_analog_dpad_mode", "1");
        cfgFlags.put("input_player3_analog_dpad_mode", "1");
        cfgFlags.put("input_player4_analog_dpad_mode", "1");
        cfgFlags.put("input_player5_analog_dpad_mode", "1");
        cfgFlags.put("input_menu_toggle_gamepad_combo", "9");
        cfgFlags.put("input_quit_gamepad_combo", "4");
        cfgFlags.put("input_bind_timeout", "4");
        cfgFlags.put("input_bind_hold", "1");
        cfgFlags.put("all_users_control_menu", "true");
        cfgFlags.put("input_poll_type_behavior", "1");
        cfgFlags.put("android_input_disconnect_workaround", "true");

        if ("cores32".equals(archCores)) cfgFlags.put("video_threaded", "true");
        else cfgFlags.put("video_threaded", "false");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cfgFlags.put("video_driver", "vulkan");
        else cfgFlags.put("video_driver", "gl");

        boolean hasTouchscreen = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        boolean isLeanback = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);

        if (hasTouchscreen && !isLeanback) {
            cfgFlags.put("input_overlay_enable", "true");
            cfgFlags.put("input_enable_hotkey_btn", "109");
            cfgFlags.put("input_menu_toggle_btn", "100");
            cfgFlags.put("input_save_state_btn", "103");
            cfgFlags.put("input_load_state_btn", "102");
            cfgFlags.put("input_state_slot_decrease_btn", "104");
            cfgFlags.put("input_state_slot_increase_btn", "105");
        } else {
            cfgFlags.put("input_overlay_enable", "false");
            cfgFlags.put("input_enable_hotkey_btn", "196");
            cfgFlags.put("input_menu_toggle_btn", "188");
            cfgFlags.put("input_save_state_btn", "193");
            cfgFlags.put("input_load_state_btn", "192");
            cfgFlags.put("input_state_slot_decrease_btn", "194");
            cfgFlags.put("input_state_slot_increase_btn", "195");
        }

        Map<String, File> assetFiles = new HashMap<>();
        for (String folder : ASSET_FOLDERS) assetFiles.put(folder, new File(BASE_DIR, folder));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(originalCfg, false))) {
            for (Map.Entry<String, String> entry : ASSET_FLAGS.entrySet()) {
                String folder = entry.getKey();
                String flag = entry.getValue();
                File path = assetFiles.get(folder);
                if (path != null) writer.write(flag + " = \"" + path.getAbsolutePath() + "\"\n");
            }
            for (Map.Entry<String, String> entry : cfgFlags.entrySet()) {
                writer.write(entry.getKey() + " = \"" + entry.getValue() + "\"\n");
            }
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
        String external = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/" + PACKAGE_NAME + "/files";
        retro.putExtra("EXTERNAL", external);
    }
}
