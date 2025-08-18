package com.retroarch.browser.mainmenu;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;

import com.retroarch.browser.debug.CoreSideloadActivity;
import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import java.io.File;
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
        checkRuntimePermissions();
    }

    private void showMessageOKCancel(String message, DialogInterface.OnClickListener onClickListener) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("Conceder", onClickListener)
                .setCancelable(false)
                .setNegativeButton("Cancelar", (dialog, which) -> finishAffinity())
                .create().show();
    }

    private boolean addPermission(List<String> permissionsList, String permission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(permission);
                if (!shouldShowRequestPermissionRationale(permission))
                    return false;
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
                permissionsNeeded.add("Escrita do armazenamento");

            if (permissionsList.size() > 0) {
                checkPermissions = true;
                if (permissionsNeeded.size() > 0) {
                    String message = "O aplicativo precisa de acesso a: " + String.join(", ", permissionsNeeded);
                    showMessageOKCancel(message, (dialog, which) -> {
                        if (which == AlertDialog.BUTTON_POSITIVE) {
                            requestPermissions(permissionsList.toArray(new String[0]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                        } else {
                            finishAffinity();
                        }
                    });
                } else {
                    requestPermissions(permissionsList.toArray(new String[0]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                }
            }
        }

        if (!checkPermissions) {
            startCoreSideload();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                startCoreSideload();
            } else {
                showMessageOKCancel("O aplicativo precisa conceder as permissões para funcionar corretamente.",
                        (dialog, which) -> {
                            if (which == AlertDialog.BUTTON_POSITIVE) {
                                checkRuntimePermissions();
                            } else {
                                finishAffinity();
                            }
                        });
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void startCoreSideload() {
        Intent intent = new Intent(this, CoreSideloadActivity.class);
        startActivity(intent);
        finish();
    }

    public static void startRetroActivity(Intent retro, String contentPath, String corePath,
                                         String configFilePath, String imePath, String dataDirPath, String dataSourcePath) {
        if (contentPath != null) {
            retro.putExtra("ROM", contentPath);
        }
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
