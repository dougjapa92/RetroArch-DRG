package com.retroarch.browser.retroactivity;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.appcompat.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.view.WindowManager;
import android.view.KeyEvent;

import com.retroarch.browser.preferences.util.ConfigFile;
import com.retroarch.browser.preferences.util.UserPreferences;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public final class RetroActivityFuture extends RetroActivityCamera {

    private boolean quitfocus = false;
    private View mDecorView;
    private static int selectedInput = -1;

    private static final int INPUT_SELECT_4 = 4;
    private static final int INPUT_SELECT_109 = 109;
    private static final int INPUT_SELECT_196 = 196;
    private static final int TIMEOUT_SECONDS = 10;

    /** Método chamado via JNI */
    public void createConfigForUnknownController(int vendorId, int productId, String deviceName) {
        ControllerConfigDialogFragment.newInstance(deviceName, vendorId, productId)
                .show(getSupportFragmentManager(), "controller_config");
    }

    /** DialogFragment para captura de input físico */
    public static class ControllerConfigDialogFragment extends DialogFragment {

        private int vendorId, productId;
        private String deviceName;
        private int remaining = TIMEOUT_SECONDS;
        private final Handler handler = new Handler(Looper.getMainLooper());

        public static ControllerConfigDialogFragment newInstance(String deviceName, int vendorId, int productId) {
            ControllerConfigDialogFragment f = new ControllerConfigDialogFragment();
            Bundle args = new Bundle();
            args.putString("deviceName", deviceName);
            args.putInt("vendorId", vendorId);
            args.putInt("productId", productId);
            f.setArguments(args);
            return f;
        }

        @NonNull
        @Override
        public AlertDialog onCreateDialog(Bundle savedInstanceState) {
            deviceName = getArguments().getString("deviceName");
            vendorId = getArguments().getInt("vendorId");
            productId = getArguments().getInt("productId");

            AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
            builder.setTitle("Autoconfiguração de Controle");
            builder.setMessage("Pressione Select (4,109,196) no controle.\nTimeout: " + TIMEOUT_SECONDS + "s");
            builder.setCancelable(false);
            builder.setNegativeButton("Cancelar", (d, which) -> {
                Log.i("ControllerConfigDialog", "Cancelado pelo usuário");
                selectedInput = -1;
                Toast.makeText(getActivity(), "Autoconfiguração cancelada", Toast.LENGTH_SHORT).show();
                dismiss();
            });

            AlertDialog dialog = builder.create();

            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN &&
                   (keyCode == INPUT_SELECT_4 || keyCode == INPUT_SELECT_109 || keyCode == INPUT_SELECT_196)) {
                    selectedInput = keyCode;
                    Log.i("ControllerConfigDialog", "Input detectado: " + keyCode);
                    dismiss();
                    createCfgFromBase(getBaseFileForInput(keyCode), deviceName, vendorId, productId, getActivity());
                    return true;
                }
                return false;
            });

            dialog.setOnShowListener(d -> {
                // Bordas arredondadas e largura ajustada
                if (dialog.getWindow() != null) {
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(0xFFFFFFFF);
                    bg.setCornerRadius(24f);
                    dialog.getWindow().setBackgroundDrawable(bg);

                    WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
                    lp.width = (int) (requireActivity().getResources().getDisplayMetrics().widthPixels * 0.85);
                    lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                    dialog.getWindow().setAttributes(lp);
                }
                startCountdown(dialog);
            });

            return dialog;
        }

        private void startCountdown(AlertDialog dialog) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (selectedInput != -1 || remaining <= 0 || !isAdded()) {
                        if (remaining <= 0) {
                            Toast.makeText(getActivity(), "Autoconfiguração cancelada por timeout", Toast.LENGTH_SHORT).show();
                        }
                        if (dialog.isShowing()) dialog.dismiss();
                        return;
                    }
                    dialog.setMessage("Pressione Select (4,109,196) no controle.\nTimeout: " + remaining + "s");
                    remaining--;
                    handler.postDelayed(this, 1000);
                }
            }, 1000);
        }

        private String getBaseFileForInput(int input) {
            switch (input) {
                case INPUT_SELECT_4: return "Base4.cfg";
                case INPUT_SELECT_109: return "Base109.cfg";
                case INPUT_SELECT_196: return "Base196.cfg";
                default: return "Base4.cfg";
            }
        }
    }

    /** Criação do arquivo CFG */
    private static void createCfgFromBase(String baseFile, String deviceName,
                                          int vendorId, int productId, Context context) {

        File basePath = new File(context.getFilesDir(), "autoconfig/bases");
        File androidPath = new File(context.getFilesDir(), "autoconfig/android");
        if (!androidPath.exists()) androidPath.mkdirs();

        File base = new File(basePath, baseFile);
        File output = new File(androidPath, deviceName + ".cfg");

        try (FileWriter writer = new FileWriter(output)) {
            String baseContent = Utils.readFileToString(base);
            writer.write(baseContent);
            writer.write("\ninput_device = \"" + deviceName + "\"\n");
            writer.write("input_vendor_id = " + vendorId + "\n");
            writer.write("input_product_id = " + productId + "\n");
            writer.flush();
            Log.i("RetroActivityFuture", "Configuração criada: " + output.getName());
            Toast.makeText(context, "Configuração criada: " + output.getName(), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e("RetroActivityFuture", "Erro ao criar CFG: " + e.getMessage());
            Toast.makeText(context, "Erro ao criar CFG: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static class Utils {
        static String readFileToString(File file) throws IOException {
            byte[] bytes = new byte[(int) file.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            try { fis.read(bytes); } finally { fis.close(); }
            return new String(bytes);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDecorView = getWindow().getDecorView();
        quitfocus = getIntent().hasExtra("QUITFOCUS");
    }

    @Override
    public void onResume() {
        super.onResume();
        setSustainedPerformanceMode(sustainedPerformanceMode);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String refresh = getIntent().getStringExtra("REFRESH");
            if (refresh != null) {
                WindowManager.LayoutParams params = getWindow().getAttributes();
                params.preferredRefreshRate = Integer.parseInt(refresh);
                getWindow().setAttributes(params);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ConfigFile configFile = new ConfigFile(UserPreferences.getDefaultConfigPath(this));
                if (configFile.getBoolean("video_notch_write_over_enable")) {
                    getWindow().getAttributes().layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
            } catch (Exception e) { Log.w("RetroActivityFuture", e.getMessage()); }
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (quitfocus) System.exit(0);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        mHandlerSendUiMessage(HANDLER_WHAT_TOGGLE_IMMERSIVE, hasFocus);
        try {
            ConfigFile configFile = new ConfigFile(UserPreferences.getDefaultConfigPath(this));
            if (configFile.getBoolean("input_auto_mouse_grab")) inputGrabMouse(hasFocus);
        } catch (Exception e) { Log.w("RetroActivityFuture", e.getMessage()); }
    }

    private void mHandlerSendUiMessage(int what, boolean state) {
        int arg1 = state ? HANDLER_ARG_TRUE : HANDLER_ARG_FALSE;
        android.os.Message message = mHandler.obtainMessage(what, arg1, -1);
        mHandler.sendMessageDelayed(message, HANDLER_MESSAGE_DELAY_DEFAULT_MS);
    }

    public void inputGrabMouse(boolean state) {
        mHandlerSendUiMessage(HANDLER_WHAT_TOGGLE_POINTER_CAPTURE, state);
        mHandlerSendUiMessage(HANDLER_WHAT_TOGGLE_POINTER_NVIDIA, state);
        mHandlerSendUiMessage(HANDLER_WHAT_TOGGLE_POINTER_ICON, state);
    }

    private void attemptToggleImmersiveMode(boolean state) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                if (state) {
                    mDecorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LOW_PROFILE
                            | View.SYSTEM_UI_FLAG_IMMERSIVE);
                } else {
                    mDecorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
                }
            } catch (Exception e) { Log.w("RetroActivityFuture", e.getMessage()); }
        }
    }

    private void attemptTogglePointerCapture(boolean state) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (state) mDecorView.requestPointerCapture();
                else mDecorView.releasePointerCapture();
            } catch (Exception e) { Log.w("RetroActivityFuture", e.getMessage()); }
        }
    }

    private void attemptToggleNvidiaCursorVisibility(boolean state) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            try {
                Method m = InputManager.class.getMethod("setCursorVisibility", boolean.class);
                InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
                m.invoke(im, !state);
            } catch (NoSuchMethodException e) { }
            catch (Exception e) { Log.w("RetroActivityFuture", e.getMessage()); }
        }
    }

    private void attemptTogglePointerIcon(boolean state) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            try {
                if (state) mDecorView.setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));
                else mDecorView.setPointerIcon(null);
            } catch (Exception e) { Log.w("RetroActivityFuture", e.getMessage()); }
        }
    }
}
