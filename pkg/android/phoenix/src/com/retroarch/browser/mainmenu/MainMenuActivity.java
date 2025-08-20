package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.AsyncTask;
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
import java.io.BufferedWriter;
import java.io.FileWriter;
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
    private boolean checkPermissions = false;
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
        archCores = arch != null && arch.contains("64") ? "cores64" : "cores32";

        checkRuntimePermissions();
    }

    private boolean addPermission(List<String> permissionsList, String permission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(permission);
                return !shouldShowRequestPermissionRationale(permission);
            }
        }
        return true;
    }

    private void checkRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissionsList = new ArrayList<>();

            addPermission(permissionsList, Manifest.permission.READ_EXTERNAL_STORAGE);
            addPermission(permissionsList, Manifest.permission.WRITE_EXTERNAL_STORAGE);

            if (!permissionsList.isEmpty()) {
                checkPermissions = true;
                requestPermissions(permissionsList.toArray(new String[0]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
            }
        }

        if (!checkPermissions)
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
            progressDialog.setTitle("Configurando RetroArch DRG...");
            progressDialog.setMessage((archCores.equals("cores64") ? "\nArquitetura dos Cores:\n   - arm64-v8a (64-bit)"
                    : "\nArquitetura dos Cores:\n   - armeabi-v7a (32-bit)") +
                    "\n\nClique em \"Sair do RetroArch\" após a configuração ou force o encerramento do aplicativo.");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
            progressDialog.show();

            if (!BASE_DIR.exists()) BASE_DIR.mkdirs();
            archAutoconfig = (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) ? "autoconfig-legacy" : "autoconfig";
            removeUnusedArchFolders();
            totalFiles = countAllFiles(ASSET_FOLDERS) + countAllFiles(new String[]{archCores, archAutoconfig});
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

        @Override
        protected Boolean doInBackground(Void... voids) {
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(ASSET_FOLDERS.length + 2, 4));

            for (String folder : ASSET_FOLDERS) {
                executor.submit(() -> {
                    try { copyAssetFolder(folder, new File(BASE_DIR, folder)); }
                    catch (IOException e) { e.printStackTrace(); }
                });
            }

            executor.submit(() -> {
                try { copyAssetFolder(archCores, new File(BASE_DIR, "cores")); }
                catch (IOException e) { e.printStackTrace(); }
            });

            executor.submit(() -> {
                try { copyAssetFolder(archAutoconfig, new File(BASE_DIR, "autoconfig")); }
                catch (IOException e) { e.printStackTrace(); }
            });

            executor.shutdown();
            while (!executor.isTerminated()) {
                publishProgress((processedFiles.get() * 100) / totalFiles);
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }

            try { updateRetroarchCfg(); } catch (IOException e) { return false; }
            return true;
        }

        @Override
        protected void onProgressUpdate(Integer... values) { progressDialog.setProgress(values[0]); }

        @Override
        protected void onPostExecute(Boolean result) {
            progressDialog.dismiss();
            prefs.edit().putBoolean("firstRun", false).apply();
            finalStartup();
        }

        private int countAllFiles(String[] folders) {
            int count = 0;
            for (String folder : folders) count += countFilesRecursive(folder);
            return count;
        }

        private int countFilesRecursive(String assetFolder) {
            try {
                String[] assets = getAssets().list(assetFolder);
                if (assets == null || assets.length == 0) return 1;
                int total = 0;
                for (String asset : assets) total += countFilesRecursive(assetFolder + "/" + asset);
                return total;
            } catch (IOException e) { return 0; }
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
                        processedFiles.incrementAndGet();
                    }
                }
            }
        }

        private void updateRetroarchCfg() throws IOException {
            File originalCfg = new File(CONFIG_DIR, "retroarch.cfg");
            if (!originalCfg.exists()) originalCfg.getParentFile().mkdirs();

            Map<String, String> cfgFlags = new HashMap<>();

            // ⚙️ Flags gerais
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

            // ⚙️ Flags específicas por arquitetura
            if ("cores32".equals(archCores))
                cfgFlags.put("video_threaded", "true");
            else
                cfgFlags.put("video_threaded", "false");

            // ⚙️ Flags específicas por versão do Android
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                cfgFlags.put("video_driver", "vulkan");
            else
                cfgFlags.put("video_driver", "gl");

            // ⚙️ Flags específicas por tipo de dispositivo
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
            for (String folder : ASSET_FOLDERS)
                assetFiles.put(folder, new File(BASE_DIR, folder));

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(originalCfg, false))) {
                for (Map.Entry<String, String> entry : ASSET_FLAGS.entrySet()) {
                    String folder = entry.getKey();
                    String flag = entry.getValue();
                    File path = assetFiles.get(folder);
                    if (path != null)
                        writer.write(flag + " = \"" + path.getAbsolutePath() + "\"\n");
                }
                for (Map.Entry<String, String> entry : cfgFlags.entrySet()) {
                    writer.write(entry.getKey() + " = \"" + entry.getValue() + "\"\n");
                }
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
