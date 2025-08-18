package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.provider.Settings;

import java.util.List;
import java.util.ArrayList;
import android.content.pm.PackageManager;
import android.Manifest;
import android.content.DialogInterface;
import android.app.AlertDialog;
import android.util.Log;

import android.app.ProgressDialog;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@link PreferenceActivity} subclass that provides all of the
 * functionality of the main menu screen.
 */
public final class MainMenuActivity extends PreferenceActivity {
    final private int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    public static String PACKAGE_NAME;
    boolean checkPermissions = false;
    private static final String MEDIA_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/media/com.retroarch";
    private ProgressDialog progressDialog;
    private Handler mainHandler;
    private ExecutorService executor;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        UserPreferences.updateConfigFile(this);

        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor();

        checkRuntimePermissions();
    }

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
                    String message = "You need to grant access to " + permissionsNeeded.get(0);
                    for (int i = 1; i < permissionsNeeded.size(); i++)
                        message += ", " + permissionsNeeded.get(i);

                    showMessageOKCancel(message, (dialog, which) -> {
                        if (which == AlertDialog.BUTTON_POSITIVE)
                            requestPermissions(permissionsList.toArray(new String[0]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                    });
                } else {
                    requestPermissions(permissionsList.toArray(new String[0]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                }
                return;
            }
        }
        startExtractionIfNeeded();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int res : grantResults)
                if(res != PackageManager.PERMISSION_GRANTED) allGranted = false;

            if (!allGranted) {
                new AlertDialog.Builder(this)
                    .setMessage("Permissões negadas. Por favor, habilite nas configurações ou reinstale o aplicativo.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (dialog, which) -> finish())
                    .create()
                    .show();
                return;
            }
        }
        startExtractionIfNeeded();
    }

    private void startExtractionIfNeeded() {
        File mediaDir = new File(MEDIA_DIR);
        boolean alreadyExtracted = mediaDir.exists() && mediaDir.listFiles() != null && mediaDir.listFiles().length > 0;

        if (alreadyExtracted) {
            finalStartup();
            return;
        }

        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Configurando RetroArch DRG...\n\nO aplicativo encerrará após configuração inicial.");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setCancelable(false);
        progressDialog.show();

        executor.execute(() -> {
            int totalFiles = countAssets("");
            int[] processedFiles = {0};

            extractAssets("", totalFiles, processedFiles);

            mainHandler.post(() -> {
                progressDialog.dismiss();
                updateRetroarchCfg();
                finish(); // encerra app após extração
            });
        });
    }

    private int countAssets(String path) {
        int count = 0;
        try {
            String[] assets = getAssets().list(path);
            if (assets == null || assets.length == 0) return 1;
            for (String asset : assets) {
                String newPath = path.isEmpty() ? asset : path + "/" + asset;
                count += countAssets(newPath);
            }
        } catch (IOException e) { Log.e("MainMenuActivity", "Erro ao contar assets: " + path, e); }
        return count;
    }

    private void extractAssets(String path, int totalFiles, int[] processedFiles) {
        try {
            String[] assets = getAssets().list(path);
            if (assets == null || assets.length == 0) {
                copyAsset(path, new File(MEDIA_DIR, path));
                processedFiles[0]++;
                int percent = (processedFiles[0]*100)/totalFiles;
                mainHandler.post(() -> progressDialog.setProgress(percent));
                return;
            }
            for (String asset : assets) {
                String newPath = path.isEmpty() ? asset : path + "/" + asset;
                extractAssets(newPath, totalFiles, processedFiles);
            }
        } catch (IOException e) { Log.e("MainMenuActivity", "Erro na extração: " + path, e); }
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
        } catch (IOException e) { Log.e("MainMenuActivity", "Erro ao atualizar retroarch.cfg", e); }
    }

    public void finalStartup() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        Intent retro = new Intent(this, RetroActivityFuture.class);
        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        startRetroActivity(
                retro,
                null,
                prefs.getString("libretro_path", getApplicationInfo().dataDir + "/cores/"),
                UserPreferences.getDefaultConfigPath(this),
                Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
                getApplicationInfo().dataDir,
                getApplicationInfo().sourceDir);
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
        retro.putExtra("SDCARD", Environment.getExternalStorageDirectory().getAbsolutePath());
        String external = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/media/com.retroarch";
        retro.putExtra("EXTERNAL", external);
    }
} 
