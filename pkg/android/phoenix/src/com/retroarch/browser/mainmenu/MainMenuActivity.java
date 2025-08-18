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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

/**
 * {@link PreferenceActivity} subclass that provides all of the
 * functionality of the main menu screen.
 */
public final class MainMenuActivity extends PreferenceActivity
{
	final private int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;
	public static String PACKAGE_NAME;
	boolean checkPermissions = false;

	// Caminho base externo customizado
	private static final String CUSTOM_BASE_DIR =
			Environment.getExternalStorageDirectory().getAbsolutePath() + "/RetroArch-DRG";

	public void showMessageOKCancel(String message, DialogInterface.OnClickListener onClickListener)
	{
		new AlertDialog.Builder(this).setMessage(message)
			.setPositiveButton("OK", onClickListener).setCancelable(false)
			.setNegativeButton("Cancel", null).create().show();
	}

	private boolean addPermission(List<String> permissionsList, String permission)
	{
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
		{
			if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
			{
				permissionsList.add(permission);

				// Check for Rationale Option
				if (!shouldShowRequestPermissionRationale(permission))
					return false;
			}
		}

		return true;
	}

	public void checkRuntimePermissions()
	{
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
		{
			// Android 6.0+ needs runtime permission checks
			List<String> permissionsNeeded = new ArrayList<String>();
			final List<String> permissionsList = new ArrayList<String>();

			if (!addPermission(permissionsList, Manifest.permission.READ_EXTERNAL_STORAGE))
				permissionsNeeded.add("Read External Storage");
			if (!addPermission(permissionsList, Manifest.permission.WRITE_EXTERNAL_STORAGE))
				permissionsNeeded.add("Write External Storage");

			if (permissionsList.size() > 0)
			{
				checkPermissions = true;

				if (permissionsNeeded.size() > 0)
				{
					// Need Rationale
					Log.i("MainMenuActivity", "Need to request external storage permissions.");

					String message = "You need to grant access to " + permissionsNeeded.get(0);

					for (int i = 1; i < permissionsNeeded.size(); i++)
						message = message + ", " + permissionsNeeded.get(i);

					showMessageOKCancel(message,
						new DialogInterface.OnClickListener()
						{
							@Override
							public void onClick(DialogInterface dialog, int which)
							{
								if (which == AlertDialog.BUTTON_POSITIVE)
								{
									requestPermissions(permissionsList.toArray(new String[permissionsList.size()]),
											REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);

									Log.i("MainMenuActivity", "User accepted request for external storage permissions.");
								}
							}
						});
				}
				else
				{
					requestPermissions(permissionsList.toArray(new String[permissionsList.size()]),
						REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);

					Log.i("MainMenuActivity", "Requested external storage permissions.");
				}
			}
		}

		if (!checkPermissions)
		{
			finalStartup();
		}
	}

	public void finalStartup()
	{
		// Garante que os assets foram extraídos na primeira execução
		extractAssetsIfNeeded("cores");
		extractAssetsIfNeeded("system");

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		Intent retro = new Intent(this, RetroActivityFuture.class);

		retro.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

		startRetroActivity(
				retro,
				null,
				CUSTOM_BASE_DIR + "/cores/",
				UserPreferences.getDefaultConfigPath(this),
				Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
				CUSTOM_BASE_DIR,
				getApplicationInfo().sourceDir);
		startActivity(retro);
		finish();
	}

	private void extractAssetsIfNeeded(String folderName) {
		File targetDir = new File(CUSTOM_BASE_DIR, folderName);
		if (!targetDir.exists()) {
			targetDir.mkdirs();
			try {
				String[] files = getAssets().list(folderName);
				if (files != null) {
					for (String file : files) {
						copyAsset(folderName + "/" + file,
								new File(targetDir, file));
					}
				}
			} catch (IOException e) {
				Log.e("MainMenuActivity", "Erro ao copiar assets: " + folderName, e);
			}
		}
	}

	private void copyAsset(String assetPath, File outFile) throws IOException {
		InputStream in = getAssets().open(assetPath);
		OutputStream out = new FileOutputStream(outFile);
		byte[] buffer = new byte[4096];
		int read;
		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}
		in.close();
		out.flush();
		out.close();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults)
	{
		switch (requestCode)
		{
			case REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS:
				for (int i = 0; i < permissions.length; i++)
				{
					if(grantResults[i] == PackageManager.PERMISSION_GRANTED)
					{
						Log.i("MainMenuActivity", "Permission: " + permissions[i] + " was granted.");
					}
					else
					{
						Log.i("MainMenuActivity", "Permission: " + permissions[i] + " was not granted.");
					}
				}

				break;
			default:
				super.onRequestPermissionsResult(requestCode, permissions, grantResults);
				break;
		}

		finalStartup();
	}

	public static void startRetroActivity(Intent retro, String contentPath, String corePath,
			String configFilePath, String imePath, String dataDirPath, String dataSourcePath)
	{
		if (contentPath != null) {
			retro.putExtra("ROM", contentPath);
		}
		retro.putExtra("LIBRETRO", corePath);
		retro.putExtra("CONFIGFILE", configFilePath);
		retro.putExtra("IME", imePath);
		retro.putExtra("DATADIR", dataDirPath);
		retro.putExtra("APK", dataSourcePath);
		retro.putExtra("SDCARD", Environment.getExternalStorageDirectory().getAbsolutePath());
		retro.putExtra("EXTERNAL", CUSTOM_BASE_DIR);
	}

	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		PACKAGE_NAME = getPackageName();

		// Bind audio stream to hardware controls.
		setVolumeControlStream(AudioManager.STREAM_MUSIC);

		UserPreferences.updateConfigFile(this);

		checkRuntimePermissions();
	}
}
