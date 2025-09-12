package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

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
        // As variáveis de progresso continuam aqui
        AtomicInteger processedFiles = new AtomicInteger(0);
        int totalFiles = 0;
    
        // onPreExecute agora é mais enxuto, apenas configura a UI
        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(MainMenuActivity.this);
            progressDialog.setTitle("Configurando RetroArch DRG...");
            String archMessage = archCores.equals("cores64") ?
                    "\nArquitetura dos Cores:\n  - arm64-v8a (64-bit)" :
                    "\nArquitetura dos Cores:\n  - armeabi-v7a (32-bit)";
            String message = archMessage + "\n\nClique em \"Sair\" após a configuração e prossiga com a instalação do sistema.\n\n(Customizado por Doug Retro Games)";
            SpannableString spannable = new SpannableString(message);
            int start = message.indexOf("\"Sair\"");
            int end = start + "\"Sair\"".length();
            spannable.setSpan(new StyleSpan(Typeface.BOLD), start, end, 0);
            progressDialog.setMessage(spannable);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
            progressDialog.show();
        }
    
        @Override
        protected Boolean doInBackground(Void... voids) {
            // --- MUDANÇA 1: Movendo a contagem de arquivos para a thread de background ---
            // Isso evita que a UI trave antes do diálogo aparecer.
            archAutoconfig = (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) ? "autoconfig-legacy" : "autoconfig";
            totalFiles = countAllFiles(ROOT_FOLDERS)
                    + countAllFiles(MEDIA_FOLDERS)
                    + countAllFiles(new String[]{archCores, archAutoconfig});
    
            // Se não houver arquivos para extrair, o progresso máximo é definido para evitar divisão por zero.
            if (totalFiles > 0) {
                runOnUiThread(() -> progressDialog.setMax(totalFiles));
            }
    
            // --- MUDANÇA 2: Pool de threads mais dinâmico ---
            // Usar o número de processadores disponíveis é uma abordagem mais robusta.
            int poolSize = Math.max(Runtime.getRuntime().availableProcessors() - 1, 2);
            ExecutorService executor = Executors.newFixedThreadPool(poolSize);
    
            for (String folder : ROOT_FOLDERS) {
                executor.submit(() -> {
                    try { copyAssetFolder(folder, new File(ROOT_DIR, folder)); }
                    catch (IOException e) { e.printStackTrace(); }
                });
            }
    
            for (String folder : MEDIA_FOLDERS) {
                executor.submit(() -> {
                    try { copyAssetFolder(folder, new File(MEDIA_DIR, folder)); }
                    catch (IOException e) { e.printStackTrace(); }
                });
            }
    
            executor.submit(() -> {
                try { copyAssetFolder(archCores, new File(ROOT_DIR, "cores")); }
                catch (IOException e) { e.printStackTrace(); }
            });
    
            executor.submit(() -> {
                try { copyAssetFolder(archAutoconfig, new File(MEDIA_DIR, "autoconfig")); }
                catch (IOException e) { e.printStackTrace(); }
            });
    
            executor.shutdown();
            try {
                // --- MUDANÇA 3: Substituindo o loop 'while' pela forma correta e eficiente ---
                // 'awaitTermination' bloqueia a thread de background sem consumir CPU, aguardando as tarefas.
                if (!executor.awaitTermination(30, TimeUnit.MINUTES)) {
                    executor.shutdownNow();
                    return false; // Retorna erro se exceder o tempo limite
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                return false; // Retorna erro se a thread for interrompida
            }
    
            // O restante do código continua como antes
            try {
                updateRetroarchCfg();
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
            return true;
        }
    
        @Override
        protected void onProgressUpdate(Integer... values) {
            // Agora, em vez de porcentagem, passamos o número de arquivos processados
            if (values.length > 0) {
                progressDialog.setProgress(values[0]);
            }
        }
    
        @Override
        protected void onPostExecute(Boolean result) {
            // O onPostExecute não precisa de mudanças significativas
            progressDialog.dismiss();
            prefs.edit().putBoolean("firstRun", false).apply();
    
            // Esta parte do código para processar imagens parece correta
            ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
            imageExecutor.submit(() -> processFolderForImages(new File(MEDIA_DIR, "overlays")));
            imageExecutor.shutdown();
            // Não é necessário esperar aqui, pode rodar em paralelo enquanto a próxima tela carrega
    
            finalStartup();
        }
    
        // --- MUDANÇA 4: Adicionando a chamada 'publishProgress' dentro do método de cópia ---
        private void copyAssetFolder(String assetFolder, File targetFolder) throws IOException {
            String[] assets = getAssets().list(assetFolder);
            if (!targetFolder.exists()) targetFolder.mkdirs();
    
            if (assets != null && assets.length > 0) {
                for (String asset : assets) {
                    String fullPath = assetFolder + "/" + asset;
                    File outFile = new File(targetFolder, asset);
    
                    if ("cores32".equals(archCores) && fullPath.equals("config/global.glslp")) {
                        publishProgress(processedFiles.incrementAndGet()); // Atualiza o progresso mesmo que pule
                        continue;
                    }
    
                    // Verifica se o 'asset' é um diretório ou arquivo
                    boolean isDir = false;
                    try (InputStream check = getAssets().open(fullPath)) {
                        // Se abrir, é um arquivo. Se der IOException, é um diretório.
                    } catch (IOException e) {
                        isDir = true;
                    }
    
                    if (isDir) {
                        copyAssetFolder(fullPath, outFile);
                    } else {
                        try (InputStream in = getAssets().open(fullPath);
                             FileOutputStream out = new FileOutputStream(outFile)) {
                            byte[] buffer = new byte[8192]; // Buffer maior para I/O mais eficiente
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                            }
                        }
                        // Publica o progresso após cada arquivo copiado
                        publishProgress(processedFiles.incrementAndGet());
                    }
                }
            } else {
                // Se a "pasta" de assets estiver vazia, ainda conta como um item processado
                publishProgress(processedFiles.incrementAndGet());
            }
        }
    
        // A lógica para contagem de arquivos e outras funções auxiliares permanece a mesma.
        // O método 'countFilesRecursive' tem um pequeno bug: ele retorna 0 para um erro de IO
        // e trata um arquivo como uma pasta vazia. Uma versão mais robusta é mostrada abaixo.
        private int countFilesRecursive(String path) {
            try {
                String[] assets = getAssets().list(path);
                if (assets == null || assets.length == 0) {
                    return 1; // É um arquivo ou um diretório vazio
                }
    
                int count = 0;
                for (String asset : assets) {
                    count += countFilesRecursive(path + "/" + asset);
                }
                return count;
            } catch (IOException e) {
                // Se deu exceção, provavelmente é um arquivo, não um diretório.
                return 1;
            }
        }

        private boolean hasImages(File dir) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) return false;

            String[] images = dir.list((d, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".bmp") ||
                        lower.endsWith(".svg") || lower.endsWith(".cpt");
            });

            return images != null && images.length > 0;
        }

        private void processFolderForImages(File dir) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) return;

            if (hasImages(dir)) {
                File nomedia = new File(dir, ".nomedia");
                try { if (!nomedia.exists()) nomedia.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
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

        private void updateRetroarchCfg() throws IOException {
            File originalCfg = new File(CONFIG_DIR, "retroarch.cfg");
            if (!originalCfg.exists()) originalCfg.getParentFile().mkdirs();
            if (originalCfg.exists()) originalCfg.delete();

            Map<String, String> cfgFlags = new HashMap<>();

            for (Map.Entry<String, String> entry : ROOT_FLAGS.entrySet()) {
                cfgFlags.put(entry.getValue(), new File(ROOT_DIR, entry.getKey()).getAbsolutePath());
            }

            for (Map.Entry<String, String> entry : MEDIA_FLAGS.entrySet()) {
                cfgFlags.put(entry.getValue(), new File(MEDIA_DIR, entry.getKey()).getAbsolutePath());
            }

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
            cfgFlags.put("android_input_disconnect_workaround", "false");
            cfgFlags.put("joypad_autoconfig_dir", new File(MEDIA_DIR, "autoconfig/android").getAbsolutePath());
            cfgFlags.put("osk_overlay_directory", new File(MEDIA_DIR, "overlays/keyboards").getAbsolutePath());
            cfgFlags.put("input_overlay", new File(MEDIA_DIR, "overlays/gamepads/neo-retropad/neo-retropad.cfg").getAbsolutePath());
            cfgFlags.put("video_threaded", "cores32".equals(archCores) ? "true" : "false");
            cfgFlags.put("video_driver", (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && "cores64".equals(archCores)) ? "vulkan" : "gl");

            cfgFlags.put("bundle_assets_extract_enable", "false");
            cfgFlags.put("bundle_assets_extract_last_version", "1756737486");
            cfgFlags.put("bundle_assets_extract_version_current", "1756737486");

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

            try (FileOutputStream out = new FileOutputStream(originalCfg, false)) {
                for (Map.Entry<String, String> entry : cfgFlags.entrySet()) {
                    String line = entry.getKey() + " = \"" + entry.getValue() + "\"\n";
                    out.write(line.getBytes());
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
