package com.retroarch.browser.input;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.widget.Toast;

public class GamepadActivity extends Activity {

    private Integer capturedSelect = null;
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showInstructionDialog();
    }

    private void showInstructionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Configuração do Controle")
                .setMessage("Pressione o botão SELECT (Options) para autoconfigurar o controle")
                .setCancelable(false)
                .setPositiveButton("OK", null)
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (capturedSelect == null) {
            if (keyCode == 4 || keyCode == 109 || keyCode == 196) {
                capturedSelect = keyCode;

                // Retorna o código detectado para o C
                getIntent().putExtra("select_btn", capturedSelect);
                setResult(RESULT_OK, getIntent());

                // Mostra diálogo de sucesso
                new AlertDialog.Builder(this)
                        .setTitle("Sucesso")
                        .setMessage("Botão SELECT (Options) detectado: " + keyCode)
                        .setCancelable(false)
                        .show();

                // Fecha após 2 segundos
                handler.postDelayed(this::finish, 2000);

            } else {
                // Aviso de erro
                Toast.makeText(this, "Pressione APENAS o botão SELECT (Options) para autoconfigurar o controle!", Toast.LENGTH_SHORT).show();
            }
        }
        return true;
    }
} 
