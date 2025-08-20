package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        UserPreferences.updateConfigFile(this);

        String arch = System.getProperty("os.arch");
        archCores = arch.contains("64") ? "cores64" : "cores32";

        requestStoragePermissions();
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
        } else {
            startExtractionOrRetro();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) if (result != PackageManager.PERMISSION_GRANTED) allGranted = false;

            if (allGranted) startExtractionOrRetro();
            else finish(); // Encerrar se negado
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void startExtractionOrRetro() {
        boolean firstRun = prefs.getBoolean("firstRun", true);
        if (firstRun) new Thread(this::runExtraction).start();
        else finalStartup();
    }

    private void runExtraction() {
        if (!BASE_DIR.exists()) BASE_DIR.mkdirs();
        removeUnusedArchFolders();

        // Definir archAutoconfig de acordo com a versão do Android
        archAutoconfig = (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) ? "autoconfig-legacy" : "autoconfig";

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(ASSET_FOLDERS.length + 2, 4));
        AtomicInteger processedFiles = new AtomicInteger(0);

        try {
            for (String folder : ASSET_FOLDERS) {
                executor.submit(() -> {
                    try { copyAssetFolder(folder, new File(BASE_DIR, folder), true); }
                    catch (IOException e) { e.printStackTrace(); }
                });
            }
            executor.submit(() -> {
                try { copyAssetFolder(archCores, new File(BASE_DIR, "cores"), false); }
                catch (IOException e) { e.printStackTrace(); }
            });
            executor.submit(() -> {
                try { copyAssetFolder(archAutoconfig, new File(BASE_DIR, "autoconfig"), false); }
                catch (IOException e) { e.printStackTrace(); }
            });
        } finally {
            executor.shutdown();
            while (!executor.isTerminated()) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
            try { updateRetroarchCfg(); } catch (IOException e) { e.printStackTrace(); }
            prefs.edit().putBoolean("firstRun", false).apply();
            runOnUiThread(this::finalStartup);
        }
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
            if (files != null) for (File file : files) {
                if (file.isDirectory()) deleteFolder(file);
                else file.delete();
            }
            folder.delete();
        }
    }

    private void copyAssetFolder(String assetFolder, File targetFolder, boolean createNomedia) throws IOException {
        String[] assets = getAssets().list(assetFolder);
        if (!targetFolder.exists()) targetFolder.mkdirs();

        if (createNomedia) {
            createNomediaInFolder(targetFolder);
        }

        if (assets != null && assets.length > 0) {
            for (String asset : assets) {
                String fullPath = assetFolder + "/" + asset;
                File outFile = new File(targetFolder, asset);

                if (getAssets().list(fullPath).length > 0) {
                    copyAssetFolder(fullPath, outFile, createNomedia);
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

    private void createNomediaInFolder(File folder) {
        File nomedia = new File(folder, ".nomedia");
        if (!nomedia.exists()) {
            try { nomedia.createNewFile(); }
            catch (IOException ignored) {}
        }
    }

    private void updateRetroarchCfg() throws IOException {
        File cfgFile = new File(CONFIG_DIR, "retroarch.cfg");
        if (!cfgFile.exists()) cfgFile.getParentFile().mkdirs();
        if (!cfgFile.exists()) cfgFile.createNewFile();

        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(cfgFile))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        } catch (IOException ignored) {}

        Map<String, String> cfgFlags = new HashMap<>();
        // --- Flags gerais ---
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

        // --- Flags por arquitetura ---
        cfgFlags.put("video_threaded", archCores.equals("cores32") ? "true" : "false");

        // --- Detectar touchscreen ---
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

        // --- Map das pastas de assets ---
        Map<String, File> assetFiles = new HashMap<>();
        for (String folder : ASSET_FOLDERS) assetFiles.put(folder, new File(BASE_DIR, folder));

        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            boolean replaced = false;
            for (Map.Entry<String, String> entry : ASSET_FLAGS.entrySet()) {
                String folder = entry.getKey();
                String flag = entry.getValue();
                if (line.startsWith(flag)) {
                    line = flag + " = \"" + assetFiles.get(folder).getAbsolutePath() + "\"";
                    replaced = true;
                    break;
                }
            }
            content.append(line).append("\n");
        }

        for (Map.Entry<String, String> entry : cfgFlags.entrySet()) {
            content.append(entry.getKey()).append(" = \"").append(entry.getValue()).append("\"\n");
        }

        try (FileOutputStream out = new FileOutputStream(cfgFile, false)) {
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
        String external = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/" + PACKAGE_NAME + "/files";
        retro.putExtra("EXTERNAL", external);
    }
}
