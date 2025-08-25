package com.retroarch.browser.retroactivity;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.PointerIcon;
import android.view.View;
import android.view.WindowManager;
import android.content.Context;
import android.view.KeyEvent;
import android.hardware.input.InputManager;
import android.util.Log;
import android.widget.Toast;

import com.retroarch.browser.preferences.util.ConfigFile;
import com.retroarch.browser.preferences.util.UserPreferences;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class RetroActivityFuture extends RetroActivityCamera {

    private boolean quitfocus = false;
    private View mDecorView;

    private static final int HANDLER_WHAT_TOGGLE_IMMERSIVE = 1;
    private static final int HANDLER_WHAT_TOGGLE_POINTER_CAPTURE = 2;
    private static final int HANDLER_WHAT_TOGGLE_POINTER_NVIDIA = 3;
    private static final int HANDLER_WHAT_TOGGLE_POINTER_ICON = 4;
    private static final int HANDLER_ARG_TRUE = 1;
    private static final int HANDLER_ARG_FALSE = 0;
    private static final int HANDLER_MESSAGE_DELAY_DEFAULT_MS = 300;

    // ===================== MÉTODOS DE CONTROLE =====================
    private void attemptToggleImmersiveMode(boolean state) {
        Log.i("RetroActivityFuture", "attemptToggleImmersiveMode: " + state);
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
            } catch (Exception e) {
                Log.w("RetroActivityFuture", e.getMessage());
            }
        }
    }

    private void attemptTogglePointerCapture(boolean state) {
        Log.i("RetroActivityFuture", "attemptTogglePointerCapture: " + state);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (state) mDecorView.requestPointerCapture();
                else mDecorView.releasePointerCapture();
            } catch (Exception e) {
                Log.w("RetroActivityFuture", e.getMessage());
            }
        }
    }

    private void attemptToggleNvidiaCursorVisibility(boolean state) {
        Log.i("RetroActivityFuture", "attemptToggleNvidiaCursorVisibility: " + state);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            try {
                Method mInputManager_setCursorVisibility =
                        InputManager.class.getMethod("setCursorVisibility", boolean.class);
                InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
                mInputManager_setCursorVisibility.invoke(inputManager, !state);
            } catch (NoSuchMethodException e) {
                // NVIDIA extension não disponível
            } catch (Exception e) {
                Log.w("RetroActivityFuture", e.getMessage());
            }
        }
    }

    private void attemptTogglePointerIcon(boolean state) {
        Log.i("RetroActivityFuture", "attemptTogglePointerIcon: " + state);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            try {
                if (state) {
                    PointerIcon nullPointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL);
                    mDecorView.setPointerIcon(nullPointerIcon);
                } else {
                    mDecorView.setPointerIcon(null);
                }
            } catch (Exception e) {
                Log.w("RetroActivityFuture", e.getMessage());
            }
        }
    }

    // ===================== HANDLER =====================
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            boolean state = (msg.arg1 == HANDLER_ARG_TRUE);
            switch (msg.what) {
                case HANDLER_WHAT_TOGGLE_IMMERSIVE:
                    attemptToggleImmersiveMode(state);
                    break;
                case HANDLER_WHAT_TOGGLE_POINTER_CAPTURE:
                    attemptTogglePointerCapture(state);
                    break;
                case HANDLER_WHAT_TOGGLE_POINTER_NVIDIA:
                    attemptToggleNvidiaCursorVisibility(state);
                    break;
                case HANDLER_WHAT_TOGGLE_POINTER_ICON:
                    attemptTogglePointerIcon(state);
                    break;
            }
        }
    };

    // ===================== CONFIGHELPER =====================
    private static final int INPUT_SELECT_4 = 4;
    private static final int INPUT_SELECT_109 = 109;
    private static final int INPUT_SELECT_196 = 196;
    private static final int MAX_WRONG_ATTEMPTS = 5;
    private static final int TIMEOUT_SECONDS = 10;

    private static CountDownLatch latch;
    private static int selectedInput = -1;

    public void createConfigForUnknownController(int vendorId, int productId, String deviceName) {
        createConfigForUnknownControllerInternal(vendorId, productId, deviceName, this);
    }

    private static void createConfigForUnknownControllerInternal(int vendorId, int productId,
                                                                 String deviceName, Context context) {
        selectedInput = -1;
        latch = new CountDownLatch(1);

        Log.i("RetroActivityFuture", "Criando CFG para: " + deviceName);
        Toast.makeText(context, "Pressione Select para autoconfigurar o controle:", Toast.LENGTH_LONG).show();

        int wrongAttempts = 0;
        try {
            while (selectedInput == -1 && wrongAttempts < MAX_WRONG_ATTEMPTS) {
                boolean pressed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                Log.i("RetroActivityFuture", "Aguardando input: " + pressed);
                if (!pressed) break;

                if (selectedInput == -1) {
                    wrongAttempts++;
                    if (wrongAttempts < MAX_WRONG_ATTEMPTS) {
                        Toast.makeText(context, "Pressione Select para autoconfigurar o controle:", Toast.LENGTH_SHORT).show();
                        latch = new CountDownLatch(1);
                    }
                }
            }

            if (selectedInput != -1) {
                String baseFile;
                switch (selectedInput) {
                    case INPUT_SELECT_4: baseFile = "Base4.cfg"; break;
                    case INPUT_SELECT_109: baseFile = "Base109.cfg"; break;
                    case INPUT_SELECT_196: baseFile = "Base196.cfg"; break;
                    default: baseFile = "Base4.cfg"; break;
                }
                Log.i("RetroActivityFuture", "Input detectado: " + selectedInput);
                createCfgFromBase(baseFile, deviceName, vendorId, productId, context);
            } else {
                Log.i("RetroActivityFuture", "Autoconfiguração falhou");
                Toast.makeText(context, "Autoconfiguração falhou", Toast.LENGTH_SHORT).show();
            }

        } catch (InterruptedException e) {
            Log.i("RetroActivityFuture", "Autoconfiguração interrompida");
        } finally {
            latch = null;
        }
    }

    public static boolean handleKeyEvent(KeyEvent event) {
        if (latch == null) return false;

        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;

        int keyCode = event.getKeyCode();
        Log.i("RetroActivityFuture", "KeyEvent: " + keyCode);
        if (keyCode == INPUT_SELECT_4 || keyCode == INPUT_SELECT_109 || keyCode == INPUT_SELECT_196) {
            selectedInput = keyCode;
            latch.countDown();
        }
        return true;
    }

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
            Log.i("RetroActivityFuture", "CFG criada: " + output.getAbsolutePath());
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

    // ===================== MÉTODOS DE ACTIVITY =====================
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
            } catch (Exception e) {
                Log.w("RetroActivityFuture", e.getMessage());
            }
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
            if (configFile.getBoolean("input_auto_mouse_grab")) {
                inputGrabMouse(hasFocus);
            }
        } catch (Exception e) {
            Log.w("RetroActivityFuture", e.getMessage());
        }
    }

    private void mHandlerSendUiMessage(int what, boolean state) {
        int arg1 = state ? HANDLER_ARG_TRUE : HANDLER_ARG_FALSE;
        Message message = mHandler.obtainMessage(what, arg1, -1);
        mHandler.sendMessageDelayed(message, HANDLER_MESSAGE_DELAY_DEFAULT_MS);
    }

    public void inputGrabMouse(boolean state) {
        mHandlerSendUiMessage(HANDLER_WHAT_TOGGLE_POINTER_CAPTURE, state);
        mHandlerSendUiMessage(HANDLER_WHAT_TOGGLE_POINTER_NVIDIA, state);
        mHandlerSendUiMessage(HANDLER_WHAT_TOGGLE_POINTER_ICON, state);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }
}
