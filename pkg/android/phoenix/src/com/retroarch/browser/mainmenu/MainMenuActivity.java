package com.retroarch.browser.mainmenu;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;

import com.retroarch.browser.debug.CoreSideloadActivity;
import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import android.content.pm.PackageManager;
import android.Manifest;

import java.util.ArrayList;
import java.util.List;

public final class MainMenuActivity extends PreferenceActivity {

    final private int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    public static String PACKAGE_NAME;
    private boolean checkPermissions = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        UserPreferences.updateConfigFile(this);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean assetsExtracted = prefs.getBoolean("assets_extracted", false);

        if (!assetsExtracted) {
            checkRuntimePermissions();
        } else {
            finalStartup(); // pula extração
        }
    }

    private boolean addPermission(List<String> permissionsList, String permission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(permission);
            }
        }
        return true;
    }

    public void checkRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissionsNeeded = new ArrayList<>();
            final List<String> permissionsList = new ArrayList<>();

            if (!addPermission(permissionsList, Manifest.permission.READ_EXTERNAL_STORAGE))
                permissionsNeeded.add("Leitura do armazenamento");
            if (!addPermission(permissionsList, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                permissionsNeeded.add("Gravação no armazenamento");

            if (permissionsList.size() > 0) {
                checkPermissions = true;
                showMessagePermissionsRequired(permissionsList);
            }
        }

        if (!checkPermissions) startExtraction();
    }

    private void showMessagePermissionsRequired(List<String> permissionsList) {
        String message = "O aplicativo precisa de acesso a: ";
        for (int i = 0; i < permissionsList.size(); i++) {
            message += permissionsList.get(i).replace("android.permission.", "");
            if (i < permissionsList.size() - 1) message += ", ";
        }
        message += ".";

        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("Conceder", (dialog, which) ->
                        requestPermissions(permissionsList.toArray(new String[0]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS))
                .setNegativeButton("Cancelar", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startExtraction();
            } else {
                // sempre exibe alerta quando negar
                new AlertDialog.Builder(this)
                        .setMessage("O aplicativo precisa conceder permissões para funcionar corretamente.")
                        .setPositiveButton("Conceder", (d, w) -> checkRuntimePermissions())
                        .setNegativeButton("Cancelar", (d, w) -> finish())
                        .setCancelable(false)
                        .show();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void startExtraction() {
        Intent intent = new Intent(this, CoreSideloadActivity.class);
        startActivity(intent);
        finish();
    }

    public void finalStartup() {
        Intent retro = new Intent(this, RetroActivityFuture.class);
        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        MainMenuActivity.startRetroActivity(
                retro,
                null,
                getApplicationInfo().dataDir + "/cores/",
                UserPreferences.getDefaultConfigPath(this),
                Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
                getApplicationInfo().dataDir,
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
        retro.putExtra("SDCARD", android.os.Environment.getExternalStorageDirectory().getAbsolutePath());
        String external = android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/media/" + PACKAGE_NAME + "/files";
        retro.putExtra("EXTERNAL", external);
    }
}
