package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

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

    private AtomicInteger processedFiles = new AtomicInteger(0);
    private ExecutorService executor;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        UserPreferences.updateConfigFile(this);

        String arch = System.getProperty("os.arch");
        archCores = arch.contains("64") ? "cores64" : "cores32";

        startExtractionOrRetro();
    }

    private void startExtractionOrRetro() {
        boolean firstRun = prefs.getBoolean("firstRun", true);
        if (firstRun) new UnifiedExtractionTask().execute();
        else finalStartup();
    }

    private class UnifiedExtractionTask extends AsyncTask<Void, Integer, Boolean> {
        ProgressDialog progressDialog;
        int totalFiles = 0;

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(MainMenuActivity.this);
            progressDialog.setTitle("Configurando RetroArch DRG...");
            String archMessage = archCores.equals("cores64") ? "\nArquitetura do Processador:\n  - arm64-v8a (64-bit)" : "\nArquitetura do Processador:\n  - armeabi-v7a (32-bit)";
            progressDialog.setMessage(archMessage + "\n\nClique em \"Sair do RetroArch\" após a configuração ou force o encerramento do aplicativo.");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
            progressDialog.show();

            if (!BASE_DIR.exists()) BASE_DIR.mkdirs();
            removeUnusedArchFolders();

            archAutoconfig = (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) ? "autoconfig-legacy" : "autoconfig";

            totalFiles = countAllFiles(ASSET_FOLDERS) + countAllFiles(new String[]{archCores, archAutoconfig});

            int cores = Runtime.getRuntime().availableProcessors();
            executor = Executors.newFixedThreadPool(cores);
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
            try {
                submitAssetFolders(ASSET_FOLDERS, null);
                submitAssetFolders(new String[]{archCores}, "cores");
                submitAssetFolders(new String[]{archAutoconfig}, "autoconfig");

                executor.shutdown();
                while (!executor.isTerminated()) {
                    publishProgress((processedFiles.get() * 100) / totalFiles);
                    Thread.sleep(50);
                }

                updateRetroarchCfg();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                return false;
            }
            return true;
        }

        private void submitAssetFolders(String[] folders, String targetFolderName) {
            for (String folder : folders) {
                executor.submit(() -> {
                    try {
                        File target = (targetFolderName != null) ? new File(BASE_DIR, targetFolderName) : new File(BASE_DIR, folder);
                        copyAssetFolder(folder, target);
                    } catch (IOException e) { e.printStackTrace(); }
                });
            }
        }

        private void copyAssetFolder(String assetFolder, File targetFolder) throws IOException {
            String[] assets = getAssets().list(assetFolder);
            if (!targetFolder.exists()) targetFolder.mkdirs();
            if (assets == null || assets.length == 0) return;

            boolean hasMedia = false;
            List<Runnable> subTasks = new ArrayList<>();

            for (String asset : assets) {
                String fullPath = assetFolder + "/" + asset;
                File outFile = new File(targetFolder, asset);
                String[] subAssets = getAssets().list(fullPath);

                if (subAssets != null && subAssets.length > 0) {
                    subTasks.add(() -> {
                        try { copyAssetFolder(fullPath, outFile); } catch (IOException e) { e.printStackTrace(); }
                    });
                } else {
                    subTasks.add(() -> {
                        try (InputStream in = getAssets().open(fullPath);
                             FileOutputStream out = new FileOutputStream(outFile)) {
                            byte[] buffer = new byte[1024];
                            int read;
                            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                        } catch (IOException e) { e.printStackTrace(); }
                    });

                    String lower = asset.toLowerCase();
                    if (!hasMedia && (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                                      lower.endsWith(".gif") || lower.endsWith(".mp4") || lower.endsWith(".mkv") ||
                                      lower.endsWith(".avi") || lower.endsWith(".mov"))) {
                        hasMedia = true;
                    }
                }
            }

            if (hasMedia) {
                File nomedia = new File(targetFolder, ".nomedia");
                if (!nomedia.exists()) nomedia.createNewFile();
            }

            for (Runnable task : subTasks) executor.submit(task);
            processedFiles.addAndGet(assets.length);
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            progressDialog.setProgress(values[0]);
        }

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

        private void updateRetroarchCfg() throws IOException {
            File originalCfg = new File(CONFIG_DIR, "retroarch.cfg");
            if (!originalCfg.exists()) originalCfg.getParentFile().mkdirs();
            if (!originalCfg.exists()) originalCfg.createNewFile();

            List<String> lines = new ArrayList<>();
            if (originalCfg.exists()) {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(originalCfg))) {
                    String line;
                    while ((line = reader.readLine()) != null) lines.add(line);
                }
            }

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

            if (archCores.equals("cores32")) cfgFlags.put("video_threaded", "true");
            else if (archCores.equals("cores64")) cfgFlags.put("video_threaded", "false");

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

            StringBuilder content = new StringBuilder();
            for (String line : lines) {
                for (Map.Entry<String, String> entry : ASSET_FLAGS.entrySet()) {
                    String folder = entry.getKey();
                    String flag = entry.getValue();
                    if (line.startsWith(flag)) {
                        line = flag + " = \"" + assetFiles.get(folder).getAbsolutePath() + "\"";
                        break;
                    }
                }
                content.append(line).append("\n");
            }

            for (Map.Entry<String, String> entry : cfgFlags.entrySet()) {
                content.append(entry.getKey()).append(" = \"").append(entry.getValue()).append("\"\n");
            }

            try (FileOutputStream out = new FileOutputStream(originalCfg, false)) {
                out.write(content.toString().getBytes());
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