package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.List;
import java.util.ArrayList;
import android.content.pm.PackageManager;
import android.Manifest;
import android.content.DialogInterface;
import android.app.AlertDialog;

public final class MainMenuActivity extends PreferenceActivity {
    final private int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    public static String PACKAGE_NAME;
    boolean checkPermissions = false;

    private static final String CUSTOM_BASE_DIR =
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/media/com.retroarch";
    private ProgressDialog progressDialog;
    private int totalFiles = 0;
    private int filesCopied = 0;

    public void showMessageOKCancel(String message, DialogInterface.OnClickListener onClickListener) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("OK", onClickListener)
                .setCancelable(false)
                .setNegativeButton("Cancel", null)
                .create()
                .show();
    }

    private boolean addPermission(List<String> permissionsList, String permission) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(permission);
                return !shouldShowRequestPermissionRationale(permission);
            }
        }
        return true;
    }

    public void checkRuntimePermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            List<String> permissionsNeeded = new ArrayList<>();
            final List<String> permissionsList = new ArrayList<>();

            if (!addPermission(permissionsList, Manifest.permission.READ_EXTERNAL_STORAGE))
                permissionsNeeded.add("Read External Storage");
            if (!addPermission(permissionsList, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                permissionsNeeded.add("Write External Storage");

            if (permissionsList.size() > 0) {
                checkPermissions = true;

                if (permissionsNeeded.size() > 0) {
                    String message = "Você precisa conceder acesso a " + permissionsNeeded.get(0);
                    for (int i = 1; i < permissionsNeeded.size(); i++)
                        message = message + ", " + permissionsNeeded.get(i);

                    showMessageOKCancel(message, (dialog, which) -> {
                        if (which == AlertDialog.BUTTON_POSITIVE) {
                            requestPermissions(
                                    permissionsList.toArray(new String[permissionsList.size()]),
                                    REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                        }
                    });
                } else {
                    requestPermissions(
                            permissionsList.toArray(new String[permissionsList.size()]),
                            REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                }
            }
        }

        if (!checkPermissions) {
            startExtractionTask();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                new AlertDialog.Builder(this)
                        .setTitle("Permissões necessárias")
                        .setMessage("O RetroArch DRG precisa de acesso ao armazenamento para funcionar corretamente.\n\n" +
                                    "Por favor, habilite as permissões nas configurações ou reinstale o aplicativo.")
                        .setCancelable(false)
                        .setPositiveButton("OK", (dialog, which) -> finishAffinity())
                        .show();
            } else {
                startExtractionTask();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private int countTotalFiles() {
        final int[] total = {0};
        String[] folders = {"assets", "autoconfig", "cores", "database",
                "filters", "info", "overlays", "shaders", "system"};
        for (String folder : folders) {
            total[0] += countFilesInAssetFolder(folder);
        }
        return total[0];
    }

    private int countFilesInAssetFolder(String assetPath) {
        try {
            String[] assets = getAssets().list(assetPath);
            if (assets == null || assets.length == 0) return 1;
            int count = 0;
            for (String asset : assets) {
                String newAssetPath = assetPath.isEmpty() ? asset : assetPath + "/" + asset;
                count += countFilesInAssetFolder(newAssetPath);
            }
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    private void startExtractionTask() {
        totalFiles = countTotalFiles();
        filesCopied = 0;

        progressDialog = new ProgressDialog(this);
        progressDialog.setTitle("Configurando RetroArch DRG");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setCancelable(false);
        progressDialog.setMax(100); // porcentagem
        progressDialog.setProgress(0);
        progressDialog.setMessage("Configurando RetroArch DRG...\n\nO aplicativo encerrará após configuração inicial.\n\n0%");
        progressDialog.show();

        new AsyncTask<Void, Integer, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                try {
                    String[] folders = {"assets", "autoconfig", "cores", "database",
                            "filters", "info", "overlays", "shaders", "system"};
                    for (String folder : folders) {
                        copyAssetFolderWithProgress(folder, new File(CUSTOM_BASE_DIR, folder));
                    }
                    generateRetroArchConfigOnce();
                } catch (Exception e) {
                    Log.e("MainMenuActivity", "Erro na extração", e);
                }
                return null;
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                int percent = values[0];
                progressDialog.setProgress(percent);
                progressDialog.setMessage("Configurando RetroArch DRG...\n\nO aplicativo encerrará após configuração inicial.\n\n"
                        + percent + "%");
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                if (progressDialog != null && progressDialog.isShowing())
                    progressDialog.dismiss();
                finishAffinity();
                System.exit(0);
            }
        }.execute();
    }

    private void copyAssetFolderWithProgress(String assetPath, File outDir) throws IOException {
        String[] assets = getAssets().list(assetPath);
        if (assets == null || assets.length == 0) {
            copyAssetWithProgress(assetPath, outDir);
        } else {
            if (!outDir.exists()) outDir.mkdirs();
            for (String asset : assets) {
                String newAssetPath = assetPath.isEmpty() ? asset : assetPath + "/" + asset;
                File newOutFile = new File(outDir, asset);
                copyAssetFolderWithProgress(newAssetPath, newOutFile);
            }
        }
    }

    private void copyAssetWithProgress(String assetPath, File outFile) throws IOException {
        if (outFile.exists()) return;
        if (!outFile.getParentFile().exists()) outFile.getParentFile().mkdirs();

        try (InputStream in = getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }

        filesCopied++;
        int percent = (int)((filesCopied / (float)totalFiles) * 100);
        publishProgress(percent);
    }

    private void generateRetroArchConfigOnce() {
        File configFile = new File(Environment.getExternalStorageDirectory().getAbsolutePath()
                + "/Android/data/" + PACKAGE_NAME + "/files/retroarch.cfg");
        if (!configFile.exists()) {
            UserPreferences.updateConfigFile(this);
        }
    }

    public static void startRetroActivity(Intent retro, String contentPath, String corePath,
                                          String configFilePath, String imePath,
                                          String dataDirPath, String dataSourcePath) {
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

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        checkRuntimePermissions();
    }
}
