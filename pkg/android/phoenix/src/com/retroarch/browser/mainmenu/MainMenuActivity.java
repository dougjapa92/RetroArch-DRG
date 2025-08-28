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
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.graphics.Typeface;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.Manifest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.style.StyleSpan;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainMenuActivity extends PreferenceActivity {

    private final int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    public static String PACKAGE_NAME;
    private boolean checkPermissions = false;
    private SharedPreferences prefs;

    private final String[] ROOT_FOLDERS = {
        "assets", "cheats", "database", "filters", "info", "shaders", "system"
    };

    private final Map<String, String> ROOT_FLAGS = new HashMap<String, String>() {{
        put("assets", "assets_directory");
        put("cheats", "cheat_database_path");
        put("database", "database_directory");
        put("filters", "filters_directory");
        put("info", "info_directory");
        put("shaders", "shaders_directory");
        put("system", "system_directory");
    }};

    private final String[] MEDIA_FOLDERS = {
        "overlays", "config", "remaps"
    };

    private final Map<String, String> MEDIA_FLAGS = new HashMap<String, String>() {{
        put("config", "rgui_config_directory");
        put("overlays", "overlay_directory");
        put("remaps", "input_remapping_directory");
    }};

    private File ROOT_DIR;
    private final File MEDIA_DIR = new File(Environment.getExternalStorageDirectory(), "/Android/media/com.retroarch");
    private final File CONFIG_DIR = new File(Environment.getExternalStorageDirectory() + "/Android/data/com.retroarch/files");

    private String archCores;
    private String archAutoconfig;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PACKAGE_NAME = getPackageName();
		ROOT_DIR = new File(getApplicationInfo().dataDir);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        UserPreferences.updateConfigFile(this);

        String arch = System.getProperty("os.arch");
        archCores = arch.contains("64") ? "cores64" : "cores32";

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

    private boolean permissionsHandled = false;
    private boolean wentToSettings = false;
    private boolean firstDenialHandled = false;

    private void checkRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissionsList = new ArrayList<>();
            addPermission(permissionsList, Manifest.permission.READ_EXTERNAL_STORAGE);
            addPermission(permissionsList, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            if (!permissionsList.isEmpty()) {
                requestPermissions(permissionsList.toArray(new String[0]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                return;
            }
        }
        startExtractionOrRetro();
    }

    private void handlePermissionStatus(String[] permissions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || permissionsHandled) return;

        List<String> missingPermissions = new ArrayList<>();
        addPermission(missingPermissions, Manifest.permission.READ_EXTERNAL_STORAGE);
        addPermission(missingPermissions, Manifest.permission.WRITE_EXTERNAL_STORAGE);

        boolean allGranted = missingPermissions.isEmpty();

        if (allGranted) {
            prefs.edit().putInt("deniedCount", 0).apply();
            permissionsHandled = true;
            startExtractionOrRetro();
        } else {
            int deniedCount = prefs.getInt("deniedCount", 0);
            if (permissions != null) deniedCount++;
            prefs.edit().putInt("deniedCount", deniedCount).apply();

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
            } else if (!firstDenialHandled) {
                firstDenialHandled = true;
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
            wentToSettings = false;
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
            String archMessage = archCores.equals("cores64") ?
                    "\nArquitetura dos Cores:\n  - arm64-v8a (64-bit)" :
                    "\nArquitetura dos Cores:\n  - armeabi-v7a (32-bit)";
            String message = archMessage + "\n\nClique em \"Sair\" após a configuração e prossiga com a instalação do Retro Game Box.";
            SpannableString spannable = new SpannableString(message);
            int start = message.indexOf("\"Sair\"");
            int end = start + "\"Sair\"".length();
            spannable.setSpan(new StyleSpan(Typeface.BOLD), start, end, 0);
            progressDialog.setMessage(spannable);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
            progressDialog.show();

            archAutoconfig = (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) ? "autoconfig-legacy" : "autoconfig";

            // Conta arquivos e pastas com imagens para progresso
            totalFiles = countAllFiles(ROOT_FOLDERS)
					+ countAllFiles(MEDIA_FOLDERS)
                    + countAllFiles(new String[]{archCores, archAutoconfig});
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            // Limita o número de threads para não saturar o dispositivo
            int poolSize = Math.min(ROOT_FOLDERS.length + MEDIA_FOLDERS.length + 2, 4);
            ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        
            // ROOT_FOLDERS
            for (String folder : ROOT_FOLDERS) {
                executor.submit(() -> {
                    try { copyAssetFolder(folder, new File(ROOT_DIR, folder)); }
                    catch (IOException e) { e.printStackTrace(); }
                });
            }
        
            // MEDIA_FOLDERS
            for (String folder : MEDIA_FOLDERS) {
                executor.submit(() -> {
                    try { copyAssetFolder(folder, new File(MEDIA_DIR, folder)); }
                    catch (IOException e) { e.printStackTrace(); }
                });
            }
        
            // Cores
            executor.submit(() -> {
                try { copyAssetFolder(archCores, new File(ROOT_DIR, "cores")); }
                catch (IOException e) { e.printStackTrace(); }
            });

            // Autoconfig
            executor.submit(() -> {
                try { copyAssetFolder(archAutoconfig, new File(MEDIA_DIR, "autoconfig")); }
                catch (IOException e) { e.printStackTrace(); }
            });
        
            // Aguarda todas as tasks finalizarem
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

            ExecutorService executor = Executors.newFixedThreadPool(3);

            executor.submit(() -> processFolderForImages(new File(MEDIA_DIR, "overlays")));

			executor.shutdown();
			try {
			    // Aguarda até 3 minutos
			    if (!executor.awaitTermination(3, TimeUnit.MINUTES)) {
			        executor.shutdownNow(); // força encerramento se não terminar
			    }
			} catch (InterruptedException e) {
			    executor.shutdownNow();
			    Thread.currentThread().interrupt();
			}

            finalStartup();
        }

        /** Verifica se a pasta contém imagens */
        private boolean hasImages(File dir) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) return false;
    
            String[] images = dir.list((d, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".bmp") || 
                       lower.endsWith(".svg") || lower.endsWith(".cpt");
            });
    
            return images != null && images.length > 0;
        }
    
        /** Cria .nomedia em uma pasta se encontrar ao menos uma imagem, e continua nas subpastas */
        private void processFolderForImages(File dir) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) return;
    
            if (hasImages(dir)) {
                File nomedia = new File(dir, ".nomedia");
                try { if (!nomedia.exists()) nomedia.createNewFile(); } 
                catch (IOException e) { e.printStackTrace(); }
            }
    
            File[] subDirs = dir.listFiles(File::isDirectory);
            if (subDirs != null) {
                for (File subDir : subDirs) processFolderForImages(subDir);
            }
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

		private void updateRetroarchCfg() throws IOException {
		    File originalCfg = new File(CONFIG_DIR, "retroarch.cfg");
		    if (!originalCfg.exists()) originalCfg.getParentFile().mkdirs();
		    if (!originalCfg.exists()) originalCfg.createNewFile();
		
		    Map<String, String> cfgFlags = new HashMap<>();
		
		    // ROOT_FLAGS
		    for (Map.Entry<String, String> entry : ROOT_FLAGS.entrySet()) {
		        cfgFlags.put(entry.getValue(), new File(ROOT_DIR, entry.getKey()).getAbsolutePath());
		    }
		
		    // MEDIA_FLAGS
		    for (Map.Entry<String, String> entry : MEDIA_FLAGS.entrySet()) {
		        cfgFlags.put(entry.getValue(), new File(MEDIA_DIR, entry.getKey()).getAbsolutePath());
		    }
		
		    // Flags Globais e Condicionais
		    cfgFlags.put("menu_driver", "ozone");
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
			cfgFlags.put("joypad_autoconfig_directory", MEDIA_DIR + "/autoconfig");
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
		
		    // Lê linhas existentes
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(originalCfg)))) {
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
            }
        
            Set<String> processedKeys = new HashSet<>();
            StringBuilder content = new StringBuilder();
        
            // Atualiza linhas existentes e marca flags processadas
            for (String line : lines) {
                boolean replaced = false;
                for (Map.Entry<String, String> entry : cfgFlags.entrySet()) {
                    if (line.startsWith(entry.getKey())) {
                        content.append(entry.getKey()).append(" = \"").append(entry.getValue()).append("\"\n");
                        processedKeys.add(entry.getKey());
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) content.append(line).append("\n");
            }
        
            // Adiciona flags novas que ainda não foram processadas
            for (Map.Entry<String, String> entry : cfgFlags.entrySet()) {
                if (!processedKeys.contains(entry.getKey())) {
                    content.append(entry.getKey()).append(" = \"").append(entry.getValue()).append("\"\n");
                }
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
                new File(ROOT_DIR, "cores").getAbsolutePath(),
                new File(CONFIG_DIR, "retroarch.cfg").getAbsolutePath(),
                Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
                ROOT_DIR.getAbsolutePath(),
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
