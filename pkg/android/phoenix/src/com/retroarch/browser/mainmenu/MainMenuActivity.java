package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.Manifest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainMenuActivity extends PreferenceActivity {

    private final int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    public static String PACKAGE_NAME;
    private SharedPreferences prefs;
    private String selectedAspectRatioIndex = "1";

    private final String[] ROOT_FOLDERS = {"assets", "cheats", "database", "filters", "info", "shaders", "system"};
    private final Map<String, String> ROOT_FLAGS = new HashMap<String, String>() {{
        put("assets", "assets_directory");
        put("cheats", "cheat_database_path");
        put("database", "database_directory");
        put("filters", "filters_directory");
        put("info", "info_directory");
        put("shaders", "shaders_directory");
        put("system", "system_directory");
    }};

    private final String[] MEDIA_FOLDERS = {"overlays", "config", "remaps"};
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

    private boolean permissionsHandled = false;
    private boolean wentToSettings = false;
    private boolean firstDenialHandled = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PACKAGE_NAME = getPackageName();
        ROOT_DIR = new File(getApplicationInfo().dataDir);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        UserPreferences.updateConfigFile(this);

        decideCoresFolder();

        checkRuntimePermissions();
    }

    private void decideCoresFolder() {
        boolean process64 = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                process64 = android.os.Process.is64Bit();
            } catch (Throwable ignored) {}
        }

        boolean os64 = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String[] abis64 = Build.SUPPORTED_64_BIT_ABIS;
            os64 = (abis64 != null && abis64.length > 0);
        } else {
            String arch = System.getProperty("os.arch");
            os64 = arch != null && arch.contains("64");
        }

        boolean prefer64 = process64 || os64;
        this.archCores = prefer64 ? "cores64" : "cores32";

        this.archAutoconfig = "autoconfig";
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

        if (missingPermissions.isEmpty()) {
            prefs.edit().putInt("deniedCount", 0).apply();
            permissionsHandled = true;
            startExtractionOrRetro();
        } else {
            int deniedCount = prefs.getInt("deniedCount", 0);
            if (permissions != null) deniedCount++;
            prefs.edit().putInt("deniedCount", deniedCount).apply();

            if (deniedCount >= 2 || wentToSettings) {
                new AlertDialog.Builder(this)
                        .setTitle("PermissÃ£o Negada!")
                        .setMessage("Ative as permissÃµes manualmente nas configuraÃ§Ãµes.")
                        .setCancelable(false)
                        .setPositiveButton("ABRIR CONFIGURAÃ‡Ã•ES", (dialog, which) -> {
                            wentToSettings = true;
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            Uri uri = Uri.fromParts("package", getPackageName(), null);
                            intent.setData(uri);
                            startActivity(intent);
                        })
                        .setNegativeButton("SAIR", (dialog, which) -> finish())
                        .show();
            } else if (!firstDenialHandled) {
                firstDenialHandled = true;
                new AlertDialog.Builder(this)
                        .setTitle("PermissÃµes NecessÃ¡rias!")
                        .setMessage("O aplicativo precisa das permissÃµes de armazenamento.")
                        .setCancelable(false)
                        .setPositiveButton("CONCEDER", (dialog, which) -> {
                            if (permissions != null)
                                requestPermissions(permissions, REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                            else
                                checkRuntimePermissions();
                        })
                        .setNegativeButton("SAIR", (dialog, which) -> finish())
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
        if (prefs.getBoolean("firstRun", true)) showAspectRatioDialog();
        else finalStartup();
    }

    private void showAspectRatioDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("ConfiguraÃ§Ã£o Inicial").setMessage("Escolha a proporÃ§Ã£o de tela dos jogos:")
                .setPositiveButton("TELA CHEIA (16:9)", (d, w) -> selectedAspectRatioIndex = "1")
                .setNegativeButton("ORIGINAL (4:3)", (d, w) -> selectedAspectRatioIndex = "20")
                .setOnDismissListener(d -> new UnifiedExtractionTask().execute())
                .setCancelable(false).create().show();
    }

    private class UnifiedExtractionTask extends AsyncTask<Void, Long, Boolean> {
        ProgressDialog progressDialog;
        AtomicLong totalExtractedBytes = new AtomicLong(0);
        // Sincronizado via AtomicLong para acesso seguro entre threads paralelas
        AtomicLong lastPublishedMB = new AtomicLong(-1);
        int totalMB = 0;

        @Override
        protected void onPreExecute() {
            totalMB = archCores.equals("cores64") ? 550 : 438;

            progressDialog = new ProgressDialog(MainMenuActivity.this);
            progressDialog.setTitle("Configurando RetroArch DRG...");

            String archMessage = archCores.equals("cores64")
                    ? "\nArquitetura dos Cores:\n  - arm64-v8a (64-bit)"
                    : "\nArquitetura dos Cores:\n  - armeabi-v7a (32-bit)";

            String message = archMessage
                    + "\n EspaÃ§o necessÃ¡rio: " + totalMB + " MB"
                    + "\n\n(Customizado por Doug Retro Games)";

            SpannableString spannable = new SpannableString(message);
            int start = message.indexOf("Doug Retro Games");
            if (start != -1) spannable.setSpan(new StyleSpan(Typeface.BOLD), start, start + "Doug Retro Games".length(), 0);

            progressDialog.setMessage(spannable);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
            progressDialog.setMax(totalMB);
            progressDialog.show();
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            int cpuCount = Runtime.getRuntime().availableProcessors();

            // TV boxes fracas tÃªm 4 nÃºcleos lentos â€” 1 thread evita contenÃ§Ã£o no I/O da eMMC.
            // Celulares mÃ©dios/top com 6+ nÃºcleos aproveitam bem 2 threads paralelas.
            final int threadCount = (cpuCount >= 6) ? 2 : 1;
            final int bufferSize  = (cpuCount >= 6) ? (1024 * 1024) : (512 * 1024);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // Copia ROOT_FOLDERS para ROOT_DIR
            for (String f : ROOT_FOLDERS) {
                executor.submit(() -> {
                    try { copyAssetFolder(f, new File(ROOT_DIR, f), bufferSize); } catch (IOException ignored) {}
                });
            }

            // Copia MEDIA_FOLDERS para MEDIA_DIR
            for (String f : MEDIA_FOLDERS) {
                executor.submit(() -> {
                    try { copyAssetFolder(f, new File(MEDIA_DIR, f), bufferSize); } catch (IOException ignored) {}
                });
            }

            // Copia cores (cores32/cores64) para ROOT_DIR/cores
            executor.submit(() -> {
                try { copyAssetFolder(archCores, new File(ROOT_DIR, "cores"), bufferSize); } catch (IOException ignored) {}
            });

            // Copia autoconfig (legacy ou atual) para MEDIA_DIR/autoconfig
            executor.submit(() -> {
                try { copyAssetFolder(archAutoconfig, new File(MEDIA_DIR, "autoconfig"), bufferSize); } catch (IOException ignored) {}
            });

            executor.shutdown();
            try {
                // Timeout de 20 minutos; se estourar, cancela e retorna falha
                if (!executor.awaitTermination(20, TimeUnit.MINUTES)) {
                    executor.shutdownNow();
                    return false;
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                return false;
            }

            createNomediaFiles();

            try {
                updateRetroarchCfg();
            } catch (IOException e) {
                return false;
            }
            return true;
        }

        private void createNomediaFiles() {
            String[] folders = {
                    "overlays/gamepads/720-med/img", "overlays/gamepads/Piixel-Gamepads/Piixel Retropad/img",
                    "overlays/gamepads/arcade/img", "overlays/gamepads/arcade-anim/img", "overlays/gamepads/arcade-minimal/img",
                    "overlays/gamepads/cdi_anim_portrait/img", "overlays/gamepads/dual-shock/img", "overlays/gamepads/example",
                    "overlays/gamepads/flat/img", "overlays/gamepads/flat/old", "overlays/gamepads/flat/src",
                    "overlays/gamepads/flip_phone/img", "overlays/gamepads/gameboy/img", "overlays/gamepads/gb_anim_portrait/img",
                    "overlays/gamepads/gba/img", "overlays/gamepads/gba-anim_landscape/img", "overlays/gamepads/gba-grey/img",
                    "overlays/gamepads/gba_landscape_6x/img", "overlays/gamepads/genesis/img", "overlays/gamepads/lite/img",
                    "overlays/gamepads/n64/img", "overlays/gamepads/n64/old", "overlays/gamepads/neo-ds-portrait/img/clear",
                    "overlays/gamepads/neo-retropad/img/clear", "overlays/gamepads/neo-retropad/img/default",
                    "overlays/gamepads/neo-retropad/src/clear", "overlays/gamepads/neo-retropad/src/default",
                    "overlays/gamepads/neo-retropad/src/template", "overlays/gamepads/nes/img", "overlays/gamepads/nes-small/img",
                    "overlays/gamepads/old/Low-resolution", "overlays/gamepads/old", "overlays/gamepads/psx/img",
                    "overlays/gamepads/quadpad/img", "overlays/gamepads/retropad/img", "overlays/gamepads/rgpad/modern",
                    "overlays/gamepads/rgpad/retro", "overlays/gamepads/scummvm/img", "overlays/gamepads/snes/img"
            };
            for (String path : folders) {
                File folder = new File(MEDIA_DIR, path);
                if (folder.exists()) {
                    try { new File(folder, ".nomedia").createNewFile(); } catch (IOException ignored) {}
                }
            }
        }

        /**
         * Copia uma pasta de assets recursivamente.
         *
         * Detecta se cada entrada Ã© arquivo ou diretÃ³rio tentando abrir como stream:
         * - Sucesso â†’ Ã© arquivo, copia o conteÃºdo.
         * - IOException â†’ Ã© diretÃ³rio, recursa.
         * Isso elimina a chamada dupla a getAssets().list() que era feita antes
         * para cada item, reduzindo o nÃºmero de operaÃ§Ãµes I/O no APK â€” especialmente
         * relevante em TV boxes com armazenamento lento.
         */
        private void copyAssetFolder(String assetFolder, File targetFolder, int bufferSize) throws IOException {
            String[] assets = getAssets().list(assetFolder);
            if (assets == null || assets.length == 0) return;
            if (!targetFolder.exists()) targetFolder.mkdirs();

            for (String asset : assets) {
                String fullPath = assetFolder + "/" + asset;
                File outFile = new File(targetFolder, asset);

                if (fullPath.equals("config/global.glslp") && !isArm64()) continue;

                try (InputStream in = getAssets().open(fullPath)) {
                    // Conseguiu abrir como stream: Ã© um arquivo â€” copia o conteÃºdo
                    try (FileOutputStream out = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[bufferSize];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            long total = totalExtractedBytes.addAndGet(read);
                            long currentMB = total / (1024 * 1024);
                            // Atualiza a UI apenas quando o MB muda;
                            // getAndSet garante que sÃ³ uma thread publica por MB
                            if (lastPublishedMB.getAndSet(currentMB) != currentMB) {
                                publishProgress(currentMB);
                            }
                        }
                    }
                } catch (IOException e) {
                    // Falhou ao abrir como stream: Ã© um diretÃ³rio â€” recursÃ£o
                    copyAssetFolder(fullPath, outFile, bufferSize);
                }
            }
        }

        private boolean isArm64() {
            return "cores64".equals(archCores);
        }

        @Override
        protected void onProgressUpdate(Long... v) {
            progressDialog.setProgress(v[0].intValue());
        }

        @Override
        protected void onPostExecute(Boolean r) {
            if (progressDialog.isShowing()) progressDialog.dismiss();
            prefs.edit().putBoolean("firstRun", false).apply();

            ProgressDialog closingDialog = new ProgressDialog(MainMenuActivity.this);
            closingDialog.setTitle("Encerrando aplicativo...");
            closingDialog.setMessage("\nProssiga com a instalaÃ§Ã£o do Retro Game Box");
            closingDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            closingDialog.setCancelable(false);
            closingDialog.setMax(5);
            closingDialog.setProgress(0);
            closingDialog.show();

            final int[] secondsElapsed = {0};
            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    secondsElapsed[0]++;
                    closingDialog.setProgress(secondsElapsed[0]);
                    if (secondsElapsed[0] < 5) {
                        new Handler().postDelayed(this, 1000);
                    } else {
                        closingDialog.dismiss();
                        finish();
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }
                }
            });
        }

        private void updateRetroarchCfg() throws IOException {
            File originalCfg = new File(CONFIG_DIR, "retroarch.cfg");
            if (originalCfg.exists()) originalCfg.delete();
            originalCfg.getParentFile().mkdirs();

            Map<String, String> cfgFlags = new HashMap<>();
            for (Map.Entry<String, String> e : ROOT_FLAGS.entrySet())
                cfgFlags.put(e.getValue(), new File(ROOT_DIR, e.getKey()).getAbsolutePath());
            for (Map.Entry<String, String> e : MEDIA_FLAGS.entrySet())
                cfgFlags.put(e.getValue(), new File(MEDIA_DIR, e.getKey()).getAbsolutePath());

            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

            String uniqueSuffix;
            if (androidId != null && androidId.length() >= 6) {
                uniqueSuffix = androidId.substring(androidId.length() - 6).toUpperCase();
            } else {
                uniqueSuffix = String.format("%06d", new java.util.Random().nextInt(1000000));
            }

            cfgFlags.put("menu_driver", "ozone");
            cfgFlags.put("menu_scale_factor", "0.600000");
            cfgFlags.put("ozone_menu_color_theme", "10");
            cfgFlags.put("input_overlay_opacity", "0.700000");
            cfgFlags.put("input_overlay_hide_when_gamepad_connected", "true");
            cfgFlags.put("video_shader_enable", "true");
            cfgFlags.put("video_shader_preset_save_reference_enable", "true");
            cfgFlags.put("video_shader_remember_last_dir", "true");
            cfgFlags.put("video_smooth", "false");
            cfgFlags.put("aspect_ratio_index", selectedAspectRatioIndex);
            cfgFlags.put("netplay_nickname", "RetroGameBox-" + uniqueSuffix);
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
            cfgFlags.put("video_driver",
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && "cores64".equals(archCores)) ? "vulkan" : "gl");
            cfgFlags.put("bundle_assets_extract_enable", "false");
            cfgFlags.put("bundle_assets_extract_last_version", "1756737486");
            cfgFlags.put("bundle_assets_extract_version_current", "1756737486");

            boolean hasTouchscreen = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
            boolean isLeanback = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
            boolean hasTelephony = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);

            UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
            boolean isTvMode = uiModeManager != null
                    && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

            boolean hasBattery = true;
            try {
                Intent batteryInfo = registerReceiver(null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (batteryInfo != null) {
                    hasBattery = batteryInfo.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
                }
            } catch (Exception ignored) {}

            String hardware = (Build.HARDWARE != null) ? Build.HARDWARE.toLowerCase() : "";
            String board    = (Build.BOARD != null) ? Build.BOARD.toLowerCase() : "";
            boolean isTvBoxSoC = hardware.contains("amlogic")
                    || hardware.contains("rk3") || hardware.contains("rockchip")
                    || hardware.contains("sunxi") || hardware.contains("allwinner")
                    || board.contains("amlogic")
                    || board.contains("rk3") || board.contains("rockchip")
                    || board.contains("sunxi") || board.contains("allwinner");

            int tvScore = 0;
            if (!hasBattery)   tvScore += 3;
            if (isTvMode)      tvScore += 2;
            if (isTvBoxSoC)    tvScore += 2;
            if (!hasTelephony) tvScore += 1;
            if (isLeanback)    tvScore += 1;

            boolean isTvDevice = tvScore >= 3;

            if (hasTouchscreen && !isTvDevice) {
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
                for (Map.Entry<String, String> e : cfgFlags.entrySet()) {
                    out.write((e.getKey() + " = \"" + e.getValue() + "\"\n").getBytes());
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
        retro.putExtra("EXTERNAL", Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/" + PACKAGE_NAME + "/files");
    }
}
                 
