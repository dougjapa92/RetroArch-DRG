package com.retroarch.browser.mainmenu;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;

import com.retroarch.browser.retroactivity.RetroActivityFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainMenuActivity extends Activity {
    private static final String CUSTOM_BASE_DIR =
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/RetroArch-DRG";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finalStartup();
    }

    private void extractAllAssets() {
        copyAssetFolder("", new File(CUSTOM_BASE_DIR));
    }

    private boolean copyAssetFolder(String assetPath, File outDir) {
        try {
            String[] assets = getAssets().list(assetPath);
            if (assets == null || assets.length == 0) {
                copyAsset(assetPath, outDir);
                return true;
            } else {
                if (!outDir.exists()) outDir.mkdirs();
                for (String asset : assets) {
                    String newAssetPath = assetPath.isEmpty() ? asset : assetPath + "/" + asset;
                    File newOutFile = new File(outDir, asset);

                    if (isUserDataFolder(outDir.getAbsolutePath())) {
                        // Pasta do usuário → não sobrescreve arquivos existentes
                        copyAssetPreserveUserData(newAssetPath, newOutFile);
                    } else {
                        copyAssetFolder(newAssetPath, newOutFile);
                    }
                }
                return true;
            }
        } catch (IOException e) {
            Log.e("MainMenuActivity", "Erro ao copiar assets: " + assetPath, e);
            return false;
        }
    }

    private boolean isUserDataFolder(String path) {
        String lower = path.toLowerCase();
        return lower.contains("/save") || lower.contains("/states") || lower.contains("/savestates");
    }

    private void copyAssetPreserveUserData(String assetPath, File outFile) throws IOException {
        if (outFile.exists()) return; // Não sobrescreve arquivos do usuário
        if (!outFile.getParentFile().exists()) outFile.getParentFile().mkdirs();

        InputStream in = getAssets().open(assetPath);
        OutputStream out = new FileOutputStream(outFile);
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        out.flush();
        out.close();
    }

    private void copyAsset(String assetPath, File outFile) throws IOException {
        if (!outFile.getParentFile().exists()) outFile.getParentFile().mkdirs();

        InputStream in = getAssets().open(assetPath);
        OutputStream out = new FileOutputStream(outFile);
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        out.flush();
        out.close();
    }

    public void finalStartup() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean firstRun = prefs.getBoolean("first_run_extraction_done", false);

        if (!firstRun) {
            extractAllAssets();
            prefs.edit().putBoolean("first_run_extraction_done", true).apply();
        }

        Intent retro = new Intent(this, RetroActivityFuture.class);
        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        String configPath = CUSTOM_BASE_DIR + "/retroarch.cfg";

        startRetroActivity(
                retro,
                null,
                CUSTOM_BASE_DIR + "/cores/",
                configPath,
                Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
                CUSTOM_BASE_DIR,
                getApplicationInfo().sourceDir);

        startActivity(retro);
        finish();
    }

    public void startRetroActivity(Intent retro, String rom, String corePath,
                                   String configPath, String ime,
                                   String externalFilesDir, String apkPath) {
        retro.putExtra("ROM", rom);
        retro.putExtra("LIBRETRO", corePath);
        retro.putExtra("CONFIGFILE", configPath);
        retro.putExtra("IME", ime);
        retro.putExtra("EXTERNAL_FILES_DIR", externalFilesDir);
        retro.putExtra("APK_PATH", apkPath);
    }
}
