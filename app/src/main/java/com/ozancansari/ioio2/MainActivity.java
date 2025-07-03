package com.ozancansari.ioio2;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.Arrays;

import ioio.lib.api.AnalogInput;
import ioio.lib.api.DigitalOutput;
import ioio.lib.api.DigitalInput;
import ioio.lib.api.TwiMaster;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.android.AbstractIOIOActivity;

/**
 * Modern ana sayfa - Sensör verilerini ve sistem durumunu gösterir
 * Android 4+ uyumlu, IOIO doğrudan Bluetooth bağlantısı ile
 */
public class MainActivity extends AbstractIOIOActivity {

    // UI elemanları
    private TextView tvSicaklik, tvNem, tvBasinc, tvDate, tvTime, tvTimer;
    private TextView tvTolerans, tvTolerans1, tvTolerans2;
    private TextView tvMax, tvMin, tvNemMax, tvNemMin, tvBasincMax, tvBasincMin;
    private TextView tvGaz, tvSistem, tvBaglantiDurum;
    private TextView topPercentage, bottomPercentage, lelMain;
    private TextView tvGucKaynagi; // Güç kaynağı ADC değeri
    private ImageButton closeButton, fanButton, lelIcon, o2Button;
    private LinearLayout part1Layout, part2Layout, part3Layout, part4Layout, part5Layout;
    private Button btnBaslat, btnDedektorler, btnRapor;
    
    // Bluetooth bağlantı yönetimi
    private BluetoothAdapter bluetoothAdapter;
    private Handler connectionCheckHandler;
    private Runnable connectionCheckRunnable;
    private boolean isIOIOConnected = false;
    
    // IOIO Thread referansı
    private MainIOIOThread currentThread;
    
    // BME280 Sensör değişkenleri - Android 4 uyumlu
    private BME280Sensor bme280Sensor;
    private float currentTemperature = 25.0f;
    private float currentHumidity = 60.0f;
    private float currentPressure = 1013.25f;
    private boolean sensorDataAvailable = false;
    
    // Veri değişkenleri
    private Handler uiHandler;
    private Runnable updateTimer;
    private int timerSeconds = 0;
    private Random random = new Random();
    private boolean isTimerRunning = false;
    private SharedPreferences preferences;
    
    // IOIO ve ADC durum yönetimi için enum
    private enum IOIOState {
        DISCONNECTED,      // Bağlantı yok
        CONNECTING,        // Bağlanıyor
        INITIALIZING,      // ADC başlatılıyor
        READY,            // Hazır
        READING,          // Okuma yapılıyor
        ERROR,            // Hata durumu
        RECOVERY          // Kurtarma modu
    }
    
    // ADC okuma modu
    private enum ReadingMode {
        BATCH,      // Toplu okuma (5 örnek)
        SINGLE      // Tek okuma
    }
    
    // IOIO durum değişkenleri
    private IOIOState currentState = IOIOState.DISCONNECTED;
    private ReadingMode readingMode = ReadingMode.BATCH;
    private long stateEnteredTime = 0;
    private int recoveryAttempts = 0;
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    
    // ADC okuma parametreleri
    private static final int BATCH_SIZE = 5;
    private static final long STATE_TIMEOUT = 10000; // 10 sn
    private static final long MIN_STATE_TIME = 2000; // 2 sn
    private static final long BATCH_INTERVAL = 5000; // 5 sn
    private static final long SINGLE_INTERVAL = 2000; // 2 sn
    private long lastBatchTime = 0;
    private float[] batchBuffer = new float[BATCH_SIZE];
    private int batchIndex = 0;
    
    // Son başarılı ADC verileri
    private float lastValidAdcValue = 0.5f;
    private float lastValidLelValue = 30.0f;
    private String lastValidGucKaynagiText = "Bekleniyor...";
    private long lastValidReadTime = 0;
    private boolean hasValidData = false;
    
