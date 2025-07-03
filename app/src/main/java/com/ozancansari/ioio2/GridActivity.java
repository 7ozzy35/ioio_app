package com.ozancansari.ioio2;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class GridActivity extends Activity {
    
    private TextView tvLelValue;
    private Button btnClose;
    private Handler updateHandler;
    private SharedPreferences preferences;
    private static final long UPDATE_INTERVAL = 1000; // 1 saniye
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.grid_activity);
        
        // SharedPreferences başlat
        preferences = getSharedPreferences("IOIO_PREFS", MODE_PRIVATE);
        
        // UI elemanlarını bağla
        tvLelValue = findViewById(R.id.tvLelValue);
        btnClose = findViewById(R.id.btnClose);
        
        // Kapat butonu
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                finish();
            }
        });

        // Handler başlat
        updateHandler = new Handler();
        startLelUpdates();
    }

    private void startLelUpdates() {
        updateHandler.post(new Runnable() {
                @Override
            public void run() {
                updateLelValue();
                updateHandler.postDelayed(this, UPDATE_INTERVAL);
                }
            });
        }

    private void updateLelValue() {
        float lelValue = preferences.getFloat("last_valid_lel", 30.0f);
        boolean hasValidData = preferences.getBoolean("has_valid_data", false);
        
        if (hasValidData) {
            tvLelValue.setText(String.format("%.1f%%", lelValue));
            
            // LEL değerine göre renk değişimi
            if (lelValue < 5.0f) {
                tvLelValue.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
            } else if (lelValue > 25.0f) {
                tvLelValue.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            } else {
                tvLelValue.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            }
        } else {
            tvLelValue.setText("Bekleniyor...");
            tvLelValue.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateLelValue();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (updateHandler != null) {
            updateHandler.removeCallbacksAndMessages(null);
        }
    }
} 