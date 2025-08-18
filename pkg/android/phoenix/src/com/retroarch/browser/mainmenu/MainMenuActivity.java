package com.retroarch.browser.mainmenu;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Build;
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
import java.util.ArrayList;
import java.util.List;

public final class MainMenuActivity extends PreferenceActivity {
    private final int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    public static String PACKAGE_NAME;
    private boolean checkPermissions = false;

    private static final String TARGET_DIR =
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/media/com.retroarch";

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
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

            if (!addPermission(permissionsList, android.Manifest.permission.READ_EXTERNAL_STORAGE))
                permissionsNeeded.add("Read External Storage");
            if (!addPermission(permissionsList, android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
                permissionsNeeded.add("Write External Storage");

            if (permissionsList.size() > 0) {
                checkPermissions = true;

                if (!permissionsNeeded.isEmpty()) {
                    String message = "Você precisa conceder acesso a " + permissionsNeeded.get(0);
                    for (int i = 1; i < permissionsNeeded.size(); i++)
                        message += ", " + permissionsNeeded.get(i);

                    showMessageOKCancel(message, (dialog, which) -> {
                        if (which == AlertDialog.BUTTON_POSITIVE) {
                            requestPermissions(permissionsList.toArray(new String[0]),
                                    REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                        }
                    });
                } else {
                    requestPermissions(permissionsList.toArray(new String[0]),
                            REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                }
            }
        }

        if (!checkPermissions) {
            new ExtractAssetsTask().execute();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS:
                boolean allGranted = true;
                for (int i = 0; i < permissions.length; i++) {
                    if (grantResults[i] != PackageManager.PERMISSION_GRANTED)
                        allGranted = false;
                }
                if (!allGranted) {
                    showMessageOKCancel(
                            "Para usar o RetroArch DRG, habilite as permissões de armazenamento nas configurações ou reinstale o app.",
                            (dialog, which) -> finish()
                    );
                    return;
                }
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
        new ExtractAssetsTask().execute();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        checkRuntimePermissions();
    }

    public static void startRetroActivity(Intent retro, String contentPath, String corePath,
                                          String configFilePath, String imePath,
                                          String dataDirPath, String dataSourcePath) {
        if (contentPath != null)
            retro.putExtra("ROM", contentPath);
        retro.putExtra("LIBRETRO", corePath);
        retro.putExtra("CONFIGFILE", configFilePath);
        retro.putExtra("IME", imePath);
        retro.putExtra("DATADIR", dataDirPath);
        retro.putExtra("APK", dataSourcePath);
        retro.putExtra("SDCARD", Environment.getExternalStorageDirectory().getAbsolutePath());
        String external = Environment.getExternalStorageDirectory().getAbsolutePath() +
                "/Android/data/" + PACKAGE_NAME + "/files";
        retro.putExtra("EXTERNAL", external);
    }

    private class ExtractAssetsTask extends AsyncTask<Void, Integer, Void> {
        ProgressDialog progressDialog;
        List<String> assetFiles;

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(MainMenuActivity.this);
            progressDialog.setMessage("Configurando RetroArch DRG...\n\nO aplicativo encerrará após configuração inicial.");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
            progressDialog.show();
        }

        @Override
        protected Void doInBackground(Void... voids) {
            try {
                assetFiles = listAllAssets("");
                int total = assetFiles.size();
                int count = 0;

                for (String asset : assetFiles) {
                    File outFile = new File(TARGET_DIR, asset);
                    copyAsset(asset, outFile);
                    count++;
                    publishProgress(count * 100 / total);
                }

                // Gera retroarch.cfg padrão se ainda não existir
                File cfgFile = new File(Environment.getExternalStorageDirectory()
                        + "/Android/data/" + PACKAGE_NAME + "/files/retroarch.cfg");
                if (!cfgFile.exists()) {
                    UserPreferences.updateConfigFile(MainMenuActivity.this);
                }
            } catch (IOException e) {
                Log.e("MainMenuActivity", "Erro ao extrair assets", e);
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            progressDialog.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(Void unused) {
            progressDialog.dismiss();
            finish();
        }

        private List<String> listAllAssets(String path) throws IOException {
            List<String> fileList = new ArrayList<>();
            String[] assets = getAssets().list(path);
            if (assets == null || assets.length == 0) {
                fileList.add(path);
                return fileList;
            }
            for (String asset : assets) {
                String subPath = path.isEmpty() ? asset : path + "/" + asset;
                fileList.addAll(listAllAssets(subPath));
            }
            return fileList;
        }

        private void copyAsset(String assetPath, File outFile) throws IOException {
            if (!outFile.getParentFile().exists())
                outFile.getParentFile().mkdirs();
            if (outFile.exists()) return;
            InputStream in = getAssets().open(assetPath);
            OutputStream out = new FileOutputStream(outFile);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1)
                out.write(buffer, 0, read);
            in.close();
            out.flush();
            out.close();
        }
    }
} 
