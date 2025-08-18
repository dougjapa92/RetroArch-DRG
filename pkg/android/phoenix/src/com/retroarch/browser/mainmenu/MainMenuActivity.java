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
import com.retroarch.browser.preferences.UserPreferences;

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
                // Arquivo
                copyAsset(assetPath, outDir);
                return true;
            } else {
                // Pasta
                if (!outDir.exists()) outDir.mkdirs();
                for (String asset : assets) {
                    String newAssetPath = assetPath.isEmpty() ? asset : assetPath + "/" + asset;
                    File newOutFile = new File(outDir, asset);
                    copyAssetFolder(newAssetPath, newOutFile);
                }
                return true;
            }
        } catch (IOException e) {
            Log.e("MainMenuActivity", "Erro ao copiar assets: " + assetPath, e);
            return false;
        }
    }

    private void copyAsset(String assetPath, File outFile) throws IOException {
        if (!outFile.getParentFile().exists()) {
            outFile.getParentFile().mkdirs();
        }
        // Se não quiser sobrescrever, pode adicionar: if (outFile.exists()) return;
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
        // Copia TODO o conteúdo dos assets recursivamente
        extractAllAssets();

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        Intent retro = new Intent(this, RetroActivityFuture.class);

        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        startRetroActivity(
                retro,
                null,
                CUSTOM_BASE_DIR + "/cores/",
                UserPreferences.getDefaultConfigPath(this),
                Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
                CUSTOM_BASE_DIR,
                getApplicationInfo().sourceDir);

        startActivity(retro);
        finish();
    }

    private void startRetroActivity(Intent retro, String rom, String corePath,
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
