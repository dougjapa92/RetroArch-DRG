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
        archCores = arch.contains("64") ? "cores64" : "cores32";

        checkRuntimePermissions();
    }

    private boolean addPermission(List<String> permissionsList, String permission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            permissionsList.add(permission);
            return !shouldShowRequestPermissionRationale(permission);
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
                // Solicita permissões
                requestPermissions(permissionsList.toArray(new String[0]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                return; // IMPORTANTE: sai do método, não chama startExtractionOrRetro ainda
            }
        }
    
        // Somente se todas já estiverem concedidas
        startExtractionOrRetro();
    }

    // Flags necessárias
    private boolean permissionsHandled = false;
    private boolean wentToSettings = false;
    
    private void handlePermissionStatus(String[] permissions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
    
        List<String> missingPermissions = new ArrayList<>();
        addPermission(missingPermissions, Manifest.permission.READ_EXTERNAL_STORAGE);
        addPermission(missingPermissions, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    
        boolean allGranted = missingPermissions.isEmpty();
    
        if (allGranted) {
            prefs.edit().putInt("deniedCount", 0).apply();
            permissionsHandled = true;
    
            // Chama a extração/executa RetroArch mesmo na primeira execução
            startExtractionOrRetro();
        } else {
            int deniedCount = prefs.getInt("deniedCount", 0);
    
            if (deniedCount >= 2 || wentToSettings) {
                new AlertDialog.Builder(this)
                    .setTitle("Permissão Negada!")
                    .setMessage("Ative as permissões manualmente nas configurações ou reinstale o aplicativo.")
                    .setCancelable(false)
                    .setPositiveButton("Abrir Configurações", (dialog, which) -> {
                        wentToSettings = true;
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        Uri uri = Uri.fromParts("package", getPackageName(), null);
                        intent.setData(uri);
                        startActivity(intent);
                    })
                    .setNegativeButton("Sair", (dialog, which) -> finish())
                    .show();
            } else {
                deniedCount++;
                prefs.edit().putInt("deniedCount", deniedCount).apply();
    
                new AlertDialog.Builder(this)
                    .setTitle("Permissões Necessárias!")
                    .setMessage("O aplicativo precisa das permissões de armazenamento para funcionar corretamente.")
                    .setCancelable(false)
                    .setPositiveButton("Conceder", (dialog, which) -> {
                        if (permissions != null)
                            requestPermissions(permissions, REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                        else
                            checkRuntimePermissions();
                    })
                    .setNegativeButton("Sair", (dialog, which) -> finish())
                    .show();
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (wentToSettings) {
            handlePermissionStatus(null);
            wentToSettings = false; // reseta a flag
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS) {
            handlePermissionStatus(permissions);
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void startExtractionOrRetro() {
        if (prefs.getBoolean("firstRun", true))
            new UnifiedExtractionTask().execute();
        else
            finalStartup();
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
            for (String folder : new String[]{"cores32", "cores64"})
                if (!folder.equals(archCores)) deleteFolder(new File(BASE_DIR, folder));
            for (String folder : new String[]{"autoconfig-legacy", "autoconfig"})
                if (!folder.equals(archAutoconfig)) deleteFolder(new File(BASE_DIR, folder));
        }

        private void deleteFolder(File folder) {
            if (folder.exists()) {
                for (File file : folder.listFiles()) {
                    if (file.isDirectory()) deleteFolder(file);
                    else file.delete();
                }
                folder.delete();
            }
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(ASSET_FOLDERS.length + 2, 4));

            for (String folder : ASSET_FOLDERS) executor.submit(() -> copyFolderSafe(folder, new File(BASE_DIR, folder)));
            executor.submit(() -> copyFolderSafe(archCores, new File(BASE_DIR, "cores")));
            executor.submit(() -> copyFolderSafe(archAutoconfig, new File(BASE_DIR, "autoconfig")));

            executor.shutdown();
            while (!executor.isTerminated()) {
                publishProgress((processedFiles.get() * 100) / totalFiles);
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }

            try { updateRetroarchCfg(); } catch (IOException e) { return false; }
            return true;
        }

        private void copyFolderSafe(String assetFolder, File targetFolder) {
            try { copyAssetFolder(assetFolder, targetFolder); }
            catch (IOException e) { e.printStackTrace(); }
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
        
            // Adicionar .nomedia se for subpasta de assets ou overlays (não na raiz)
            if ((assetFolder.startsWith("assets/") && !assetFolder.equals("assets")) ||
                (assetFolder.startsWith("overlays/") && !assetFolder.equals("overlays"))) {
                File noMedia = new File(targetFolder, ".nomedia");
                if (!noMedia.exists()) noMedia.createNewFile();
            }
        
            if (assets != null && assets.length > 0) {
                for (String asset : assets) {
                    String fullPath = assetFolder + "/" + asset;
                    File outFile = new File(targetFolder, asset);
        
                    // Ignorar global.glslp se cores32 e pasta config
                    if ("cores32".equals(archCores) && fullPath.equals("config/global.glslp")) {
                        processedFiles.incrementAndGet();
                        continue;
                    }
        
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

        @Override
        protected void onProgressUpdate(Integer... values) { progressDialog.setProgress(values[0]); }

        @Override
        protected void onPostExecute(Boolean result) {
            progressDialog.dismiss();
            prefs.edit().putBoolean("firstRun", false).apply();
            finalStartup();
        }

        private void updateRetroarchCfg() throws IOException {
            File originalCfg = new File(CONFIG_DIR, "retroarch.cfg");
            if (!originalCfg.exists()) originalCfg.getParentFile().mkdirs();

            Map<String, String> cfgFlags = new HashMap<>();
            
            // Flags Globais
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

            // Flags Condicionais
            cfgFlags.put("video_threaded", "cores32".equals(archCores) ? "true" : "false");
            cfgFlags.put("video_driver", (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && "cores64".equals(archCores)) ? "vulkan" : "gl");

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

            // Populando retroarch.cfg
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(originalCfg, false))) {
                for (String folder : ASSET_FOLDERS) {
                    File path = new File(BASE_DIR, folder);
                    writer.write(ASSET_FLAGS.get(folder) + " = \"" + path.getAbsolutePath() + "\"\n");
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
