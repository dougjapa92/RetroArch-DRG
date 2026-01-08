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
            try { process64 = android.os.Process.is64Bit(); } catch (Throwable ignored) {}
        }
        boolean os64 = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String[] abis64 = Build.SUPPORTED_64_BIT_ABIS;
            os64 = (abis64 != null && abis64.length > 0);
        }
        this.archCores = (process64 || os64) ? "cores64" : "cores32";
        this.archAutoconfig = (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) ? "autoconfig-legacy" : "autoconfig";
    }

    private void checkRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                return;
            }
        }
        startExtractionOrRetro();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        startExtractionOrRetro();
    }

    private void startExtractionOrRetro() {
        if (prefs.getBoolean("firstRun", true)) showAspectRatioDialog();
        else finalStartup();
    }

    private void showAspectRatioDialog() {
        new AlertDialog.Builder(this).setTitle("Configuração Inicial").setMessage("Escolha a proporção de tela:")
                .setPositiveButton("TELA CHEIA (16:9)", (d, w) -> { selectedAspectRatioIndex = "1"; new UnifiedExtractionTask().execute(); })
                .setNegativeButton("ORIGINAL (4:3)", (d, w) -> { selectedAspectRatioIndex = "20"; new UnifiedExtractionTask().execute(); })
                .setCancelable(false).show();
    }

    private class UnifiedExtractionTask extends AsyncTask<Void, Long, Boolean> {
        ProgressDialog progressDialog;
        AtomicLong totalExtractedBytes = new AtomicLong(0);
        long lastPublishedMB = -1;
        int totalMB = 0;

        @Override
        protected void onPreExecute() {
            // Valores Hardcoded para performance máxima
            totalMB = archCores.equals("cores64") ? 547 : 436;

            progressDialog = new ProgressDialog(MainMenuActivity.this);
            progressDialog.setTitle("RetroArch DRG");
            progressDialog.setMessage("Extraindo recursos (" + totalMB + " MB)...");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
            progressDialog.setMax(totalMB);
            progressDialog.show();
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            // Inteligência de Threads baseada no hardware
            int cpuCount = Runtime.getRuntime().availableProcessors();
            int threadCount = (cpuCount > 4) ? 6 : 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (String f : ROOT_FOLDERS) executor.submit(() -> { try { copyAssetFolder(f, new File(ROOT_DIR, f)); } catch (Exception e) {} });
            for (String f : MEDIA_FOLDERS) executor.submit(() -> { try { copyAssetFolder(f, new File(MEDIA_DIR, f)); } catch (Exception e) {} });
            executor.submit(() -> { try { copyAssetFolder(archCores, new File(ROOT_DIR, "cores")); } catch (Exception e) {} });
            executor.submit(() -> { try { copyAssetFolder(archAutoconfig, new File(MEDIA_DIR, "autoconfig")); } catch (Exception e) {} });

            executor.shutdown();
            try { executor.awaitTermination(30, TimeUnit.MINUTES); } catch (InterruptedException e) {}

            createNomediaFiles();
            try { updateRetroarchCfg(); } catch (IOException e) {}
            return true;
        }

        private void copyAssetFolder(String assetFolder, File targetFolder) throws IOException {
            String[] assets = getAssets().list(assetFolder);
            if (!targetFolder.exists()) targetFolder.mkdirs();
            if (assets == null) return;

            for (String asset : assets) {
                String fullPath = assetFolder + "/" + asset;
                File outFile = new File(targetFolder, asset);
                if (fullPath.equals("config/global.glslp") && !archCores.equals("cores64")) continue;

                try (InputStream in = getAssets().open(fullPath)) {
                    if (getAssets().list(fullPath).length == 0) {
                        try (FileOutputStream out = new FileOutputStream(outFile)) {
                            byte[] buffer = new byte[1024 * 1024]; // 1MB Buffer
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                                long total = totalExtractedBytes.addAndGet(read);
                                long currentMB = total / (1024 * 1024);
                                if (currentMB != lastPublishedMB) {
                                    lastPublishedMB = currentMB;
                                    publishProgress(currentMB);
                                }
                            }
                        }
                    } else { copyAssetFolder(fullPath, outFile); }
                } catch (IOException e) {}
            }
        }

        @Override
        protected void onProgressUpdate(Long... values) {
            progressDialog.setProgress(values[0].intValue());
        }

        @Override
        protected void onPostExecute(Boolean r) {
            if (progressDialog.isShowing()) progressDialog.dismiss();
            prefs.edit().putBoolean("firstRun", false).apply();
            finalStartup();
        }

        private void createNomediaFiles() {
            String[] folders = {"overlays/gamepads/720-med/img", "overlays/gamepads/Piixel-Gamepads/Piixel Retropad/img", "overlays/gamepads/arcade/img", "overlays/gamepads/arcade-anim/img", "overlays/gamepads/arcade-minimal/img", "overlays/gamepads/cdi_anim_portrait/img", "overlays/gamepads/dual-shock/img", "overlays/gamepads/example", "overlays/gamepads/flat/img", "overlays/gamepads/flat/old", "overlays/gamepads/flat/src", "overlays/gamepads/flip_phone/img", "overlays/gamepads/gameboy/img", "overlays/gamepads/gb_anim_portrait/img", "overlays/gamepads/gba/img", "overlays/gamepads/gba-anim_landscape/img", "overlays/gamepads/gba-grey/img", "overlays/gamepads/gba_landscape_6x/img", "overlays/gamepads/genesis/img", "overlays/gamepads/lite/img", "overlays/gamepads/n64/img", "overlays/gamepads/n64/old", "overlays/gamepads/neo-ds-portrait/img/clear", "overlays/gamepads/neo-retropad/img/clear", "overlays/gamepads/neo-retropad/img/default", "overlays/gamepads/neo-retropad/src/clear", "overlays/gamepads/neo-retropad/src/default", "overlays/gamepads/neo-retropad/src/template", "overlays/gamepads/nes/img", "overlays/gamepads/nes-small/img", "overlays/gamepads/old/Low-resolution", "overlays/gamepads/old", "overlays/gamepads/psx/img", "overlays/gamepads/quadpad/img", "overlays/gamepads/retropad/img", "overlays/gamepads/rgpad/modern", "overlays/gamepads/rgpad/retro", "overlays/gamepads/scummvm/img", "overlays/gamepads/snes/img"};
            for (String path : folders) {
                File folder = new File(MEDIA_DIR, path);
                if (folder.exists()) try { new File(folder, ".nomedia").createNewFile(); } catch (Exception e) {}
            }
        }

        private void updateRetroarchCfg() throws IOException {
            File originalCfg = new File(CONFIG_DIR, "retroarch.cfg");
            if (originalCfg.exists()) originalCfg.delete();
            originalCfg.getParentFile().mkdirs();
            Map<String, String> cfgFlags = new HashMap<>();
            for (Map.Entry<String, String> e : ROOT_FLAGS.entrySet()) cfgFlags.put(e.getValue(), new File(ROOT_DIR, e.getKey()).getAbsolutePath());
            for (Map.Entry<String, String> e : MEDIA_FLAGS.entrySet()) cfgFlags.put(e.getValue(), new File(MEDIA_DIR, e.getKey()).getAbsolutePath());
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            String uniqueSuffix = (androidId != null && androidId.length() >= 6) ? androidId.substring(androidId.length() - 6).toUpperCase() : "123456";
            cfgFlags.put("menu_driver", "ozone");
            cfgFlags.put("aspect_ratio_index", selectedAspectRatioIndex);
            cfgFlags.put("netplay_nickname", "RetroGameBox-" + uniqueSuffix);
            cfgFlags.put("video_threaded", "cores32".equals(archCores) ? "true" : "false");
            cfgFlags.put("bundle_assets_extract_enable", "false");
            cfgFlags.put("input_overlay", new File(MEDIA_DIR, "overlays/gamepads/neo-retropad/neo-retropad.cfg").getAbsolutePath());
            boolean isTV = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
            cfgFlags.put("input_overlay_enable", isTV ? "false" : "true");
            try (FileOutputStream out = new FileOutputStream(originalCfg, false)) {
                for (Map.Entry<String, String> e : cfgFlags.entrySet()) out.write((e.getKey() + " = \"" + e.getValue() + "\"\n").getBytes());
            }
        }
    }

    public void finalStartup() {
        Intent retro = new Intent(this, RetroActivityFuture.class);
        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        retro.putExtra("LIBRETRO", new File(ROOT_DIR, "cores").getAbsolutePath());
        retro.putExtra("CONFIGFILE", new File(CONFIG_DIR, "retroarch.cfg").getAbsolutePath());
        retro.putExtra("DATADIR", ROOT_DIR.getAbsolutePath());
        retro.putExtra("APK", getApplicationInfo().sourceDir);
        retro.putExtra("SDCARD", Environment.getExternalStorageDirectory().getAbsolutePath());
        retro.putExtra("EXTERNAL", Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/" + PACKAGE_NAME + "/files");
        startActivity(retro);
        finish();
    }
}
 
