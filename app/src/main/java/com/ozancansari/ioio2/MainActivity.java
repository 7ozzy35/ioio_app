package com.ozancansari.ioio2;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Modern ana sayfa - Sensör verilerini ve sistem durumunu gösterir
 * Android 4+ uyumlu, IOIO işlemleri arka planda çalışacak
 */
public class MainActivity extends Activity {

    // UI elemanları
    private TextView tvSicaklik, tvNem, tvBasinc, tvDate, tvTime, tvTimer;
    private TextView tvTolerans, tvTolerans1, tvTolerans2;
    private TextView tvMax, tvMin, tvNemMax, tvNemMin, tvBasincMax, tvBasincMin;
    private TextView tvGaz, tvSistem, tvBaglantiDurum;
    private TextView topPercentage, bottomPercentage, lelMain;
    private ImageButton closeButton, fanButton, lelIcon;
    private LinearLayout part1Layout, part2Layout, part3Layout, part4Layout, part5Layout;
    private Button btnBaslat, btnDedektorler, btnRapor;
    
    // Veri değişkenleri
    private Handler uiHandler;
    private Runnable updateTimer;
    private int timerSeconds = 0;
    private Random random = new Random();
    private boolean isTimerRunning = false;
    private SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        preferences = getSharedPreferences("IOIO_PREFS", MODE_PRIVATE);
        
