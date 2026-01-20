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
    private static final int INPUT_SELECT_104 = 104;
    private static final int INPUT_SELECT_109 = 109;
    private static final int INPUT_SELECT_196 = 196;
    private static final int TIMEOUT_SECONDS = 15;
    private static final int HOLD_DURATION_MS = 1200;
    
    private AlertDialog dialog;
    private CountDownLatch latch;
    private int selectedInput = -1;
    
    public boolean createCfgForUnknownControllerSync(int vendorId, int productId, String deviceName) {
        final int[] attemptsLeft = {3};
        selectedInput = -1;
        latch = new CountDownLatch(1);
    
        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setCancelable(false);
    
            TextView titleView = new TextView(this);
            titleView.setText("Autoconfiguração de Controle");
            titleView.setGravity(Gravity.CENTER);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setTextSize(20);
            titleView.setPadding(20, 40, 20, 20);
            builder.setCustomTitle(titleView);
    
            TextView messageView = new TextView(this);
            messageView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            messageView.setGravity(Gravity.CENTER);
            messageView.setTextSize(16);
            messageView.setPadding(40, 30, 40, 30);
            messageView.setLines(8);
            builder.setView(messageView);
    
            final Handler mainHandler = new Handler(Looper.getMainLooper());
            final Handler holdHandler = new Handler(Looper.getMainLooper());
            final int[] remainingSeconds = {TIMEOUT_SECONDS};
            final int[] currentKeyCode = {0};
            final boolean[] successWaitingForRelease = {false};
            final boolean[] isShowingInvalidMessage = {false};
            final boolean[] failedWaitingForRelease = {false};
            final boolean[] isProcessActive = {true};
            final long[] resultShownTimestamp = {0};
            
            final String MESSAGE_TEMPLATE = "%s\n\n%s\n\nTentativas restantes: %d\n\n%ds";
            final String SUCCESS_TEMPLATE = "✅ Controle configurado com sucesso!\n\nBotão: %d\n\nSolte o botão para continuar.";
            final String FAILURE_MESSAGE = "❌ Falha na configuração!\n\n"
                + "Feche o RetroArch DRG para tentar novamente ou configure manualmente em:\n\n"
                + "Configurações > Entrada > RetroPad Binds > Controle da porta 1 > Definir todos os Controles";
    
            final Runnable[] countdownRunnableHolder = new Runnable[1];
    
            final Runnable updateMessage = () -> {
                if (successWaitingForRelease[0]) {
                    messageView.setLines(5);
                    messageView.setText(String.format(SUCCESS_TEMPLATE, selectedInput));
                    return;
                }
                if (failedWaitingForRelease[0]) {
                    messageView.setLines(8);
                    messageView.setText(FAILURE_MESSAGE);
                    return;
                }
                String feedbackLine = isShowingInvalidMessage[0] ? "BOTÃO INVÁLIDO!" : 
                                     (currentKeyCode[0] != 0 ? "Botão: " + currentKeyCode[0] : " ");
                String instructionLine = "Pressione e segure SELECT (Options) para configurar o controle.";
                messageView.setText(String.format(MESSAGE_TEMPLATE, instructionLine, feedbackLine, attemptsLeft[0], remainingSeconds[0]));
            };
    
            final Runnable holdSuccessRunnable = () -> {
                selectedInput = currentKeyCode[0];
                successWaitingForRelease[0] = true;
                resultShownTimestamp[0] = System.currentTimeMillis();
                remainingSeconds[0] = 10; // Reset para tempo de leitura
                updateMessage.run();
            };
            
            final Runnable invalidPressRunnable = () -> {
                if (attemptsLeft[0] > 0) attemptsLeft[0]--;
                if (attemptsLeft[0] <= 0) {
                    failedWaitingForRelease[0] = true;
                    resultShownTimestamp[0] = System.currentTimeMillis();
                    remainingSeconds[0] = 10; // Reset para tempo de leitura
                } else {
                    isShowingInvalidMessage[0] = true;
                }
                updateMessage.run();
            };
    
            countdownRunnableHolder[0] = () -> {
                if (!isProcessActive[0]) return;
                if (remainingSeconds[0] <= 0) {
                    isProcessActive[0] = false;
                    holdHandler.removeCallbacksAndMessages(null);
                    if (selectedInput == -1 && latch.getCount() > 0) latch.countDown();
                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                    return;
                }
                updateMessage.run();
                remainingSeconds[0]--;
                mainHandler.postDelayed(countdownRunnableHolder[0], 1000);
            };
    
            dialog = builder.create();
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() > 0 || !isProcessActive[0]) return true;
                    currentKeyCode[0] = keyCode;
                    boolean isSelectKey = (keyCode == INPUT_SELECT_4 || keyCode == INPUT_SELECT_104 || keyCode == INPUT_SELECT_109 || keyCode == INPUT_SELECT_196);
                    if (isSelectKey) holdHandler.postDelayed(holdSuccessRunnable, HOLD_DURATION_MS);
                    else holdHandler.postDelayed(invalidPressRunnable, HOLD_DURATION_MS);
                    updateMessage.run();
                    return true;
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (keyCode == currentKeyCode[0]) {
                        if (successWaitingForRelease[0]) {
                            isProcessActive[0] = false;
                            mainHandler.removeCallbacksAndMessages(null);
                            if (latch.getCount() > 0) latch.countDown();
                            dialog.dismiss();
                            return true;
                        }
                        if (failedWaitingForRelease[0]) {
                            long elapsed = System.currentTimeMillis() - resultShownTimestamp[0];
                            if (elapsed >= 2000) {
                                isProcessActive[0] = false;
                                mainHandler.removeCallbacksAndMessages(null);
                                if (latch.getCount() > 0) latch.countDown();
                                dialog.dismiss();
                            } else {
                                mainHandler.postDelayed(() -> {
                                    if (dialog.isShowing()) {
                                        isProcessActive[0] = false;
                                        if (latch.getCount() > 0) latch.countDown();
                                        dialog.dismiss();
                                    }
                                }, 2000 - elapsed);
                            }
                            return true;
                        }
                        holdHandler.removeCallbacks(holdSuccessRunnable);
                        holdHandler.removeCallbacks(invalidPressRunnable);
                        currentKeyCode[0] = 0;
                        isShowingInvalidMessage[0] = false;
                        updateMessage.run();
                    }
                    return true;
                }
                return false;
            });
    
            dialog.setOnShowListener(d -> mainHandler.post(countdownRunnableHolder[0]));
            dialog.show();
        });
    
        try {
            // Ajustado para comportar o tempo extra de leitura de 10s
            latch.await(TIMEOUT_SECONDS + 12, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    
        if (dialog != null && dialog.isShowing()) {
            runOnUiThread(dialog::dismiss);
        }
    
        if (selectedInput != -1) {
            String baseFile;
            switch (selectedInput) {
                case INPUT_SELECT_4: baseFile = "Base4.cfg"; break;
                case INPUT_SELECT_104: baseFile = "Base104.cfg"; break;
                case INPUT_SELECT_109: baseFile = "Base109.cfg"; break;
                case INPUT_SELECT_196: baseFile = "Base196.cfg"; break;
                default: baseFile = "Base4.cfg"; break;
            }
            createCfgFromBase(baseFile, deviceName, vendorId, productId, this);
            return true;
        }
        return false;
    }
   
    private static void createCfgFromBase(String baseFile, String deviceName, int vendorId, int productId, Context context) {
        File basePath = new File(context.getExternalMediaDirs()[0], "autoconfig/bases");
        File androidPath = new File(context.getExternalMediaDirs()[0], "autoconfig/android");
        if (!androidPath.exists()) androidPath.mkdirs();
        File base = new File(basePath, baseFile);
        File output = new File(androidPath, deviceName + ".cfg");
        try {
            String baseContent = Utils.readFileToString(base);
            StringBuilder newContent = new StringBuilder();
            newContent.append("input_device = \"").append(deviceName).append("\"\n");
            newContent.append("input_vendor_id = \"").append(vendorId).append("\"\n");
            newContent.append("input_product_id = \"").append(productId).append("\"\n");
            newContent.append(baseContent);
            try (FileWriter writer = new FileWriter(output)) {
                writer.write(newContent.toString());
                writer.flush();
            }
            Log.i("RetroActivityFuture", "Configuração criada: " + output.getName());
        } catch (IOException e) {
            Log.e("RetroActivityFuture", "Erro ao criar CFG: " + e.getMessage());
        }
    }

    private static class Utils {
        static String readFileToString(File file) throws IOException {
            byte[] bytes = new byte[(int) file.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                fis.read(bytes);
            }
            return new String(bytes);
        }
    }

    // ===================== ACTIVITY METHODS (RESTORED) =====================
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
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
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
                // Not Nvidia
            } catch (Exception e) {
                Log.w("RetroActivityFuture", e.getMessage());
            }
        }
    }

    private void attemptTogglePointerIcon(boolean state) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                PointerIcon icon = state ? PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL) : null;
                mDecorView.setPointerIcon(icon);
            } catch (Exception e) {
                Log.w("RetroActivityFuture", e.getMessage());
            }
        }
    }
}
