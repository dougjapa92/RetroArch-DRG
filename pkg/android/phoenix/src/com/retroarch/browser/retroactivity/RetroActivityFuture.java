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

    // Wrapper não estático chamado via JNI
    public void createConfigForUnknownController(int vendorId, int productId, String deviceName) {
        createConfigForUnknownControllerInternal(vendorId, productId, deviceName, this);
    }

    private static void createConfigForUnknownControllerInternal(int vendorId, int productId,
                                                                 String deviceName, Context context) {
        selectedInput = -1;
        latch = new CountDownLatch(1);

        Log.i("RetroActivityFuture", "Iniciando autoconfiguração para: " + deviceName);
        Toast.makeText(context, "Pressione Select (4, 109 ou 196) para autoconfigurar o controle", Toast.LENGTH_LONG).show();

        int wrongAttempts = 0;

        try {
            while (selectedInput == -1 && wrongAttempts < MAX_WRONG_ATTEMPTS) {
                Log.i("RetroActivityFuture", "Aguardando input do usuário...");
                boolean pressed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (!pressed) {
                    Log.w("RetroActivityFuture", "Timeout atingido sem input do usuário.");
                    break;
                }

                if (selectedInput == -1) {
                    wrongAttempts++;
                    Log.w("RetroActivityFuture", "Botão errado pressionado. Tentativa " + wrongAttempts);
                    if (wrongAttempts < MAX_WRONG_ATTEMPTS) {
                        Toast.makeText(context, "Pressione Select (4, 109 ou 196) para autoconfigurar o controle", Toast.LENGTH_SHORT).show();
                        latch = new CountDownLatch(1); // reinicia latch
                    }
                } else {
                    Log.i("RetroActivityFuture", "Botão correto detectado: " + selectedInput);
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

                Log.i("RetroActivityFuture", "Criando CFG a partir da base: " + baseFile);
                createCfgFromBase(baseFile, deviceName, vendorId, productId, context);

            } else {
                Log.e("RetroActivityFuture", "Autoconfiguração falhou: nenhum input correto recebido");
                Toast.makeText(context, "Autoconfiguração falhou", Toast.LENGTH_SHORT).show();
            }

        } catch (InterruptedException e) {
            Log.e("RetroActivityFuture", "Autoconfiguração interrompida: " + e.getMessage());
            Toast.makeText(context, "Autoconfiguração interrompida", Toast.LENGTH_SHORT).show();
        } finally {
            latch = null;
        }
    }

    public static boolean handleKeyEvent(KeyEvent event) {
        if (latch == null)
            return false;

        if (event.getAction() != KeyEvent.ACTION_DOWN)
            return true;

        int keyCode = event.getKeyCode();
        if (keyCode == INPUT_SELECT_4 || keyCode == INPUT_SELECT_109 || keyCode == INPUT_SELECT_196) {
            selectedInput = keyCode;
            latch.countDown();
            Log.i("RetroActivityFuture", "Input capturado via handleKeyEvent: " + keyCode);
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
            Log.i("RetroActivityFuture", "Configuração criada: " + output.getAbsolutePath());
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
    // ===================== FIM CONFIGHELPER =====================

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDecorView = getWindow().getDecorView();
        quitfocus = getIntent().hasExtra("QUITFOCUS");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleKeyEvent(event)) {
            Log.i("RetroActivityFuture", "dispatchKeyEvent recebeu keyCode: " + event.getKeyCode());
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // Mantém restante do código do RetroActivityFuture (immersive, pointer capture, etc.)...
}
