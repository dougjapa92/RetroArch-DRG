package com.retroarch.browser.retroactivity;

import android.util.Log;
import android.view.PointerIcon;
import android.view.View;
import android.view.WindowManager;
import android.content.Intent;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.SpannableString;
import android.text.style.AbsoluteSizeSpan;
import android.util.TypedValue;
import android.widget.TextView;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import com.retroarch.browser.preferences.util.ConfigFile;
import com.retroarch.browser.preferences.util.UserPreferences;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

// Android framework
import android.app.AlertDialog;
import android.view.KeyEvent;
import android.widget.Toast;

// Java standard library
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
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

    // ===================== AUTOCONFIGURATION =====================
    private static final int INPUT_SELECT_4 = 4;
    private static final int INPUT_SELECT_109 = 109;
    private static final int INPUT_SELECT_196 = 196;
    private static final int TIMEOUT_SECONDS = 10;

    private AlertDialog dialog;
    private CountDownLatch latch;
    private int selectedInput = -1;

    /** Método chamado via JNI de forma síncrona */
    public boolean createConfigForUnknownControllerSync(int vendorId, int productId, String deviceName) {
        final int[] attemptsLeft = {3};
        selectedInput = -1;
        latch = new CountDownLatch(1);
    
        Log.d("RetroActivityFuture", "[Autoconf] Iniciando autoconfiguração para dispositivo: " + deviceName);
    
        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
    
            // Título
            SpannableString spannableTitle = new SpannableString("Autoconfiguração de Controle");
            spannableTitle.setSpan(new StyleSpan(Typeface.BOLD), 0, spannableTitle.length(), 0);
    
            // Mensagem inicial
            final TextView messageView = new TextView(this);
            messageView.setText("Pressione Select (Options) para autoconfigurar o controle.\nTentativas restantes: " + attemptsLeft[0]);
            messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            messageView.setGravity(Gravity.CENTER_HORIZONTAL);
            int padding = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
            messageView.setPadding(padding, padding, padding, padding);
    
            // Layout vertical
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.addView(messageView);
    
            builder.setTitle(spannableTitle);
            builder.setView(layout);
            builder.setCancelable(false);
    
            dialog = builder.create();
    
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == INPUT_SELECT_4 || keyCode == INPUT_SELECT_109 || keyCode == INPUT_SELECT_196) {
                        selectedInput = keyCode;
                        latch.countDown();
                        dialog.dismiss();
                        return true;
                    } else {
                        attemptsLeft[0]--;
                        messageView.setText("Botão inválido! Tentativas restantes: " + attemptsLeft[0]);
                        if (attemptsLeft[0] <= 0) {
                            latch.countDown();
                            dialog.dismiss();
                        }
                        return true;
                    }
                }
                return false;
            });
    
            dialog.show();
    
            // Ajuste tamanho do título após mostrar
            TextView titleView = dialog.findViewById(android.R.id.title);
            if (titleView != null) titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        });
    
        try {
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Log.d("RetroActivityFuture", "[Autoconf] Espera finalizada ou timeout atingido.");
        } catch (InterruptedException e) {
            Log.e("RetroActivityFuture", "[Autoconf] Interrompido durante espera", e);
        }
    
        if (dialog != null && dialog.isShowing()) {
            Log.d("RetroActivityFuture", "[Autoconf] Diálogo ainda aberto. Fechando...");
            dialog.dismiss();
        }
    
        boolean cfgCreated = false;
    
        if (selectedInput != -1) {
            String baseFile;
            switch (selectedInput) {
                case INPUT_SELECT_4:
                    baseFile = "Base4.cfg";
                    break;
                case INPUT_SELECT_109:
                    baseFile = "Base109.cfg";
                    break;
                case INPUT_SELECT_196:
                    baseFile = "Base196.cfg";
                    break;
                default:
                    baseFile = "Base4.cfg";
                    break;
            }
            Log.d("RetroActivityFuture", "[Autoconf] Criando CFG com base: " + baseFile);
            createCfgFromBase(baseFile, deviceName, vendorId, productId, this);
            cfgCreated = true;
            Log.d("RetroActivityFuture", "[Autoconf] CFG criada com sucesso.");
        } else {
            Log.d("RetroActivityFuture", "[Autoconf] Nenhum Select pressionado. Autoconfiguração cancelada.");
            Toast.makeText(this, "Nenhum Select pressionado, autoconfiguração cancelada", Toast.LENGTH_SHORT).show();
        }
    
        latch = null;
        Log.d("RetroActivityFuture", "[Autoconf] createConfigForUnknownControllerSync retornando: " + cfgCreated);
        return cfgCreated;
    }
    
    /** Criação do arquivo CFG */
    private static void createCfgFromBase(String baseFile, String deviceName,
                                          int vendorId, int productId, Context context) {

        File basePath = new File(context.getExternalMediaDirs()[0], "autoconfig/bases");
        File androidPath = new File(context.getExternalMediaDirs()[0], "autoconfig/android");
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
            try {
                fis.read(bytes);
            } finally {
                fis.close();
            }
            return new String(bytes);
        }
    }

    // ===================== ACTIVITY METHODS =====================
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
            } catch (Exception e) {
                Log.w("RetroActivityFuture", e.getMessage());
            }
        }
    }

    private void attemptTogglePointerCapture(boolean state) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            try {
                Method m = InputManager.class.getMethod("setCursorVisibility", boolean.class);
                InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
                m.invoke(im, !state);
            } catch (NoSuchMethodException e) {
                // Método não existe — provavelmente não é NVIDIA
            } catch (Exception e) {
                Log.w("RetroActivityFuture", e.getMessage());
            }
        }
    }

    private void attemptTogglePointerIcon(boolean state) {
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (latch != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == INPUT_SELECT_4 || keyCode == INPUT_SELECT_109 || keyCode == INPUT_SELECT_196) {
                selectedInput = keyCode;
                latch.countDown();
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
