package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

public final class MainMenuActivity extends PreferenceActivity {

    private final int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    public static String PACKAGE_NAME;
    boolean checkPermissions = false;
    private SharedPreferences prefs;

    private final String[] ASSET_FOLDERS = {
            "assets", "autoconfig", "cores", "database", "filters",
            "info", "overlays", "shaders", "system"
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        UserPreferences.updateConfigFile(this);
        checkRuntimePermissions();
    }

    private void showMessageAndExit(String message) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("OK", (dialog, which) -> finish())
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
                        message += ", " + permissionsNeeded.get(i);

                    new AlertDialog.Builder(this)
                            .setMessage(message)
                            .setCancelable(false)
                            .setPositiveButton("OK", (dialog, which) ->
                                    requestPermissions(permissionsList.toArray(new String[0]),
                                            REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS))
                            .setNegativeButton("Cancel", (dialog, which) -> finish())
                            .show();
                } else {
                    requestPermissions(permissionsList.toArray(new String[0]),
                            REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                }
            }
        }

        if (!checkPermissions) {
            startExtractionOrRetro();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    showMessageAndExit("Permissões necessárias foram negadas. O aplicativo será encerrado.");
                    return;
                }
            }
        }
        startExtractionOrRetro();
    }

    private void startExtractionOrRetro() {
        boolean firstRun = prefs.getBoolean("firstRun", true);
        if (firstRun) {
            new AssetExtractionTask().execute();
        } else {
            finalStartup();
        }
    }

    private class AssetExtractionTask extends AsyncTask<Void, Integer, Boolean> {

        ProgressDialog progressDialog;
        File baseDir;
        List<String> allFiles = new ArrayList<>();

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(MainMenuActivity.this);
            progressDialog.setMessage("Configurando RetroArch DRG...\n\nO aplicativo encerrará após configuração inicial.");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
            progressDialog.show();

            baseDir = new File(Environment.getExternalStorageDirectory(), "Android/media/com.retroarch");
            if (!baseDir.exists()) baseDir.mkdirs();

            // Contar todos os arquivos para barra de progresso detalhada
            try {
                for (String folder : ASSET_FOLDERS) {
                    gatherAllFiles(folder, allFiles);
                }
            } catch (IOException e) {
                Log.e("MainMenuActivity", "Erro ao contar arquivos", e);
            }
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                int processed = 0;
                for (String folder : ASSET_FOLDERS) {
                    File target = new File(baseDir, folder);
                    processed += copyAssetFolder(folder, target, processed);
                }
                generateRetroarchCfg();
                return true;
            } catch (IOException e) {
                Log.e("MainMenuActivity", "Erro ao extrair assets", e);
                return false;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            progressDialog.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            progressDialog.dismiss();
            prefs.edit().putBoolean("firstRun", false).apply();
            finish(); // encerra o app após extração
        }

        private void gatherAllFiles(String assetFolder, List<String> files) throws IOException {
            String[] assets = getAssets().list(assetFolder);
            if (assets != null) {
                for (String asset : assets) {
                    String path = assetFolder + "/" + asset;
                    if (getAssets().list(path).length > 0) {
                        gatherAllFiles(path, files);
                    } else {
                        files.add(path);
                    }
                }
            }
        }

        private int copyAssetFolder(String assetFolder, File targetFolder, int processedSoFar) throws IOException {
            String[] assets = getAssets().list(assetFolder);
            if (!targetFolder.exists()) targetFolder.mkdirs();
            int processed = 0;

            if (assets != null) {
                for (String asset : assets) {
                    String fullPath = assetFolder + "/" + asset;
                    File outFile = new File(targetFolder, asset);

                    if (getAssets().list(fullPath).length > 0) {
                        processed += copyAssetFolder(fullPath, outFile, processedSoFar + processed);
                    } else {
                        try (InputStream in = getAssets().open(fullPath);
                             FileOutputStream out = new FileOutputStream(outFile)) {
                            byte[] buffer = new byte[1024];
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                            }
                        }
                        processed++;
                        publishProgress((int)(((processedSoFar + processed) / (float) allFiles.size()) * 100));
                    }
                }
            }
            return processed;
        }

        private void generateRetroarchCfg() throws IOException {
            File cfgFile = new File(baseDir, "retroarch.cfg");
            if (!cfgFile.exists()) cfgFile.createNewFile();

            try (FileOutputStream out = new FileOutputStream(cfgFile)) {
                StringBuilder content = new StringBuilder("# RetroArch DRG cfg\n");
                for (String folder : ASSET_FOLDERS) {
                    content.append(folder).append("_directory = \"")
                           .append(new File(baseDir, folder).getAbsolutePath())
                           .append("\"\n");
                }
                out.write(content.toString().getBytes());
            }
        }
    }

    public void finalStartup() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
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
