package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import android.app.AlertDialog;
import android.app.ProgressDialog;
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
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainMenuActivity extends PreferenceActivity {

    private static final int REQUEST_CODE_PERMISSIONS = 124;
    public static String PACKAGE_NAME;
    private SharedPreferences prefs;
    private boolean checkPermissions = false;

    private final String[] ASSET_FOLDERS = {
            "assets","database","filters","info","overlays","shaders","system","config","remaps","cheats"
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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        UserPreferences.updateConfigFile(this);

        archCores = System.getProperty("os.arch").contains("64") ? "cores64" : "cores32";
        archAutoconfig = (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) ? "autoconfig-legacy" : "autoconfig";

        checkPermissions();
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissions = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);

            if (!permissions.isEmpty()) {
                checkPermissions = true;
                requestPermissions(permissions.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
            }
        }

        if (!checkPermissions) startExtractionOrRetro();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_CODE_PERMISSIONS) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        boolean allGranted = true;
        for (int res : grantResults) if (res != PackageManager.PERMISSION_GRANTED) allGranted = false;

        if (allGranted) {
            prefs.edit().putInt("deniedCount", 0).apply();
            startExtractionOrRetro();
        } else {
            int denied = prefs.getInt("deniedCount", 0) + 1;
            prefs.edit().putInt("deniedCount", denied).apply();

            if (denied >= 2) {
                new AlertDialog.Builder(this)
                        .setTitle("Permissão Negada!")
                        .setMessage("Ative as permissões manualmente nas configurações ou reinstale o aplicativo.")
                        .setCancelable(false)
                        .setPositiveButton("Abrir Configurações", (d, w) -> {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.fromParts("package", getPackageName(), null));
                            startActivity(intent);
                        })
                        .setNegativeButton("Sair", (d, w) -> finish())
                        .show();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Permissões Necessárias!")
                        .setMessage("O aplicativo precisa das permissões de armazenamento para funcionar corretamente.")
                        .setCancelable(false)
                        .setPositiveButton("Conceder", (d, w) -> requestPermissions(permissions, REQUEST_CODE_PERMISSIONS))
                        .setNegativeButton("Sair", (d, w) -> finish())
                        .show();
            }
        }
    }

    private void startExtractionOrRetro() {
        if (prefs.getBoolean("firstRun", true))
            extractAssets();
        else
            finalStartup();
    }

    private void extractAssets() {
        new Thread(() -> {
            try {
                if (!BASE_DIR.exists()) BASE_DIR.mkdirs();
                removeUnusedFolders();

                int totalFiles = countAllFiles(ASSET_FOLDERS) + countAllFiles(new String[]{archCores, archAutoconfig});
                AtomicInteger processed = new AtomicInteger(0);

                ExecutorService executor = Executors.newFixedThreadPool(Math.min(ASSET_FOLDERS.length + 2, 4));
                for (String folder : ASSET_FOLDERS) executor.submit(() -> copyAsset(folder, new File(BASE_DIR, folder), processed));
                executor.submit(() -> copyAsset(archCores, new File(BASE_DIR, "cores"), processed));
                executor.submit(() -> copyAsset(archAutoconfig, new File(BASE_DIR, "autoconfig"), processed));

                executor.shutdown();
                while (!executor.isTerminated()) Thread.sleep(100);

                updateRetroarchCfg();
                prefs.edit().putBoolean("firstRun", false).apply();
                runOnUiThread(this::finalStartup);
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private void removeUnusedFolders() {
        for (String f : new String[]{"cores32","cores64"}) if (!f.equals(archCores)) deleteFolder(new File(BASE_DIR, f));
        for (String f : new String[]{"autoconfig-legacy","autoconfig"}) if (!f.equals(archAutoconfig)) deleteFolder(new File(BASE_DIR, f));
    }

    private void deleteFolder(File folder) {
        if (!folder.exists()) return;
        for (File f : folder.listFiles()) if (f.isDirectory()) deleteFolder(f); else f.delete();
        folder.delete();
    }

    private int countAllFiles(String[] folders) {
        int count = 0;
        for (String f : folders) count += countFilesRecursive(f);
        return count;
    }

    private int countFilesRecursive(String assetFolder) {
        try {
            String[] assets = getAssets().list(assetFolder);
            if (assets == null || assets.length == 0) return 1;
            int total = 0;
            for (String a : assets) total += countFilesRecursive(assetFolder + "/" + a);
            return total;
        } catch (IOException e) { return 0; }
    }

    private void copyAsset(String assetFolder, File target, AtomicInteger processed) {
        try {
            String[] assets = getAssets().list(assetFolder);
            if (!target.exists()) target.mkdirs();
            if (assets != null && assets.length > 0) {
                for (String a : assets) {
                    File out = new File(target, a);
                    if (getAssets().list(assetFolder + "/" + a).length > 0) copyAsset(assetFolder + "/" + a, out, processed);
                    else try (InputStream in = getAssets().open(assetFolder + "/" + a);
                             FileOutputStream outStream = new FileOutputStream(out)) {
                        byte[] buf = new byte[1024];
                        int r;
                        while ((r = in.read(buf)) != -1) outStream.write(buf, 0, r);
                        processed.incrementAndGet();
                    }
                }
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void updateRetroarchCfg() throws IOException {
        File cfg = new File(CONFIG_DIR, "retroarch.cfg");
        if (!cfg.exists()) cfg.getParentFile().mkdirs();

        Map<String,String> cfgFlags = new HashMap<>();
        cfgFlags.put("menu_scale_factor","0.600000"); // Flags mantidas
        cfgFlags.put("ozone_menu_color_theme","10");
        cfgFlags.put("input_overlay_opacity","0.700000");
        cfgFlags.put("input_overlay_hide_when_gamepad_connected","true");
        cfgFlags.put("video_smooth","false");
        cfgFlags.put("aspect_ratio_index","1");
        cfgFlags.put("netplay_nickname","RetroGameBox");
        cfgFlags.put("menu_enable_widgets","true");
        cfgFlags.put("pause_nonactive","false");
        cfgFlags.put("menu_mouse_enable","false");
        cfgFlags.put("input_player1_analog_dpad_mode","1");
        cfgFlags.put("input_player2_analog_dpad_mode","1");
        cfgFlags.put("input_player3_analog_dpad_mode","1");
        cfgFlags.put("input_player4_analog_dpad_mode","1");
        cfgFlags.put("input_player5_analog_dpad_mode","1");
        cfgFlags.put("input_menu_toggle_gamepad_combo","9");
        cfgFlags.put("input_quit_gamepad_combo","4");
        cfgFlags.put("input_bind_timeout","4");
        cfgFlags.put("input_bind_hold","1");
        cfgFlags.put("all_users_control_menu","true");
        cfgFlags.put("input_poll_type_behavior","1");
        cfgFlags.put("android_input_disconnect_workaround","true");
        cfgFlags.put("video_threaded", "cores32".equals(archCores)?"true":"false");
        cfgFlags.put("video_driver", Build.VERSION.SDK_INT>=Build.VERSION_CODES.R?"vulkan":"gl");

        boolean hasTouch = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        boolean leanback = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        if (hasTouch && !leanback) {
            cfgFlags.put("input_overlay_enable","true");
            cfgFlags.put("input_enable_hotkey_btn","109");
            cfgFlags.put("input_menu_toggle_btn","100");
            cfgFlags.put("input_save_state_btn","103");
            cfgFlags.put("input_load_state_btn","102");
            cfgFlags.put("input_state_slot_decrease_btn","104");
            cfgFlags.put("input_state_slot_increase_btn","105");
        } else {
            cfgFlags.put("input_overlay_enable","false");
            cfgFlags.put("input_enable_hotkey_btn","196");
            cfgFlags.put("input_menu_toggle_btn","188");
            cfgFlags.put("input_save_state_btn","193");
            cfgFlags.put("input_load_state_btn","192");
            cfgFlags.put("input_state_slot_decrease_btn","194");
            cfgFlags.put("input_state_slot_increase_btn","195");
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(cfg,false))) {
            for (String f : ASSET_FOLDERS) writer.write(ASSET_FLAGS.get(f) + " = \"" + new File(BASE_DIR,f).getAbsolutePath() + "\"\n");
            for (Map.Entry<String,String> e : cfgFlags.entrySet()) writer.write(e.getKey() + " = \"" + e.getValue() + "\"\n");
        }
    }

    private void finalStartup() {
        Intent retro = new Intent(this, RetroActivityFuture.class);
        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startRetroActivity(
                retro,
                null,
                new File(BASE_DIR,"cores").getAbsolutePath(),
                new File(CONFIG_DIR,"retroarch.cfg").getAbsolutePath(),
                Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD),
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
        retro.putExtra("EXTERNAL", Environment.getExternalStorageDirectory().getAbsolutePath()+"/Android/data/"+PACKAGE_NAME+"/files");
    }
} 
