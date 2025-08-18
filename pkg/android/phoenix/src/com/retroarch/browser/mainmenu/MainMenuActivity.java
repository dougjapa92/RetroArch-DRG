package com.retroarch.browser.mainmenu;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import com.retroarch.browser.retroactivity.RetroActivityFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class MainMenuActivity extends Activity {

    private static final int REQUEST_CODE_PERMISSIONS = 124;
    private static final String CUSTOM_BASE_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/RetroArch-DRG";
    public static String PACKAGE_NAME;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PACKAGE_NAME = getPackageName();

        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        checkPermissionsAndStart();
    }

    private void checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean read = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            boolean write = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

            if (!read || !write) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_CODE_PERMISSIONS);
                return;
            }
        }
        setupRetroArch();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    showMessageOKCancel("Permissão necessária para continuar: " + permissions[i],
                            (dialog, which) -> checkPermissionsAndStart());
                    return;
                }
            }
        }
        setupRetroArch();
    }

    private void showMessageOKCancel(String message, DialogInterface.OnClickListener onClickListener) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("OK", onClickListener)
                .setCancelable(false)
                .setNegativeButton("Cancel", null)
                .create()
                .show();
    }

    private void setupRetroArch() {
        // Extrai assets do APK para /RetroArch-DRG sobrescrevendo tudo
        extractAllAssets();

        // Gera retroarch.cfg no DRG com paths corretos
        generateRetroArchConfig();

        // Inicia RetroActivityFuture com paths do DRG
        Intent retro = new Intent(this, RetroActivityFuture.class);
        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        startRetroActivity(retro,
                null,
                CUSTOM_BASE_DIR + "/cores/",
                CUSTOM_BASE_DIR + "/retroarch.cfg",
                Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
                CUSTOM_BASE_DIR,
                getApplicationInfo().sourceDir);

        startActivity(retro);
        finish();
    }

    private void extractAllAssets() {
        try {
            String[] assets = getAssets().list("");
            if (assets != null) {
                for (String asset : assets) {
                    copyAssetOrFolder(asset, new File(CUSTOM_BASE_DIR, asset));
                }
            }
        } catch (IOException e) {
            Log.e("MainMenuActivity", "Erro ao listar assets", e);
        }
    }

    private void copyAssetOrFolder(String assetPath, File outFile) throws IOException {
        String[] list = getAssets().list(assetPath);
        if (list == null || list.length == 0) {
            copyAssetFile(assetPath, outFile); // arquivo → sobrescreve
        } else {
            if (!outFile.exists()) outFile.mkdirs();
            for (String child : list) {
                String childAssetPath = assetPath + "/" + child;
                copyAssetOrFolder(childAssetPath, new File(outFile, child));
            }
        }
    }

    private void copyAssetFile(String assetPath, File outFile) throws IOException {
        if (!outFile.getParentFile().exists()) outFile.getParentFile().mkdirs();
        InputStream in = getAssets().open(assetPath);
        OutputStream out = new FileOutputStream(outFile, false); // sobrescreve
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        out.flush();
        out.close();
    }

    private void generateRetroArchConfig() {
        File cfg = new File(CUSTOM_BASE_DIR + "/retroarch.cfg");
        try {
            if (!cfg.getParentFile().exists()) cfg.getParentFile().mkdirs();
            FileOutputStream out = new FileOutputStream(cfg, false); // sobrescreve
            String content = ""
                    + "system_directory = \"" + CUSTOM_BASE_DIR + "/system\"\n"
                    + "core_directory = \"" + CUSTOM_BASE_DIR + "/cores\"\n"
                    + "assets_directory = \"" + CUSTOM_BASE_DIR + "/assets\"\n";
            // NOTA: save e states ficam no /RetroArch → não colocamos paths aqui
            out.write(content.getBytes());
            out.flush();
            out.close();
        } catch (IOException e) {
            Log.e("MainMenuActivity", "Erro ao criar retroarch.cfg", e);
        }
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
