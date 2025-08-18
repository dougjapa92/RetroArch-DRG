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
import android.content.pm.PackageManager;
import android.Manifest;
import android.content.DialogInterface;
import android.app.AlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.ArrayList;

public final class MainMenuActivity extends PreferenceActivity {
    private static final int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    private static final String MEDIA_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/media/com.retroarch";
    public static String PACKAGE_NAME;
    boolean checkPermissions = false;
    private ProgressDialog progressDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        UserPreferences.updateConfigFile(this);
        checkRuntimePermissions();
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
                        message += ", " + permissionsNeeded.get(i);

                    showMessageOKCancel(message, (dialog, which) -> {
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

    private void showMessageOKCancel(String message, DialogInterface.OnClickListener onClickListener) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("OK", onClickListener)
                .setCancelable(false)
                .setNegativeButton("Cancel", null)
                .create()
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS:
                boolean allGranted = true;
                for (int i = 0; i < permissions.length; i++) {
                    if (grantResults[i] != PackageManager.PERMISSION_GRANTED) allGranted = false;
                }
                if (!allGranted) {
                    new AlertDialog.Builder(this)
                            .setMessage("Permissões negadas. Por favor, habilite as permissões nas configurações ou reinstale o aplicativo.")
                            .setCancelable(false)
                            .setPositiveButton("OK", (dialog, which) -> finish())
                            .create()
                            .show();
                    return;
                }
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
                break;
        }
        startExtraction();
    }

    private void startExtraction() {
        File markerFile = new File(MEDIA_DIR, ".extracted");
        if (markerFile.exists()) {
            launchRetroActivity();
            return;
        }

        new AsyncTask<Void, Integer, Void>() {
            private int totalFiles = 0;
            private int extractedFiles = 0;

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
                    totalFiles = countAssets("");
                    extractAssets("");
                    markerFile.getParentFile().mkdirs();
                    markerFile.createNewFile();
                } catch (IOException e) {
                    Log.e("MainMenuActivity", "Erro na extração", e);
                }
                return null;
            }

            private int countAssets(String path) throws IOException {
                String[] list = getAssets().list(path);
                int count = 0;
                if (list != null) {
                    for (String s : list) {
                        String newPath = path.isEmpty() ? s : path + "/" + s;
                        if (getAssets().list(newPath).length == 0) count++;
                        else count += countAssets(newPath);
                    }
                }
                return count;
            }

            private void extractAssets(String path) throws IOException {
                String[] assets = getAssets().list(path);
                if (assets == null || assets.length == 0) {
                    copyAsset(path, new File(MEDIA_DIR, path));
                    extractedFiles++;
                    publishProgress(extractedFiles * 100 / totalFiles);
                    return;
                }
                for (String asset : assets) {
                    String newPath = path.isEmpty() ? asset : path + "/" + asset;
                    extractAssets(newPath);
                }
            }

            private void copyAsset(String assetPath, File outFile) throws IOException {
                if (!outFile.getParentFile().exists()) outFile.getParentFile().mkdirs();
                InputStream in = getAssets().open(assetPath);
                OutputStream out = new FileOutputStream(outFile);
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                in.close();
                out.flush();
                out.close();
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                progressDialog.setProgress(values[0]);
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                progressDialog.dismiss();
                updateRetroarchCfg();
                launchRetroActivity();
            }
        }.execute();
    }

    private void updateRetroarchCfg() {
        File cfgFile = new File(getFilesDir(), "retroarch.cfg");
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("system_directory = \"" + MEDIA_DIR + "/system\"\n");
            sb.append("core_assets_directory = \"" + MEDIA_DIR + "/assets\"\n");
            sb.append("autoconfig_directory = \"" + MEDIA_DIR + "/autoconfig\"\n");
            sb.append("cores_directory = \"" + MEDIA_DIR + "/cores\"\n");
            sb.append("database_directory = \"" + MEDIA_DIR + "/database\"\n");
            sb.append("filters_directory = \"" + MEDIA_DIR + "/filters\"\n");
            sb.append("info_directory = \"" + MEDIA_DIR + "/info\"\n");
            sb.append("overlays_directory = \"" + MEDIA_DIR + "/overlays\"\n");
            sb.append("shaders_directory = \"" + MEDIA_DIR + "/shaders\"\n");

            FileOutputStream fos = new FileOutputStream(cfgFile, false);
            fos.write(sb.toString().getBytes());
            fos.close();
        } catch (IOException e) {
            Log.e("MainMenuActivity", "Erro ao atualizar retroarch.cfg", e);
        }
    }

    private void launchRetroActivity() {
        Intent retro = new Intent(this, RetroActivityFuture.class);
        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        retro.putExtra("LIBRETRO", MEDIA_DIR + "/cores");
        retro.putExtra("CONFIGFILE", new File(getFilesDir(), "retroarch.cfg").getAbsolutePath());
        retro.putExtra("IME", Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD));
        retro.putExtra("DATADIR", getApplicationInfo().dataDir);
        retro.putExtra("APK", getApplicationInfo().sourceDir);
        retro.putExtra("SDCARD", Environment.getExternalStorageDirectory().getAbsolutePath());

        retro.putExtra("EXTERNAL", MEDIA_DIR);

        startActivity(retro);
        finish();
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
        retro.putExtra("EXTERNAL", MEDIA_DIR);
    }

    public void finalStartup() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        Intent retro = new Intent(this, RetroActivityFuture.class);
        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        startRetroActivity(
                retro,
                null,
                prefs.getString("libretro_path", getApplicationInfo().dataDir + "/cores/"),
                new File(getFilesDir(), "retroarch.cfg").getAbsolutePath(),
                Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
                getApplicationInfo().dataDir,
                getApplicationInfo().sourceDir
        );
        startActivity(retro);
        finish();
    }
} 