        initializeViews();
        setupEventListeners();
        startPeriodicUpdates();
    }
    
    /**
     * UI elemanlarını başlatır
     */
    private void initializeViews() {
        // Sensör değer TextViews
        tvSicaklik = (TextView) findViewById(R.id.tvSicaklik);
        tvNem = (TextView) findViewById(R.id.tvNem);
        tvBasinc = (TextView) findViewById(R.id.tvBasinc);
        tvGaz = (TextView) findViewById(R.id.tvGaz);
        tvSistem = (TextView) findViewById(R.id.tvSistem);
        tvBaglantiDurum = (TextView) findViewById(R.id.tvBaglantiDurum);
        
        // Tolerans TextViews
        tvTolerans = (TextView) findViewById(R.id.tvTolerans);
        tvTolerans1 = (TextView) findViewById(R.id.tvTolerans1);
        tvTolerans2 = (TextView) findViewById(R.id.tvTolerans2);
        
        // Min/Max değerler
        tvMax = (TextView) findViewById(R.id.tvMax);
        tvMin = (TextView) findViewById(R.id.tvMin);
        tvNemMax = (TextView) findViewById(R.id.tvNemMax);
        tvNemMin = (TextView) findViewById(R.id.tvNemMin);
        tvBasincMax = (TextView) findViewById(R.id.tvBasincMax);
        tvBasincMin = (TextView) findViewById(R.id.tvBasincMin);
        
        // Tarih/saat/timer
        tvDate = (TextView) findViewById(R.id.tvDate);
        tvTime = (TextView) findViewById(R.id.tvTime);
        tvTimer = (TextView) findViewById(R.id.tvTimer);
        
        // Yüzde değerleri ve LEL
        topPercentage = (TextView) findViewById(R.id.topPercentage);
        bottomPercentage = (TextView) findViewById(R.id.bottomPercentage);
        lelMain = (TextView) findViewById(R.id.lelMain);
        
        // Butonlar
        closeButton = (ImageButton) findViewById(R.id.imageButton);
        fanButton = (ImageButton) findViewById(R.id.fanButton);
        lelIcon = (ImageButton) findViewById(R.id.btnLelIcon);
        
        // Layout panelleri
        part1Layout = (LinearLayout) findViewById(R.id.part1Layout);
        part2Layout = (LinearLayout) findViewById(R.id.part2Layout);
        part3Layout = (LinearLayout) findViewById(R.id.part3Layout);
        part4Layout = (LinearLayout) findViewById(R.id.part4Layout);
        part5Layout = (LinearLayout) findViewById(R.id.part5Layout);
        
        // Alt butonlar
        btnBaslat = (Button) findViewById(R.id.btnBaslat);
        btnDedektorler = (Button) findViewById(R.id.btnDedektorler);
        btnRapor = (Button) findViewById(R.id.btnRapor);
        
        // Handler'ı başlat
        uiHandler = new Handler();
        
        // İlk değerleri ayarla
        updateDateTime();
        loadLelValue();
        // updateSensorData();
    }
    
    /**
     * Event listener'ları ayarlar - Android 4 uyumlu
     */
    private void setupEventListeners() {
        // IOIO LED kontrol sayfasına git
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openIOIOControl();
            }
        });
        
        // Sensör panellerine tıklama
        part1Layout.setOnClickListener(new View.OnClickListener() {
                @Override
            public void onClick(View v) {
                showSensorDetail("Sıcaklık");
            }
        });
        
        part2Layout.setOnClickListener(new View.OnClickListener() {
                @Override
            public void onClick(View v) {
                showSensorDetail("Nem");
            }
        });
        
        part3Layout.setOnClickListener(new View.OnClickListener() {
                @Override
            public void onClick(View v) {
                showSensorDetail("Basınç");
            }
        });
        
        part4Layout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                showSensorDetail("Gaz");
                    }
                });
        
        part5Layout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSensorDetail("Sistem");
            }
        });

        // Fan butonu
                fanButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                toggleFan();
            }
        });

        // LEL ikonu
        lelIcon.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                showLelDetail();
            }
        });
        
        // Alt butonlar
                btnBaslat.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                toggleTimer();
            }
        });
        
        btnDedektorler.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                openDetectors();
            }
        });
        
        btnRapor.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                openReport();
                    }
                });
            }

    /**
     * Periyodik güncellemeleri başlatır (sadece tarih/saat)
     */
    private void startPeriodicUpdates() {
        updateTimer = new Runnable() {
                    @Override
            public void run() {
                updateDateTime();
                if (isTimerRunning) {
                    updateTimerDisplay();
                }
                // updateSensorData();
                uiHandler.postDelayed(updateTimer, 1000); // Her saniye güncelle
            }
        };
        uiHandler.post(updateTimer);
    }
    
    /**
     * Tarih ve saat günceller
     */
    private void updateDateTime() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        Date now = new Date();
        
        tvDate.setText(dateFormat.format(now));
        tvTime.setText(timeFormat.format(now));
    }
    
    /**
     * Timer display günceller
     */
    private void updateTimerDisplay() {
        timerSeconds++;
        int minutes = timerSeconds / 60;
        int seconds = timerSeconds % 60;
        tvTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
    }
    
    /**
     * Sensör verilerini günceller (simülasyon)
     */
    // private void updateSensorData() {
    //     // Sıcaklık (35-42°C arası)
    //     float sicaklik = 37.0f + (random.nextFloat() * 5.0f);
    //     tvSicaklik.setText(String.format(Locale.getDefault(), "Sıcaklık: %.1f°C", sicaklik));
        
    //     // Nem (25-35% arası)
    //     float nem = 28.0f + (random.nextFloat() * 7.0f);
    //     tvNem.setText(String.format(Locale.getDefault(), "Nem: %.1f%%", nem));
        
    //     // Basınç (115-122 kPa arası)
    //     float basinc = 118.0f + (random.nextFloat() * 4.0f);
    //     tvBasinc.setText(String.format(Locale.getDefault(), "Basınç: %.1f kPa", basinc));
        
    //     // Gaz durumu
    //     String[] gazDurumlari = {"Normal", "Uyarı", "Güvenli"};
    //     tvGaz.setText("Gaz: " + gazDurumlari[random.nextInt(gazDurumlari.length)]);
        
    //     // Sistem durumu
    //     tvSistem.setText("Sistem: Aktif");
    //     tvBaglantiDurum.setText("IOIO: Hazır");
        
    //     // Yüzde değerleri
    //     float topPercent = 18.0f + (random.nextFloat() * 4.0f);
    //     float bottomPercent = 2.0f + (random.nextFloat() * 2.0f);
    //     topPercentage.setText(String.format(Locale.getDefault(), "%.1f%%", topPercent));
    //     bottomPercentage.setText(String.format(Locale.getDefault(), "%.1f%%", bottomPercent));
        
    //     // LEL değeri
    //     float lelValue = 33.0f + (random.nextFloat() * 4.0f);
    //     lelMain.setText(String.format(Locale.getDefault(), "LEL: %.1f%%", lelValue));
    // }
    
    /**
     * IOIO kontrol sayfasını açar
     */
    private void openIOIOControl() {
        Intent intent = new Intent(this, LEDKontrolActivity.class);
        startActivity(intent);
    }
    
    /**
     * Sensör detay bilgisi gösterir
     */
    private void showSensorDetail(String sensorType) {
        // Burada gelecekte detay popup'ı veya yeni sayfa açılabilir
        // Şimdilik basit bir işlem - detay sayfası geliştirilebilir
    }
    
    /**
     * Fan durumunu değiştirir
     */
    private void toggleFan() {
        // Fan kontrol işlemi burada yapılacak
        // IOIO entegrasyonu sonrası eklenecek
    }
    
    /**
     * LEL detay bilgisini gösterir
     */
    private void showLelDetail() {
        // LEL değer detayları burada gösterilecek
    }
    
    /**
     * LEL değerini SharedPreferences'e kaydeder
     */
    private void saveLelValue() {
        if (lelMain != null) {
            String lelText = lelMain.getText().toString();
            // "LEL: 35.0%" formatından sadece "35.0%" kısmını al
            if (lelText.contains(":")) {
                String lelValue = lelText.substring(lelText.indexOf(":") + 1).trim();
                SharedPreferences.Editor editor = preferences.edit();
                editor.putString("lel_value", lelValue);
                editor.apply();
            }
        }
    }
    
    /**
     * LEL değerini SharedPreferences'den yükler
     */
    private void loadLelValue() {
        String lelValue = preferences.getString("lel_value", "35.0%");
        if (lelMain != null) {
            lelMain.setText("LEL: " + lelValue);
        }
    }
    
    /**
     * Timer başlat/durdur işlemi
     */
    private void toggleTimer() {
        if (!isTimerRunning) {
            // Timer başlat
            isTimerRunning = true;
            timerSeconds = 0; // Sıfırla
            btnBaslat.setText("DURDUR");
            btnBaslat.setBackgroundResource(R.drawable.button_stop);
            tvTimer.setText("00:00");
        } else {
            // Timer durdur
            isTimerRunning = false;
            btnBaslat.setText("BAŞLAT");
            btnBaslat.setBackgroundResource(R.drawable.button_start);
        }
    }
    
    /**
     * Dedektörler sayfasını açar
     */
    private void openDetectors() {
        // LEL değerini SharedPreferences'e kaydet
        saveLelValue();
        
        // GridActivity'yi aç
        Intent intent = new Intent(this, GridActivity.class);
        startActivity(intent);
    }
    
    /**
     * Rapor sayfasını açar
     */
    private void openReport() {
        // Gelecekte rapor sayfası açılacak
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadLelValue(); // GridActivity'den döndüğünde LEL değerini güncelle
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (uiHandler != null && updateTimer != null) {
            uiHandler.removeCallbacks(updateTimer);
        }
    }
} 