    // Android 4 stabilite için eklenen değişkenler
    private static final long MIN_READ_INTERVAL = 2000; // Minimum okuma aralığı (2 saniye)
    private long lastReadAttempt = 0;
    private boolean isReadingADC = false; // ADC okuma kilidi

    // Bluetooth durumu receiver'ı - Minimal müdahale
    private BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                // Sadece kritik durumlarda müdahale et
                if (state == BluetoothAdapter.STATE_OFF) {
                    isIOIOConnected = false;
                    updateConnectionStatus("Bluetooth kapalı", false);
                }
            }
            // ACL_CONNECTED ve ACL_DISCONNECTED eventlerini kaldırdık
            // Bu eventler çok sık tetikleniyor ve bağlantıyı bozuyor
        }
   };

    @Override
    protected boolean shouldWaitForConnect() {
        return false; // Hızlı başlatma için false, bağlantı kontrolü kendimiz yapacağız
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        preferences = getSharedPreferences("IOIO_PREFS", MODE_PRIVATE);
        
        initializeBluetooth();
        initializeViews();
        setupEventListeners();
        startPeriodicUpdates();
        startConnectionMonitoring(); // Sadece başlangıç kontrolü
    }

    @Override
    protected IOIOThread createIOIOThread() {
        currentThread = new MainIOIOThread();
        return currentThread;
    }
    
    /**
     * Bluetooth adaptörünü başlatır ve durumu kontrol eder
     */
    private void initializeBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        
        if (bluetoothAdapter == null) {
            updateConnectionStatus("Bluetooth desteklenmiyor", false);
            return;
        }
        
        // Bluetooth receiver'ı kaydet - Sadece kritik durumlar
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        // ACL_CONNECTED ve ACL_DISCONNECTED kaldırıldı - stabilite için
        registerReceiver(bluetoothReceiver, filter);
        
        checkBluetoothStatus();
    }

    /**
     * Bluetooth durumunu kontrol eder
     */
    private void checkBluetoothStatus() {
        if (bluetoothAdapter == null) {
            updateConnectionStatus("Bluetooth yok", false);
            return;
        }
        
        if (!bluetoothAdapter.isEnabled()) {
            updateConnectionStatus("Bluetooth kapalı", false);
            showBluetoothEnableDialog();
        } else {
            updateConnectionStatus("Bluetooth açık - IOIO hazır", true);
        }
    }

    /**
     * Bağlantı durumunu UI'da gösterir
     */
    private void updateConnectionStatus(String message, boolean isConnected) {
        if (tvBaglantiDurum != null) {
            // BME-280 durumu da ekle
            String fullMessage = message;
            if (bme280Sensor != null && isConnected) {
                fullMessage += bme280Sensor.isSimulating() ? " (Sim)" : " (Gerçek)";
            }
            
            tvBaglantiDurum.setText(fullMessage);
            tvBaglantiDurum.setTextColor(isConnected ? 
                getResources().getColor(android.R.color.holo_green_dark) : 
                getResources().getColor(android.R.color.holo_red_dark));
        }
    }

    /**
     * Bluetooth açma dialog'u gösterir
     */
    private void showBluetoothEnableDialog() {
        Toast.makeText(this, "IOIO kartı için Bluetooth'u açınız", Toast.LENGTH_LONG).show();
        
        // Bluetooth açma intent'i gönder
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableBtIntent, 1001);
    }

    /**
     * IOIO bağlantı uyarısı gösterir
     */
    private void showIOIOConnectionDialog() {
        Toast.makeText(this, "IOIO kartı bağlantısı kuruluyor...", Toast.LENGTH_SHORT).show();
    }

    /**
     * Periyodik bağlantı kontrolü başlatır - Minimal müdahale
     */
    private void startConnectionMonitoring() {
        // Periyodik kontrol iptal edildi - bağlantı stabilitesi için
        // Sadece başlangıçta bir kez kontrol
        connectionCheckHandler = new Handler();
        connectionCheckHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                    updateConnectionStatus("Bluetooth hazır - IOIO bağlanıyor...", false);
                } else {
                    updateConnectionStatus("Bluetooth kapalı", false);
                }
            }
        }, 2000); // Sadece 2 saniye sonra bir kez
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
        tvGucKaynagi = (TextView) findViewById(R.id.tvGucKaynagi);
        
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
        o2Button = (ImageButton) findViewById(R.id.o2Button);
        
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
        
        // Güç kaynağı başlangıç değeri
        if (tvGucKaynagi != null) {
            tvGucKaynagi.setText("Bekleniyor...");
        }
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

        // O2 butonu (şimdilik işlevsiz)
        o2Button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Şimdilik boş - gelecekte O2 kontrol işlevi eklenecek
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
     * LEL değerini SharedPreferences'den yükler
     */
    private void loadLelValue() {
        // Önceki oturumdan son başarılı verileri yükle
        lastValidAdcValue = preferences.getFloat("last_valid_adc", 0.5f);
        lastValidLelValue = preferences.getFloat("last_valid_lel", 30.0f);
        lastValidGucKaynagiText = preferences.getString("last_valid_guc", "Bekleniyor...");
        lastValidReadTime = preferences.getLong("last_valid_time", 0);
        hasValidData = preferences.getBoolean("has_valid_data", false);
        
        // LEL değeri artık dinamik olarak ADC'ye göre hesaplanıyor
        // Başlangıç değeri olarak son bilinen veriyi göster
        if (lelMain != null) {
            if (hasValidData) {
                lelMain.setText(String.format("LEL: %.1f%% (önceki oturum)", lastValidLelValue));
                // Son bilinen değere göre renklendirme
                final float TOP_PERCENT = 25.0f;
                final float BOTTOM_PERCENT = 5.0f;
                
                if (lastValidLelValue < BOTTOM_PERCENT) {
                    lelMain.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
                } else if (lastValidLelValue > TOP_PERCENT) {
                    lelMain.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                } else {
                    lelMain.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                }
            } else {
                lelMain.setText("LEL: Bekleniyor...");
                lelMain.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
            }
        }
        
        // Güç kaynağı son bilinen değeri
        if (tvGucKaynagi != null) {
            if (hasValidData) {
                tvGucKaynagi.setText(lastValidGucKaynagiText + " (önceki oturum)");
            } else {
                tvGucKaynagi.setText("Bekleniyor...");
            }
        }
        
        // Başlangıç yüzde değerleri
        if (topPercentage != null) {
            topPercentage.setText("25.0%");
            topPercentage.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        }
        if (bottomPercentage != null) {
            bottomPercentage.setText("5.0%");
            bottomPercentage.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
        }
    }
    
    /**
     * Son başarılı verileri SharedPreferences'e kaydeder
     */
    private void saveLastValidData() {
        if (hasValidData) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putFloat("last_valid_adc", lastValidAdcValue);
            editor.putFloat("last_valid_lel", lastValidLelValue);
            editor.putString("last_valid_guc", lastValidGucKaynagiText);
            editor.putLong("last_valid_time", lastValidReadTime);
            editor.putBoolean("has_valid_data", hasValidData);
            editor.apply();
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
        saveLastValidData();
        
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
        
        // Son başarılı verileri kaydet
        saveLastValidData();
        
        if (uiHandler != null && updateTimer != null) {
            uiHandler.removeCallbacks(updateTimer);
        }
        
        // Bluetooth receiver'ı kaldır
        if (bluetoothReceiver != null) {
            try {
                unregisterReceiver(bluetoothReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver zaten kaldırılmış
            }
        }
        
        // Bağlantı kontrolü durdur
        if (connectionCheckHandler != null && connectionCheckRunnable != null) {
            connectionCheckHandler.removeCallbacks(connectionCheckRunnable);
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == 1001) { // Bluetooth enable request
            if (resultCode == RESULT_OK) {
                updateConnectionStatus("Bluetooth açıldı - IOIO hazır", true);
            } else {
                updateConnectionStatus("Bluetooth açılamadı", false);
            }
        }
     }

    /**
     * IOIO Thread sınıfı - State machine pattern ile ADC okuma
     * Public yapıldı - BME280Sensor erişimi için
     */
    public class MainIOIOThread extends IOIOThread {
        private AnalogInput adcPin;
        private DigitalOutput sclPin; // SCL pin - sadece output
        private DigitalOutput csbPin; // CSB pin - I2C modu için HIGH
        // SDA pin dinamik olarak değişecek
        private DigitalOutput sdaOutPin;
        private DigitalInput sdaInPin;
        private boolean sdaIsOutput = true; // SDA pin durumu takibi
        private boolean adcHazir = false;
        private boolean bme280Hazir = false;
        private static final long ADC_SETUP_DELAY = 2000; // 2 saniye
        private static final long ADC_READ_DELAY = 100; // 100ms
        private static final int MAX_ERRORS = 3;
        private int errorCount = 0;
        
        @Override
        protected void setup() throws ConnectionLostException {
            try {
                // IOIO bağlantısı için uzun bekleme
                Thread.sleep(ADC_SETUP_DELAY);
                
                if (ioio_ != null) {
                    // ADC pin setup
                    adcPin = ioio_.openAnalogInput(31);
                    adcHazir = true;
                    
                    // Manuel I2C GPIO pinleri başlat (Android 4 crash-safe)
                    try {
                        Log.i("MainActivity", "BME280 için manuel I2C GPIO başlatılıyor...");
                        
                        // GPIO pinleri aç - I2C modu için
                        // Doğru pin mapping: SDA=4, SCL=5, CSB=6
                        sclPin = ioio_.openDigitalOutput(5, false); // SCL pin 5 (doğru)
                        csbPin = ioio_.openDigitalOutput(6, false); // CSB pin 6 - Başlangıçta LOW (SPI reset için)
                        sdaOutPin = ioio_.openDigitalOutput(4, false); // SDA pin 4 (başlangıçta output)
                        sdaInPin = null; // Henüz açılmadı
                        sdaIsOutput = true;
                        
                        Log.i("MainActivity", "Pin Mapping DOĞRU: SDA=4, SCL=5, CSB=6");
                        
                        Thread.sleep(200);
                        
                        Log.i("MainActivity", "GPIO pinleri hazır, BME280 test ediliyor...");
                        
                        // BME280 sensörünü manuel I2C ile başlat - dinamik pin switching helper
                        bme280Sensor = new BME280Sensor(this); // MainActivity.MainIOIOThread referansı ver
                        
                        // Başlatma işlemi
                        boolean bme280Ok = false;
                        try {
                            bme280Ok = bme280Sensor.initialize();
                        } catch (Exception initEx) {
                            Log.e("MainActivity", "BME280 başlatma iç hatası", initEx);
                            bme280Ok = false;
                        }
                        
                        if (bme280Ok) {
                            bme280Hazir = true;
                            sensorDataAvailable = true;
                            Log.i("MainActivity", "BME280 başarıyla başlatıldı - manuel I2C aktif");
                        } else {
                            throw new Exception("BME280 manuel I2C ile başlatılamadı");
                        }
                        
                    } catch (Exception e) {
                        Log.e("MainActivity", "BME280 manuel I2C hatası", e);
                        
                        // GPIO pinleri temizle
                        if (sclPin != null) {
                            try {
                                sclPin.close();
                            } catch (Exception ex) {
                                Log.e("MainActivity", "SCL pin kapatma hatası", ex);
                            }
                            sclPin = null;
                        }
                        if (csbPin != null) {
                            try {
                                csbPin.close();
                            } catch (Exception ex) {
                                Log.e("MainActivity", "CSB pin kapatma hatası", ex);
                            }
                            csbPin = null;
                        }
                        if (sdaOutPin != null) {
                            try {
                                sdaOutPin.close();
                            } catch (Exception ex) {
                                Log.e("MainActivity", "SDA out pin kapatma hatası", ex);
                            }
                            sdaOutPin = null;
                        }
                        if (sdaInPin != null) {
                            try {
                                sdaInPin.close();
                            } catch (Exception ex) {
                                Log.e("MainActivity", "SDA in pin kapatma hatası", ex);
                            }
                            sdaInPin = null;
                        }
                        
                        bme280Sensor = null;
                        bme280Hazir = false;
                        sensorDataAvailable = false;
                        
                        // Gerçek sensör çalışmadığı için hatayı bildir
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateConnectionStatus("BME280 GPIO hatası - kablolar kontrol edin", false);
                            }
                        });
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String status = "IOIO hazır - ADC aktif";
                            if (bme280Hazir) {
                                status += ", BME280 manuel I2C";
                            } else {
                                status += ", BME280 HATA";
                            }
                            updateConnectionStatus(status, bme280Hazir);
                        }
                    });
                    
                    // İlk ADC okuma ve stabilizasyon
                    float sum = 0;
                    for (int i = 0; i < 3; i++) {
                        sum += adcPin.read();
                        Thread.sleep(ADC_READ_DELAY);
                    }
                    float avgValue = sum / 3;
                    
                    // İlk değeri kaydet
                    lastValidAdcValue = avgValue;
                    lastValidReadTime = System.currentTimeMillis();
                    hasValidData = true;
                }
            } catch (Exception e) {
                Log.e("MainActivity", "IOIO setup hatası", e);
                adcHazir = false;
                bme280Hazir = false;
                sensorDataAvailable = false;
                
                if (adcPin != null) {
                    try {
                        adcPin.close();
                    } catch (Exception ex) {
                        // Ignore
                    }
                    adcPin = null;
                }
                
                // GPIO pinleri temizle
                if (sclPin != null) {
                    try {
                        sclPin.close();
                    } catch (Exception ex) {
                        // Ignore
                    }
                    sclPin = null;
                }
                if (csbPin != null) {
                    try {
                        csbPin.close();
                    } catch (Exception ex) {
                        // Ignore
                    }
                    csbPin = null;
                }
                if (sdaOutPin != null) {
                    try {
                        sdaOutPin.close();
                    } catch (Exception ex) {
                        // Ignore
                    }
                    sdaOutPin = null;
                }
                if (sdaInPin != null) {
                    try {
                        sdaInPin.close();
                    } catch (Exception ex) {
                        // Ignore
                    }
                    sdaInPin = null;
                }
                
                throw new ConnectionLostException(e);
            }
        }
        
        /**
         * SDA pinini output moduna geçir
         */
        public void setSDAOutput() throws ConnectionLostException, InterruptedException {
            if (!sdaIsOutput) {
                // Input modundan output moduna geç
                if (sdaInPin != null) {
                    sdaInPin.close();
                    sdaInPin = null;
                }
                sdaOutPin = ioio_.openDigitalOutput(4, false);
                sdaIsOutput = true;
                Thread.sleep(1);
            }
        }
        
        /**
         * SDA pinini input moduna geçir
         */
        public void setSDAInput() throws ConnectionLostException, InterruptedException {
            if (sdaIsOutput) {
                // Output modundan input moduna geç
                if (sdaOutPin != null) {
                    sdaOutPin.close();
                    sdaOutPin = null;
                }
                sdaInPin = ioio_.openDigitalInput(4, DigitalInput.Spec.Mode.PULL_UP);
                sdaIsOutput = false;
                Thread.sleep(1);
            }
        }
        
        /**
         * SDA pinini yaz (output modunda)
         */
        public void writeSDA(boolean value) throws ConnectionLostException {
            if (sdaIsOutput && sdaOutPin != null) {
                sdaOutPin.write(value);
            }
        }
        
        /**
         * SDA pinini oku (input modunda)
         */
        public boolean readSDA() throws ConnectionLostException, InterruptedException {
            if (!sdaIsOutput && sdaInPin != null) {
                return sdaInPin.read();
            }
            return false;
        }
        
        /**
         * SCL pinini yaz
         */
        public void writeSCL(boolean value) throws ConnectionLostException {
            if (sclPin != null) {
                sclPin.write(value);
            }
        }
        
        /**
         * CSB pinini yaz (SPI/I2C modu kontrolü)
         */
        public void writeCSB(boolean value) throws ConnectionLostException {
            if (csbPin != null) {
                csbPin.write(value);
            }
        }

        @Override
        protected void loop() throws ConnectionLostException, InterruptedException {
            if (!adcHazir || adcPin == null) {
                Thread.sleep(1000);
                return;
            }
            
            try {
                // ADC değerini oku
                float currentValue = adcPin.read();
                
                // Değer validasyonu
                if (currentValue < 0.0f || currentValue > 1.0f) {
                    throw new RuntimeException("Geçersiz ADC değeri: " + currentValue);
                }
                
                // Ani değişim kontrolü
                if (hasValidData) {
                    float change = Math.abs(currentValue - lastValidAdcValue);
                    if (change > 0.5f) { // %30'dan fazla değişim
                        errorCount++;
                        if (errorCount >= MAX_ERRORS) {
                            throw new RuntimeException("Çok fazla ani değişim");
                        }
                        Thread.sleep(1000); // Hata durumunda bekle
                        return;
                    }
                }
                
                // BME280 gerçek sensör verilerini oku (manuel I2C)
                BME280Sensor.SensorData sensorData = null;
                if (bme280Sensor != null && bme280Hazir && sclPin != null) {
                    try {
                        Log.d("MainActivity", "BME280 manuel I2C veri okunuyor...");
                        sensorData = bme280Sensor.readSensorData();
                        
                        if (sensorData != null && !sensorData.isSimulated) {
                            sensorDataAvailable = true;
                            Log.d("MainActivity", String.format("BME280 manuel I2C veri: %.1f°C, %.1f%%, %.1f hPa", 
                                sensorData.temperature, sensorData.humidity, sensorData.pressure));
                        } else {
                            Log.w("MainActivity", "BME280 veri okuma başarısız");
                            sensorDataAvailable = false;
                        }
                        
                    } catch (Exception e) {
                        Log.e("MainActivity", "BME280 manuel I2C okuma hatası", e);
                        sensorDataAvailable = false;
                        
                        // Kritik GPIO hatası varsa sensörü sıfırla
                        if (e.getMessage() != null && e.getMessage().contains("Connection")) {
                            bme280Hazir = false;
                            Log.w("MainActivity", "BME280 GPIO bağlantısı kesildi - yeniden başlatılacak");
                        }
                    }
                } else {
                    // BME280 hazır değil
                    sensorDataAvailable = false;
                    Log.w("MainActivity", "BME280 GPIO hazır değil - sensör bağlantısını kontrol edin");
                }
                
                // Başarılı okuma
                errorCount = 0;
                lastValidAdcValue = currentValue;
                lastValidReadTime = System.currentTimeMillis();
                hasValidData = true;
                
                // Değerleri hesapla
                final float voltage = currentValue * 3.3f;
                final float lelValue = 20.0f + (currentValue * 20.0f);
                lastValidLelValue = lelValue;
                lastValidGucKaynagiText = String.format("%.2fV (%.0f%%)", voltage, currentValue * 100);
                
                // UI güncelle
                final BME280Sensor.SensorData finalSensorData = sensorData;
                final boolean dataOk = sensorDataAvailable;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Güç kaynağı ve LEL güncellemesi
                            if (tvGucKaynagi != null) {
                                tvGucKaynagi.setText(lastValidGucKaynagiText);
                            }
                            
                            if (lelMain != null) {
                                lelMain.setText(String.format("LEL: %.1f%%", lelValue));
                                
                                // LEL renk değişimi
                                if (lelValue < 5.0f) {
                                    lelMain.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
                                } else if (lelValue > 25.0f) {
                                    lelMain.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                                } else {
                                    lelMain.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                                }
                            }
                            
                            // BME280 gerçek sensör verilerini güncelle
                            if (finalSensorData != null && dataOk && !finalSensorData.isSimulated) {
                                updateBME280UI(finalSensorData);
                            } else {
                                // Sensör verisi yok - uyarı göster
                                if (tvSicaklik != null) {
                                    tvSicaklik.setText("Sıcaklık: Manuel I2C hatası");
                                    tvSicaklik.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                                }
                                if (tvNem != null) {
                                    tvNem.setText("Nem: Manuel I2C hatası");
                                    tvNem.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                                }
                                if (tvBasinc != null) {
                                    tvBasinc.setText("Basınç: Manuel I2C hatası");
                                    tvBasinc.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                                }
                            }
                            
                        } catch (Exception e) {
                            Log.e("MainActivity", "UI güncelleme hatası", e);
                        }
                    }
                });
                
                // Android 4 için uygun bekleme
                Thread.sleep(4000); // 4 saniye - manuel I2C için yeterli
                
            } catch (ConnectionLostException e) {
                Log.e("MainActivity", "Bağlantı kaybedildi", e);
                adcHazir = false;
                throw e;
            } catch (Exception e) {
                Log.e("MainActivity", "Loop hatası", e);
                errorCount++;
                if (errorCount >= MAX_ERRORS) {
                    // ADC'yi yeniden başlat
                    adcHazir = false;
                    if (adcPin != null) {
                        try {
                            adcPin.close();
                        } catch (Exception ex) {
                            // Ignore
                        }
                        adcPin = null;
                    }
                    
                    // Yeniden başlatma dene
                    try {
                        Thread.sleep(3000);
                        if (ioio_ != null) {
                            adcPin = ioio_.openAnalogInput(31);
                            adcHazir = true;
                            errorCount = 0;
                        }
                    } catch (Exception ex) {
                        // Yeniden başlatma başarısız
                        Log.e("MainActivity", "ADC yeniden başlatma hatası", ex);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateConnectionStatus("ADC hatası - yeniden bağlanıyor", false);
                            }
                        });
                    }
                }
                
                Thread.sleep(1000);
            }
        }

        @Override
        protected void disconnected() {
            Log.i("MainActivity", "IOIO bağlantısı kesildi");
            adcHazir = false;
            bme280Hazir = false;
            sensorDataAvailable = false;
            
            if (adcPin != null) {
                try {
                    adcPin.close();
                } catch (Exception e) {
                    // Ignore
                }
                adcPin = null;
            }
            
            // GPIO pinleri kapat
            if (sclPin != null) {
                try {
                    sclPin.close();
                } catch (Exception e) {
                    Log.e("MainActivity", "SCL pin kapatma hatası", e);
                }
                sclPin = null;
            }
            if (csbPin != null) {
                try {
                    csbPin.close();
                } catch (Exception e) {
                    Log.e("MainActivity", "CSB pin kapatma hatası", e);
                }
                csbPin = null;
            }
            if (sdaOutPin != null) {
                try {
                    sdaOutPin.close();
                } catch (Exception e) {
                    Log.e("MainActivity", "SDA out pin kapatma hatası", e);
                }
                sdaOutPin = null;
            }
            if (sdaInPin != null) {
                try {
                    sdaInPin.close();
                } catch (Exception e) {
                    Log.e("MainActivity", "SDA in pin kapatma hatası", e);
                }
                sdaInPin = null;
            }
            
            bme280Sensor = null;
            
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateConnectionStatus("IOIO bağlantısı kesildi", false);
                }
            });
        }
    }
    
    /**
     * BME-280 sensör verilerini UI'da günceller
     * Android 4 uyumlu, basit güvenli güncelleme
     */
    private void updateBME280UI(BME280Sensor.SensorData sensorData) {
        if (sensorData == null || !sensorDataAvailable) return;
        
        // Android 4 için basit UI güncelleme, thread kontrolü yok
        try {
            // Sıcaklık güncellemesi
        if (tvSicaklik != null) {
            tvSicaklik.setText(String.format("Sıcaklık: %.1f°C%s", 
                sensorData.temperature, 
                sensorData.isSimulated ? " (Sim)" : ""));
            
            // Renk kodlaması
            if (sensorData.temperature < 18.0f) {
                tvSicaklik.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
            } else if (sensorData.temperature > 35.0f) {
                tvSicaklik.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            } else {
                tvSicaklik.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            }
        }
        
        // Nem güncellemesi
        if (tvNem != null) {
            tvNem.setText(String.format("Nem: %.1f%%%s", 
                sensorData.humidity,
                sensorData.isSimulated ? " (Sim)" : ""));
            
            // Renk kodlaması
            if (sensorData.humidity < 30.0f) {
                tvNem.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
            } else if (sensorData.humidity > 80.0f) {
                tvNem.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
            } else {
                tvNem.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            }
        }
        
        // Basınç güncellemesi  
        if (tvBasinc != null) {
            tvBasinc.setText(String.format("Basınç: %.1f hPa%s", 
                sensorData.pressure,
                sensorData.isSimulated ? " (Sim)" : ""));
            
            // Renk kodlaması (normal deniz seviyesi basıncı: 1013.25 hPa)
            if (sensorData.pressure < 980.0f) {
                tvBasinc.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
            } else if (sensorData.pressure > 1050.0f) {
                tvBasinc.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            } else {
                tvBasinc.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            }
        }
        
        // Min/Max değerleri güncelle (basitleştirilmiş)
        if (tvMax != null) {
            tvMax.setText(String.format("%.0f°C", sensorData.temperature + 2));
        }
        if (tvMin != null) {
            tvMin.setText(String.format("%.0f°C", sensorData.temperature - 2));
        }
        if (tvNemMax != null) {
            tvNemMax.setText(String.format("%.0f%%", sensorData.humidity + 5));
        }
        if (tvNemMin != null) {
            tvNemMin.setText(String.format("%.0f%%", sensorData.humidity - 5));
        }
        if (tvBasincMax != null) {
            tvBasincMax.setText(String.format("%.0f hPa", sensorData.pressure + 10));
        }
        if (tvBasincMin != null) {
            tvBasincMin.setText(String.format("%.0f hPa", sensorData.pressure - 10));
        }
        } catch (Exception e) {
            // Android 4 UI güncelleme hatası durumunda sessizce geç
        }
    }
    
    /**
     * UI güncelleme yardımcı metodu
     */
    private void updateUI(float voltage, float lelValue, String gucKaynagiText) {
        if (tvGucKaynagi != null) {
            tvGucKaynagi.setText(gucKaynagiText);
        }
        
        if (lelMain != null) {
            lelMain.setText(String.format("LEL: %.1f%%", lelValue));
            
            // LEL renk değişimi
            if (lelValue < 5.0f) {
                lelMain.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
            } else if (lelValue > 25.0f) {
                lelMain.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            } else {
                lelMain.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            }
        }
        
        // Yüzde değerleri
        if (topPercentage != null) {
            topPercentage.setText("25.0%");
            topPercentage.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        }
        if (bottomPercentage != null) {
            bottomPercentage.setText("5.0%");
            bottomPercentage.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
        }
    }
} 