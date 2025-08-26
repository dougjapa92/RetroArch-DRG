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
    public void createConfigForUnknownControllerSync(int vendorId, int productId, String deviceName) {
        selectedInput = -1;
        latch = new CountDownLatch(1);

        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Autoconfiguração de Controle");
            builder.setMessage("Pressione Select (4, 109 ou 196) ou cancele.");
            builder.setCancelable(false);
            builder.setNegativeButton("Cancelar", (d, w) -> {
                latch.countDown();
            });

            dialog = builder.create();
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN &&
                    (keyCode == INPUT_SELECT_4 || keyCode == INPUT_SELECT_109 || keyCode == INPUT_SELECT_196)) {
                    selectedInput = keyCode;
                    latch.countDown();
                    dialog.dismiss();
                    return true;
                }
                return false;
            });

            dialog.show();
        });

        try {
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e("RetroActivityFuture", "Interrompido durante espera");
        }

        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }

        if (selectedInput != -1) {
            String baseFile = switch (selectedInput) {
                case INPUT_SELECT_4 -> "Base4.cfg";
                case INPUT_SELECT_109 -> "Base109.cfg";
                case INPUT_SELECT_196 -> "Base196.cfg";
                default -> "Base4.cfg";
            };
            createCfgFromBase(baseFile, deviceName, vendorId, productId, this);
        } else {
            Log.i("RetroActivityFuture", "Autoconfiguração cancelada ou sem input");
            Toast.makeText(this, "Autoconfiguração cancelada ou sem input", Toast.LENGTH_SHORT).show();
        }

        latch = null;
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
