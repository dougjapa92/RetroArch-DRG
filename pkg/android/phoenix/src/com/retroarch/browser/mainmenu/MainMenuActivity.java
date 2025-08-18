package com.retroarch.browser.mainmenu;

import android.app.Activity;
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

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.ArrayList;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.DialogInterface;
import android.app.AlertDialog;

public final class MainMenuActivity extends PreferenceActivity {
    private static final String CUSTOM_BASE_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/RetroArch";
    final private int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    public static String PACKAGE_NAME;
    boolean checkPermissions = false;
    private ProgressDialog progressDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        checkRuntimePermissions();
    }

    private void showMessageOKCancel(String message, DialogInterface.OnClickListener onClickListener) {
        new AlertDialog.Builder(this).setMessage(message)
            .setPositiveButton("OK", onClickListener)
            .setCancelable(false)
            .setNegativeButton("Cancel", null).create().show();
    }

    private boolean addPermission(List<String> permissionsList, String permission) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(permission);
                if (!shouldShowRequestPermissionRationale(permission)) return false;
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
                        message += ", " + permissionsNeeded.get(i);

                    showMessageOKCancel(message,
                        (dialog, which) -> {
                            if (which == AlertDialog.BUTTON_POSITIVE) {
                                requestPermissions(permissionsList.toArray(new String[0]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                            }
                        });
                } else {
                    requestPermissions(permissionsList.toArray(new String[0]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                }
            }
        }

        if (!checkPermissions) {
            startExtraction();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS) {
            boolean grantedAll = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) grantedAll = false;
            }

            if (!grantedAll) {
                showMessageOKCancel("Permissões não concedidas. Habilite manualmente ou reinstale o aplicativo.",
                        (dialog, which) -> finish());
                return;
            }
        }
        startExtraction();
    }

    private void startExtraction() {
        new AsyncTask<Void, Integer, Void>() {
            List<String> assetList = new ArrayList<>();
            int totalFiles = 0;

            @Override
            protected void onPreExecute() {
                progressDialog = new ProgressDialog(MainMenuActivity.this);
                progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                progressDialog.setMessage("Configurando RetroArch DRG...\n\nO aplicativo encerrará após configuração inicial.");
                progressDialog.setCancelable(false);
                progressDialog.show();

                collectAssets(""); // conta o total de arquivos
                totalFiles = assetList.size();
                progressDialog.setMax(100);
            }

            @Override
            protected Void doInBackground(Void... voids) {
                extractAssets(""); 
                return null;
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                progressDialog.setProgress(values[0]);
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                progressDialog.dismiss();
                generateRetroarchConfig(); // cria retroarch.cfg no diretório padrão
                finish();
            }

            private void collectAssets(String path) {
                try {
                    String[] list = getAssets().list(path);
                    if (list == null || list.length == 0) {
                        assetList.add(path);
                    } else {
                        for (String item : list) {
                            String newPath = path.isEmpty() ? item : path + "/" + item;
                            collectAssets(newPath);
                        }
                    }
                } catch (IOException e) {
                    Log.e("MainMenuActivity", "Erro ao contar assets: " + path, e);
                }
            }

            private void extractAssets(String path) {
                try {
                    String[] list = getAssets().list(path);
                    if (list == null || list.length == 0) {
                        copyAsset(path, new File(CUSTOM_BASE_DIR, path));
                        int progress = (assetList.indexOf(path) * 100) / totalFiles;
                        publishProgress(progress);
                    } else {
                        File dir = new File(CUSTOM_BASE_DIR, path);
                        if (!dir.exists()) dir.mkdirs();
                        for (String item : list) {
                            String newPath = path.isEmpty() ? item : path + "/" + item;
                            extractAssets(newPath);
                        }
                    }
                } catch (IOException e) {
                    Log.e("MainMenuActivity", "Erro ao extrair asset: " + path, e);
                }
            }

            private void copyAsset(String assetPath, File outFile) throws IOException {
                if (outFile.exists()) return;
                if (!outFile.getParentFile().exists()) outFile.getParentFile().mkdirs();
                try (InputStream in = getAssets().open(assetPath); OutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }.execute();
    }

    private void generateRetroarchConfig() {
        UserPreferences.updateConfigFile(this); // cria retroarch.cfg no diretório padrão
    }
} 
