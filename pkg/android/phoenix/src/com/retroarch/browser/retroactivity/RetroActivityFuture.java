package com.retroarch.browser.retroactivity;

import android.util.Log;
import android.view.View;
import android.view.PointerIcon;
import android.view.WindowManager;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import android.app.AlertDialog;
import android.view.KeyEvent;
import android.widget.Toast;
import android.widget.TextView;
import android.view.Gravity;
import android.net.Uri;
import android.widget.LinearLayout;
import android.graphics.Typeface;

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
    
    /** Método chamado via JNI de forma síncrona
     *  Retorna o path completo do arquivo CFG criado, ou null se não foi criado */
    public String createCfgForUnknownControllerSync(int vendorId, int productId, String deviceName) {
        final int[] attemptsLeft = {3};
        selectedInput = -1;
        latch = new CountDownLatch(1);
    
        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Autoconfiguração de Controle");
            builder.setCancelable(false);
    
            // Layout customizado para centralizar texto e título e permitir contador
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(50, 30, 50, 30);
    
            TextView titleView = new TextView(this);
            titleView.setText("Autoconfiguração de Controle");
            titleView.setTextSize(20);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setGravity(Gravity.CENTER);
            layout.addView(titleView);
    
            TextView messageView = new TextView(this);
            messageView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            messageView.setText("Pressione Select (Options) para autoconfigurar o controle.\nTentativas restantes: " + attemptsLeft[0]);
            messageView.setPadding(0, 20, 0, 0);
            layout.addView(messageView);
    
            builder.setView(layout);
    
            dialog = builder.create();
    
            final Handler handler = new Handler(Looper.getMainLooper());
            Runnable timeoutRunnable = new Runnable() {
                @Override
                public void run() {
                    if (latch.getCount() > 0) {
                        latch.countDown();
                        dialog.dismiss();
                    }
                }
            };
    
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == INPUT_SELECT_4 || keyCode == INPUT_SELECT_109 || keyCode == INPUT_SELECT_196) {
                        selectedInput = keyCode;
                        latch.countDown();
                        dialog.dismiss();
                        handler.removeCallbacks(timeoutRunnable);
                        return true;
                    } else {
                        attemptsLeft[0]--;
                        messageView.setText("Botão inválido! Pressione somente Select (Options).\nTentativas restantes: " + attemptsLeft[0]);
                        handler.removeCallbacks(timeoutRunnable);
    
                        if (attemptsLeft[0] > 0) {
                            // Reinicia o timer de 10 segundos
                            latch = new CountDownLatch(1);
                            handler.postDelayed(timeoutRunnable, TIMEOUT_SECONDS * 1000);
                        } else {
                            latch.countDown();
                            dialog.dismiss();
                        }
                        return true;
                    }
                }
                return false;
            });
    
            dialog.show();
            // Inicia contador da primeira vez
            handler.postDelayed(timeoutRunnable, TIMEOUT_SECONDS * 1000);
        });
    
        try {
            latch.await(TIMEOUT_SECONDS + 1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    
        if (selectedInput == -1) {
            return null;
        }
    
        String baseFile;
        switch (selectedInput) {
            case INPUT_SELECT_4:  baseFile = "Base4.cfg"; break;
            case INPUT_SELECT_109: baseFile = "Base109.cfg"; break;
            case INPUT_SELECT_196: baseFile = "Base196.cfg"; break;
            default: baseFile = "Base4.cfg"; break;
        }
    
        createCfgFromBase(baseFile, deviceName, vendorId, productId, this);
    
        File androidPath = new File(getExternalMediaDirs()[0], "autoconfig/android/" + deviceName + ".cfg");
        return androidPath.getAbsolutePath();
    }
    
    /** Criação do arquivo CFG */
    private static void createCfgFromBase(String baseFile, String deviceName,
                                          int vendorId, int productId, Context context) {
    
        File basePath = new File(context.getExternalMediaDirs()[0], "autoconfig/bases");
        File androidPath = new File(context.getExternalMediaDirs()[0], "autoconfig/android");
        if (!androidPath.exists()) androidPath.mkdirs();
    
        File base = new File(basePath, baseFile);
        File output = new File(androidPath, deviceName + ".cfg");
    
        try {
            // lê o conteúdo base
            String baseContent = Utils.readFileToString(base);
    
            // monta as linhas novas que irão no topo
            StringBuilder newContent = new StringBuilder();
            newContent.append("input_device = \"").append(deviceName).append("\"\n");
            newContent.append("input_vendor_id = \"").append(vendorId).append("\"\n");
            newContent.append("input_product_id = \"").append(productId).append("\"\n");
    
            // adiciona o conteúdo base depois
            newContent.append(baseContent);
    
            // escreve no arquivo
            try (FileWriter writer = new FileWriter(output)) {
                writer.write(newContent.toString());
                writer.flush();
            }
    
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
