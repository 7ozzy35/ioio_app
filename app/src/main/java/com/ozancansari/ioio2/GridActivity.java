package com.ozancansari.ioio2;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public class GridActivity extends Activity {
    
    private TextView tvLelValue;
    private List<TextView> gridCells;
    private List<Boolean> cellStates; // true = kırmızı, false = normal
    private SharedPreferences preferences;
    private Button btnClose;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.grid_activity);
        
        preferences = getSharedPreferences("IOIO_PREFS", MODE_PRIVATE);
        
        initViews();
        loadLelValue();
        setupGridCells();
    }
    
    private void initViews() {
        tvLelValue = findViewById(R.id.tvLelValue);
        btnClose = findViewById(R.id.btnClose);
        
        // Grid hücrelerini listeye ekle
        gridCells = new ArrayList<>();
        cellStates = new ArrayList<>();
        
        // Grid hücrelerini bul ve listeye ekle (cell_0 dan cell_34 e kadar)
        for (int i = 0; i < 35; i++) { // 35 hücre (0-34)
            String cellId = "cell_" + i;
            int resourceId = getResources().getIdentifier(cellId, "id", getPackageName());
            if (resourceId != 0) {
                TextView cell = findViewById(resourceId);
                if (cell != null) {
                    gridCells.add(cell);
                    cellStates.add(false); // Başlangıçta tüm hücreler normal
                    Log.d("GridActivity", "Grid cell added: " + cellId);
                } else {
                    Log.w("GridActivity", "Grid cell not found: " + cellId);
                }
            } else {
                Log.w("GridActivity", "Resource ID not found for: " + cellId);
            }
        }
        
        Log.d("GridActivity", "Total grid cells found: " + gridCells.size());
        
        // Close butonu
        if (btnClose != null) {
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onBackPressed();
                }
            });
        }
    }
    
    private void loadLelValue() {
        // MainActivity'den LEL değerini al
        String lelValue = preferences.getString("lel_value", "35.0%");
        if (tvLelValue != null) {
            tvLelValue.setText("LEL: " + lelValue);
        }
    }
    
    private void setupGridCells() {
        for (int i = 0; i < gridCells.size(); i++) {
            final int index = i;
            TextView cell = gridCells.get(i);
            
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleCellState(index);
                }
            });
        }
    }
    
    private void toggleCellState(int index) {
        if (index >= 0 && index < gridCells.size()) {
            TextView cell = gridCells.get(index);
            boolean currentState = cellStates.get(index);
            
            if (currentState) {
                // Kırmızıdan normale döndür
                try {
                    Drawable normalBg = getResources().getDrawable(R.drawable.bg_grid_normal);
                    cell.setBackground(normalBg);
                } catch (Exception e) {
                    // Eğer drawable bulunamazsa varsayılan renk kullan
                    cell.setBackgroundColor(Color.LTGRAY);
                }
                cellStates.set(index, false);
            } else {
                // Normale'den kırmızıya çevir
                cell.setBackgroundColor(Color.RED);
                cellStates.set(index, true);
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        loadLelValue(); // Sayfa görünür olduğunda LEL değerini güncelle
    }
    
    @Override
    public void onBackPressed() {
        // Geri tuşuna basıldığında MainActivity'e dön
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
} 