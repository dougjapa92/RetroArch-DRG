package com.retroarch.browser.mainmenu;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import android.app.AlertDialog;
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
import android.content.pm.PackageManager;
import android.Manifest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainMenuActivity extends PreferenceActivity {

    private final int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
    public static String PACKAGE_NAME;
    boolean checkPermissions = false;
    private SharedPreferences prefs;

    private final String[] ASSET_FOLDERS = {
            "assets", "autoconfig", "cores", "database",
            "filters", "info", "overlays", "shaders", "system"
    };

    private final File BASE_DIR = new File(Environment.getExternalStorageDirectory(), "Android/media/com.retroarch");
    private final File CONFIG_DIR = new File(Environment.getExternalStorageDirectory() + "/Android/data/com.retroarch/files");

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PACKAGE_NAME = getPackageName();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        UserPreferences.updateConfigFile(this);

        // Zera o contador de negações sempre que o app inicia
        prefs.edit().putInt("deniedCount", 0).apply();

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

                    new AlertDialog.Builder(this)
                            .setMessage(message)
                            .setCancelable(false)
                            .setPositiveButton("OK", (dialog, which) ->
                                    requestPermissions(permissionsList.toArray(new String[0]),
                                            REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS))
                            .setNegativeButton("Sair", (dialog, which) -> finish())
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
            boolean allGranted = true;

            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                // Reset contador se usuário concedeu
                prefs.edit().putInt("deniedCount", 0).apply();
                startExtractionOrRetro();
            } else {
                int deniedCount = prefs.getInt("deniedCount", 0) + 1;
                prefs.edit().putInt("deniedCount", deniedCount).apply();

                if (deniedCount >= 2) {
                    new AlertDialog.Builder(this)
                            .setTitle("Permissão negada!")
                            .setMessage("Ative as permissões manualmente ou reinstale o aplicativo.")
                            .setCancelable(false)
                            .setPositiveButton("OK", (dialog, which) -> finish())
                            .show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Permissões necessárias")
                            .setMessage("O aplicativo precisa das permissões de armazenamento para funcionar corretamente.")
                            .setCancelable(false)
                            .setPositiveButton("Conceder", (dialog, which) -> {
                                requestPermissions(permissions, REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                            })
                            .setNegativeButton("Sair", (dialog, which) -> finish())
                            .show();
                }
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void startExtractionOrRetro() {
        boolean firstRun = prefs.getBoolean("firstRun", true);
        if (firstRun) {
            new UnifiedExtractionTask().execute();
        } else {
            finalStartup();
        }
    }

    private class UnifiedExtractionTask extends AsyncTask<Void, Integer, Boolean> {

        ProgressDialog progressDialog;
        AtomicInteger processedFiles = new AtomicInteger(0);
        int totalFiles = 0;

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(MainMenuActivity.this);
            progressDialog.setTitle("Permissão negada!")
            progressDialog.setMessage("Clique em \"Sair do RetroArch\" após a configuração ou force o encerramento do aplicativo.");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
            progressDialog.show();

            if (!BASE_DIR.exists()) BASE_DIR.mkdirs();
            totalFiles = countAllFiles(ASSET_FOLDERS);
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(ASSET_FOLDERS.length, 4));

            for (String folder : ASSET_FOLDERS) {
                executor.submit(() -> {
                    try {
                        copyAssetFolder(folder, new File(BASE_DIR, folder));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }

            executor.shutdown();
            while (!executor.isTerminated()) {
                publishProgress((processedFiles.get() * 100) / totalFiles);
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }

            try { updateRetroarchCfg(); } catch (IOException e) { return false; }

            return true;
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            progressDialog.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            progressDialog.dismiss();
            prefs.edit().putBoolean("firstRun", false).apply();
            finalStartup();
        }

        private int countAllFiles(String[] folders) {
            int count = 0;
            for (String folder : folders) count += countFilesRecursive(folder);
            return count;
        }

        private int countFilesRecursive(String assetFolder) {
            try {
                String[] assets = getAssets().list(assetFolder);
                if (assets == null || assets.length == 0) return 1;
                int total = 0;
                for (String asset : assets) total += countFilesRecursive(assetFolder + "/" + asset);
                return total;
            } catch (IOException e) {
                return 0;
            }
        }

        private void copyAssetFolder(String assetFolder, File targetFolder) throws IOException {
            String[] assets = getAssets().list(assetFolder);
            if (!targetFolder.exists()) targetFolder.mkdirs();

            if (assets != null && assets.length > 0) {
                for (String asset : assets) {
                    String fullPath = assetFolder + "/" + asset;
                    File outFile = new File(targetFolder, asset);

                    if (getAssets().list(fullPath).length > 0) {
                        copyAssetFolder(fullPath, outFile);
                    } else {
                        try (InputStream in = getAssets().open(fullPath);
                             FileOutputStream out = new FileOutputStream(outFile)) {
                            byte[] buffer = new byte[1024];
                            int read;
                            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                        }
                        processedFiles.incrementAndGet();
                    }
                }
            }
        }

        private void updateRetroarchCfg() throws IOException {
            File originalCfg = new File(CONFIG_DIR, "retroarch.cfg");
            if (!originalCfg.exists()) originalCfg.getParentFile().mkdirs();
            if (!originalCfg.exists()) originalCfg.createNewFile();

            List<String> lines = java.nio.file.Files.readAllLines(originalCfg.toPath());
            StringBuilder content = new StringBuilder();

            for (String line : lines) {
                boolean replaced = false;
                for (String folder : ASSET_FOLDERS) {
                    if (line.startsWith(folder + "_directory")) {
                        line = folder + "_directory = \"" + new File(BASE_DIR, folder).getAbsolutePath() + "\"";
                        replaced = true;
                        break;
                    }
                }
                content.append(line).append("\n");
            }

            try (FileOutputStream out = new FileOutputStream(originalCfg, false)) {
                out.write(content.toString().getBytes());
            }
        }
    }

    public void finalStartup() {
        Intent retro = new Intent(this, RetroActivityFuture.class);
        retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        MainMenuActivity.startRetroActivity(
                retro,
                null,
                new File(BASE_DIR, "cores").getAbsolutePath(),
                new File(CONFIG_DIR, "retroarch.cfg").getAbsolutePath(),
                Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
                BASE_DIR.getAbsolutePath(),
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
        retro.putExtra("SDCARD", Environment.getExternalStorageDirectory().getAbsolutePath());
        String external = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/" + PACKAGE_NAME + "/files";
        retro.putExtra("EXTERNAL", external);
    }
}
