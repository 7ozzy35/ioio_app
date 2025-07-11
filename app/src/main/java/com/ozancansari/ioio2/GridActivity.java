package com.ozancansari.ioio2;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import ioio.lib.api.AnalogInput;
import ioio.lib.api.DigitalOutput;
import ioio.lib.api.IOIO;
import ioio.lib.api.PwmOutput;
import ioio.lib.api.SpiMaster;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.android.AbstractIOIOActivity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class GridActivity extends AbstractIOIOActivity {
    
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1001;
    private static final int REQUEST_ENABLE_BT = 1002;
    
    // UI elemanları
    private TextView tvLelValue;
    private TextView tvTimer;
    private TextView tvCountdownTimer;
    private TextView tvVoltage;  // Voltaj TextView'ı
    private Button btnStartStop;
    private Button btnClose;
    
    // Timer değişkenleri
    private Handler timerHandler = new Handler();
    private int timerSeconds = 0;
    private boolean isTimerRunning = false;
    private static final String PREF_COUNTDOWN_SECONDS = "countdown_seconds";
    private Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            timerSeconds++;
            updateTimerDisplay();
            timerHandler.postDelayed(this, 1000);
        }
    };
    
    // Geri sayım timer değişkenleri
    private Handler countdownHandler = new Handler();
    private int countdownSeconds = 30; // Varsayılan değer
    private int remainingSeconds = 0;
    private boolean isCountdownRunning = false;
    private Runnable countdownRunnable = new Runnable() {
        @Override
        public void run() {
            if (remainingSeconds > 0) {
                remainingSeconds--;
                updateCountdownDisplay();
                countdownHandler.postDelayed(this, 1000);
            } else {
                stopCountdown();
                onCountdownFinished();
            }
        }
    };
    
    // ADC ve LEL değişkenleri
    private static final int ADC_PIN = 42;
    private static final float MIN_VOLTAGE = 0.6f;  // 0.6V = %0 LEL
    private static final float MID_VOLTAGE = 1.3f;  // 1.3V = %10 LEL
    private static final float MAX_VOLTAGE = 2.0f;  // 2.0V = %20 LEL
    private static final float MIN_LEL = 0.0f;      // Minimum LEL değeri
    private static final float MID_LEL = 10.0f;     // Orta LEL değeri
    private static final float MAX_LEL = 20.0f;     // Maximum LEL değeri
    private static final float MAX_LEL_LIMIT = 20.0f; // Valf kesme limiti (%20)
    
    // LEL renk eşikleri
    private static final float LEL_YELLOW_THRESHOLD = 7.5f;  // %7.5 altı SARI
    private static final float LEL_GREEN_THRESHOLD = 12.5f;  // %7.5 - %12.5 arası YEŞİL, üstü KIRMIZI
    
    // Bluetooth yönetimi
    private BluetoothAdapter bluetoothAdapter;
    private SharedPreferences preferences;
    private Handler reconnectHandler;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final int RECONNECT_DELAY = 3000;
    
    // SPI durumu
    private boolean spiCalisiyorMu = false;
    private Handler spiHandler;
    private static final long SPI_INTERVAL = 2000; // 2 saniye aralıkla SPI
    
    // Thread referansı
    private IOIOSpiThread currentThread_;
    
    // PWM ve Valf pin tanımları
    private static final int PWM_PIN = 14;
    private static final int PWM_FREQ = 1000; // 1000 Hz
    private static final float PWM_DUTY = 0.5f; // %50 duty cycle
    private static final int VALF_PIN = 10;
    
    // Valf kontrol değişkenleri
    private boolean isValfControlActive = false;
    private Handler valfHandler = new Handler();
    private Runnable valfRunnable;
    
    // Bluetooth durum receiver'ı
    private BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                handleBluetoothStateChange(state);
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                handleBluetoothDeviceConnected(device);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                handleBluetoothDeviceDisconnected(device);
            }
        }
    };
    
    private TextView[] detektorTextViews;
    private Map<TextView, Boolean> grayDetectors = new HashMap<>(); // Gri olan detektörleri tut
    private Set<TextView> greenDetectors = new HashSet<>(); // Yeşil olan detektörleri tut
    private boolean isHeatTimerRunning = false;
    private Map<TextView, Float> detectorLelValues = new HashMap<>(); // Detektörlerin LEL değerlerini sakla
    
    private volatile float lastVoltage = 0.0f;
    private volatile float lastLelValue = 0.0f;
    
    private Map<TextView, Integer> grayConfirmCount = new HashMap<>(); // Gri duruma geçiş sayısını tut
    private static final int REQUIRED_GRAY_COUNT = 3; // Gri olarak kabul edilmek için gereken minimum tetiklenme sayısı
    
    @Override
    protected boolean shouldWaitForConnect() {
        return true;
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.grid_activity);
        
            // Detektör TextView'larını başlat
            initializeDetektorViews();
            
        preferences = getSharedPreferences("IOIO_PREFS", MODE_PRIVATE);
            // Kaydedilmiş geri sayım süresini yükle
            countdownSeconds = preferences.getInt(PREF_COUNTDOWN_SECONDS, 30);
            
            reconnectHandler = new Handler();
            spiHandler = new Handler();
            
            initializeViews();
            setupEventListeners();
            initializeBluetooth();
            startSpiOperations();
            
        } catch (Exception e) {
            Log.e("GridActivity", "onCreate hatası: " + e.getMessage());
            finish();
        }
        }
        
    private void initializeViews() {
        tvLelValue = findViewById(R.id.tvLelValue);
        tvTimer = findViewById(R.id.tvTimer);
        tvCountdownTimer = findViewById(R.id.tvCountdownTimer);
       // tvVoltage = findViewById(R.id.tvVoltage);  // Voltaj TextView'ını initialize et
        
        btnStartStop = findViewById(R.id.btnStartStop);
        btnClose = findViewById(R.id.btnClose);
    }
    
    private void setupEventListeners() {
        tvCountdownTimer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isTimerRunning) {
                    showCountdownDialog();
            }
            }
        });
        
        btnStartStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTimer();
            }
        });
        
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                finish();
            }
        });
    }
    
    private void showCountdownDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_countdown_timer, null);
        
        final SeekBar seekBar = dialogView.findViewById(R.id.seekBarTimer);
        final TextView tvSelectedTime = dialogView.findViewById(R.id.tvSelectedTime);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);
        Button btnOk = dialogView.findViewById(R.id.btnOk);
        
        // SeekBar'ı 10-90 saniye aralığına ayarla
        seekBar.setMax(80); // 90-10=80
        seekBar.setProgress(countdownSeconds - 10); // Mevcut değeri göster
        tvSelectedTime.setText(countdownSeconds + " saniye");
        
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int seconds = progress + 10; // 10-90 arası değer
                tvSelectedTime.setText(seconds + " saniye");
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        final AlertDialog dialog = builder.setView(dialogView).create();
        
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        
        btnOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                countdownSeconds = seekBar.getProgress() + 10;
                // Seçilen süreyi kaydet
                preferences.edit().putInt(PREF_COUNTDOWN_SECONDS, countdownSeconds).apply();
                updateCountdownDisplay(); // Yeni süreyi göster
                dialog.dismiss();
                Toast.makeText(GridActivity.this, 
                    "Geri sayım süresi kaydedildi: " + countdownSeconds + " saniye", 
                    Toast.LENGTH_SHORT).show();
            }
        });
        
        dialog.show();
    }
    
    private void startCountdown() {
        if (!isCountdownRunning) {
            isCountdownRunning = true;
            startHeatTimer(); // Heat Timer'ı başlat
            countdownHandler.postDelayed(countdownRunnable, 1000);
        }
    }
    
    private void stopCountdown() {
        if (isCountdownRunning) {
            isCountdownRunning = false;
            stopHeatTimer(); // Heat Timer'ı durdur
            countdownHandler.removeCallbacks(countdownRunnable);
        }
    }
    
    private void updateCountdownDisplay() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String timeText;
                if (remainingSeconds > 0) {
                    timeText = String.format("%d sn", remainingSeconds);
                } else {
                    timeText = "0 sn";
                }
                tvCountdownTimer.setText(timeText);
            }
        });
    }
    
    private void toggleTimer() {
        if (isTimerRunning) {
            stopTimer();
            stopCountdown();
            ((IOIOSpiThread)currentThread_).stopPWM();
            ((IOIOSpiThread)currentThread_).stopValfControl();
            ((IOIOSpiThread)currentThread_).stopPin12(); // Pin 12'yi kapat
            btnStartStop.setText("BAŞLAT");
            btnStartStop.setBackgroundResource(R.drawable.button_start);
            isTimerRunning = false;
        } else {
            ((IOIOSpiThread)currentThread_).startPWM();
            startTimer();
            btnStartStop.setText("DURDUR");
            btnStartStop.setBackgroundResource(R.drawable.button_stop);
        }
    }
    
    private void startTimer() {
        if (!isTimerRunning) {
            isTimerRunning = true;
            remainingSeconds = countdownSeconds;
            updateCountdownDisplay();
            startCountdown(); // Bu Heat Timer'ı başlatacak
            timerHandler.postDelayed(timerRunnable, 1000);
        }
    }
    
    private void stopTimer() {
        if (isTimerRunning) {
            isTimerRunning = false;
            stopCountdown(); // Bu Heat Timer'ı durduracak
            timerHandler.removeCallbacks(timerRunnable);
        }
    }
    
    private void updateTimerDisplay() {
        int minutes = timerSeconds / 60;
        int seconds = timerSeconds % 60;
        tvTimer.setText(String.format("%02d:%02d", minutes, seconds));
    }
    
    /**
     * Voltaj değerinden LEL değerini hesaplar
     * 0.6V - 1.3V arası: %0 - %10 LEL
     * 1.3V - 2.0V arası: %10 - %20 LEL
     */
    private float calculateLEL(float voltage) {
        // Voltaj değerini sınırla
        voltage = Math.max(MIN_VOLTAGE, Math.min(MAX_VOLTAGE, voltage));
        
        float lelValue;

        // 0.6V altında negatif LEL değeri göster
        if (voltage < MIN_VOLTAGE) {
            float diff = MIN_VOLTAGE - voltage;
            lelValue = -(diff * 10); // Her 0.1V için -%1 LEL
        }
        // 0.6V - 1.3V arası için %0-%10 LEL hesaplama
        else if (voltage <= MID_VOLTAGE) {
            float ratio = (voltage - MIN_VOLTAGE) / (MID_VOLTAGE - MIN_VOLTAGE);
            lelValue = MIN_LEL + (ratio * MID_LEL);
        }
        // 1.3V - 2.0V arası için %10-%20 LEL hesaplama
        else {
            float ratio = (voltage - MID_VOLTAGE) / (MAX_VOLTAGE - MID_VOLTAGE);
            lelValue = MID_LEL + (ratio * (MAX_LEL - MID_LEL));
        }
        
        // LEL değerini yuvarla (1 ondalık basamak)
        return Math.round(lelValue * 10.0f) / 10.0f;
    }

    private void updateLELDisplay(final float lelValue, final float voltage) {
        lastVoltage = voltage;
        lastLelValue = lelValue;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String lelText;
                if (lelValue < 0) {
                    lelText = String.format("LEL: %.1f%% (%.3fV)\n⚠️ Sensör Altlimit", lelValue, voltage);
                } else if (voltage > MAX_VOLTAGE) {
                    lelText = String.format("LEL: %.1f%% (%.3fV)\n⚠️ Sensör Üstlimit", lelValue, voltage);
                } else {
                    lelText = String.format("LEL: %.1f%% (%.3fV)", lelValue, voltage);
                }
                tvLelValue.setText(lelText);
                
                // LEL değerine göre metin rengini ayarla
                if (lelValue < 0) {
                    tvLelValue.setTextColor(Color.BLUE);
                } else if (lelValue >= MAX_LEL) {
                    tvLelValue.setTextColor(Color.RED);
                } else {
                    tvLelValue.setTextColor(Color.rgb(51, 51, 51));
                }

                // Heat timer bittiyse LEL kontrolü yap
                if (!isHeatTimerRunning) {
                    checkAndUpdateDetectors(lelValue);
                }
            }
        });
    }
    
    private void checkAndUpdateDetectors(float currentLel) {
        // Gri listedeki tüm detektörleri kontrol et
        Iterator<Map.Entry<TextView, Boolean>> iterator = grayDetectors.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<TextView, Boolean> entry = iterator.next();
            TextView detector = entry.getKey();
            boolean trigger = entry.getValue();
            
            // Eğer mevcut LEL değeri, kaydedilen değeri geçtiyse yeşile çevir
            if (currentLel >= MAX_LEL_LIMIT && isValfControlActive) {
                detector.setBackgroundResource(R.drawable.bg_green_border);
                String lelText = String.format("%.1f%%", currentLel);
                detector.setText(lelText);
                detector.setTextColor(Color.BLACK);
                greenDetectors.add(detector);
                iterator.remove();
                Log.d("GridActivity", "Detektör yeşile döndü! LEL: " + currentLel);
            }
        }
    }
    
    private void initializeBluetooth() {
        try {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            
            if (bluetoothAdapter == null) {
                if (tvLelValue != null) {
                    tvLelValue.setText("Bluetooth desteklenmiyor!");
                }
                Log.e("GridActivity", "Bluetooth adapter null");
                return;
            }
        } catch (Exception e) {
            Log.e("GridActivity", "Bluetooth başlatma hatası: " + e.getMessage());
            if (tvLelValue != null) {
                tvLelValue.setText("Bluetooth hatası: " + e.getMessage());
            }
            return;
        }
        
        // Bluetooth receiver'ı güvenli şekilde kaydet
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            registerReceiver(bluetoothReceiver, filter);
        } catch (Exception e) {
            Log.e("GridActivity", "Bluetooth receiver kayıt hatası: " + e.getMessage());
        }
        
        // Android 12+ için yeni Bluetooth izinleri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
                    != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) 
                    != PackageManager.PERMISSION_GRANTED) {
                
                ActivityCompat.requestPermissions(this, 
                    new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    }, 
                    REQUEST_BLUETOOTH_PERMISSIONS);
                return;
            }
        }
        // Android 6-11 için konum izinleri
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) 
                    != PackageManager.PERMISSION_GRANTED) {
                
                ActivityCompat.requestPermissions(this, 
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 
                    REQUEST_BLUETOOTH_PERMISSIONS);
                return;
            }
        }
        
        // Bluetooth açık kontrolü
        try {
            if (!bluetoothAdapter.isEnabled()) {
                if (tvLelValue != null) {
                    tvLelValue.setText("Bluetooth'u açınız...");
                }
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            } else {
                if (tvLelValue != null) {
                    tvLelValue.setText("Bluetooth hazır, IOIO bağlanıyor...");
                }
                attemptAutoReconnect();
            }
        } catch (SecurityException e) {
            Log.e("GridActivity", "Bluetooth güvenlik hatası: " + e.getMessage());
            if (tvLelValue != null) {
                tvLelValue.setText("Bluetooth güvenlik hatası");
            }
            Toast.makeText(this, "Android 12+ için Bluetooth izinleri gerekli", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e("GridActivity", "Bluetooth kontrol hatası: " + e.getMessage());
            if (tvLelValue != null) {
                tvLelValue.setText("Bluetooth hatası: " + e.getMessage());
            }
        }
    }
    
    private void attemptAutoReconnect() {
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++;
            tvLelValue.setText(String.format("IOIO bağlantısı deneniyor... (%d/%d)", 
                    reconnectAttempts, MAX_RECONNECT_ATTEMPTS));
                    
            reconnectHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tvLelValue.setText("IOIO bağlantısı bekleniyor...");
                }
            }, 2000);
        } else {
            tvLelValue.setText("IOIO bağlantısı kurulamadı");
            reconnectAttempts = 0;
        }
    }
    
    private void handleBluetoothStateChange(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_ON:
                tvLelValue.setText("Bluetooth açık - IOIO bağlanıyor...");
                reconnectAttempts = 0;
                attemptAutoReconnect();
                break;
            case BluetoothAdapter.STATE_OFF:
                tvLelValue.setText("Bluetooth kapalı");
                preferences.edit().putBoolean("IOIO_CONNECTED", false).apply();
                break;
        }
    }
    
    private void handleBluetoothDeviceConnected(BluetoothDevice device) {
        if (device != null && device.getName() != null) {
            String deviceName = device.getName().toLowerCase();
            if (deviceName.contains("ioio")) {
                tvLelValue.setText("IOIO kartı bağlandı: " + device.getName());
                preferences.edit().putBoolean("IOIO_CONNECTED", true).apply();
                reconnectAttempts = 0;
                Toast.makeText(this, "IOIO kartı başarıyla bağlandı!", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    private void handleBluetoothDeviceDisconnected(BluetoothDevice device) {
        if (device != null && device.getName() != null) {
            String deviceName = device.getName().toLowerCase();
            if (deviceName.contains("ioio")) {
                tvLelValue.setText("IOIO bağlantısı kesildi - Yeniden bağlanılıyor...");
                preferences.edit().putBoolean("IOIO_CONNECTED", false).apply();
                spiCalisiyorMu = false;
                
                reconnectHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                            attemptAutoReconnect();
                        }
                    }
                }, RECONNECT_DELAY);
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                Log.i("GridActivity", "Bluetooth izinleri verildi");
                if (tvLelValue != null) {
                    tvLelValue.setText("İzinler verildi - Bluetooth kontrol ediliyor...");
                }
                initializeBluetooth(); // Tekrar kontrol et
            } else {
                Log.w("GridActivity", "Bluetooth izinleri reddedildi");
                if (tvLelValue != null) {
                    tvLelValue.setText("Bluetooth izinleri gerekli!");
                }
                Toast.makeText(this, "IOIO Bluetooth bağlantısı için izinler gerekli", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                Log.i("GridActivity", "Bluetooth açıldı");
                if (tvLelValue != null) {
                    tvLelValue.setText("Bluetooth açıldı, IOIO bağlanıyor...");
                }
            } else {
                Log.w("GridActivity", "Bluetooth açılamadı");
                if (tvLelValue != null) {
                    tvLelValue.setText("Bluetooth gerekli!");
                }
                Toast.makeText(this, "IOIO bağlantısı için Bluetooth gerekli", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    @Override
    protected IOIOThread createIOIOThread() {
        currentThread_ = new IOIOSpiThread();
        return currentThread_;
    }
    
    /**
     * IOIO SPI Thread sınıfı
     */
    private class IOIOSpiThread extends IOIOThread {
        private SpiMaster spi_;
        private DigitalOutput pin12_;
        private DigitalOutput pin39_;
        private AnalogInput adcPin_;
        private PwmOutput pwmPin_;
        private DigitalOutput valfPin_;
        private boolean spiHazir_ = false;
        private boolean adcHazir_ = false;
        private boolean pwmHazir_ = false;
        private boolean valfHazir_ = false;
        
        public boolean isSpiHazir() {
            return spiHazir_;
        }

        // Pin 12'yi kapatmak için yeni metod
        public void stopPin12() {
            try {
                if (pin12_ != null) {
                    pin12_.write(false);
                    Log.i("IOIOThread", "Pin 12 kapatıldı");
                }
            } catch (ConnectionLostException e) {
                Log.e("IOIOThread", "Pin 12 kapatma hatası: " + e.getMessage());
            }
        }

        @Override
        protected void setup() throws ConnectionLostException {
            try {
                if (ioio_ != null) {
                    // Önce IOIO kartının hazır olduğundan emin ol
                    Thread.sleep(500);
                    Log.i("GridActivity", "IOIO kartı hazırlanıyor...");
                    
                    // Pin 12'yi aç
                    pin12_ = ioio_.openDigitalOutput(12, true);
                    Thread.sleep(200);
                    Log.i("GridActivity", "Pin 12 başlatıldı - HIGH");
                    
                    // SPI pinlerini başlat - daha düşük hız
                    spi_ = ioio_.openSpiMaster(37, 35, 38, new int[]{36}, SpiMaster.Rate.RATE_31K);
                    Thread.sleep(300);
                    Log.i("GridActivity", "SPI Master başlatıldı - 16KHz");
                    
                    // En son select pinini aç
                    pin39_ = ioio_.openDigitalOutput(39, true);
                    Thread.sleep(200);
                    Log.i("GridActivity", "Pin 39 (SELECT) başlatıldı - HIGH");
                    
                    // ADC pinini ayarla
                    adcPin_ = ioio_.openAnalogInput(ADC_PIN);
                    adcHazir_ = true;
                    
                    // PWM pinini ayarla - frekans Hz cinsinden
                    pwmPin_ = ioio_.openPwmOutput(PWM_PIN, PWM_FREQ);
                    pwmHazir_ = true;
                    
                    // Valf pinini ayarla
                    valfPin_ = ioio_.openDigitalOutput(VALF_PIN, false);
                    valfHazir_ = true;
                    
                    // SPI hazır
                    spiHazir_ = true;
                    
                    // Bağlantı durumunu güncelle
                    preferences.edit().putBoolean("IOIO_CONNECTED", true).apply();
                    reconnectAttempts = 0;
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tvLelValue.setText("IOIO bağlandı!");
                            
                            // 2 saniye bekleyip SPI işlemlerini başlat
                            new Handler().postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (spiHazir_) {
                                        startSpiOperations();
                                    }
                                }
                            }, 2000);
                        }
                    });
                }
            } catch (Exception e) {
                final String errorMessage = e.getMessage();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvLelValue.setText("IOIO bağlantı hatası: " + errorMessage);
                    }
                });
            }
        }
        
        @Override
        protected void loop() throws ConnectionLostException, InterruptedException {
            try {
                if (adcHazir_) {
                    float voltage = adcPin_.getVoltage();
                    float lelValue = calculateLEL(voltage);
                    updateLELDisplay(lelValue, voltage);
                    
                    // LEL limit kontrolü - sadece valf kontrolünü durdur
                    if (lelValue >= MAX_LEL_LIMIT && isValfControlActive) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getApplicationContext(), 
                                    "LEL limiti aşıldı! Valf kontrolü durduruluyor.", 
                                    Toast.LENGTH_LONG).show();
                                stopValfControl();
                            }
                        });
                    }
                }
                
                if (spiHazir_) {
                    performSpiTransfer();
                }
                
                Thread.sleep(100);
                
            } catch (InterruptedException e) {
                Log.e("IOIOThread", "Loop interrupted: " + e.getMessage());
                throw e;
            } catch (ConnectionLostException e) {
                Log.e("IOIOThread", "Bağlantı koptu: " + e.getMessage());
                throw e;
            }
        }
        
        @Override
        protected void disconnected() {
            spiCalisiyorMu = false;
            spiHazir_ = false;
            adcHazir_ = false;
            pwmHazir_ = false;
            valfHazir_ = false;
            stopValfControl();
            
            // Bağlantı durumunu güncelle
            preferences.edit().putBoolean("IOIO_CONNECTED", false).apply();
            
            // Kaynakları temizle
            try {
                if (spi_ != null) {
                    spi_.close();
                    spi_ = null;
                }
                if (pin12_ != null) {
                    pin12_.close();
                    pin12_ = null;
                }
                if (pin39_ != null) {
                    pin39_.close();
                    pin39_ = null;
                }
                if (adcPin_ != null) {
                    adcPin_.close();
                    adcPin_ = null;
                }
                if (pwmPin_ != null) {
                    pwmPin_.close();
                    pwmPin_ = null;
                }
                if (valfPin_ != null) {
                    valfPin_.close();
                    valfPin_ = null;
                }
            } catch (Exception e) {
                // Sessizce geç
            }
            
            runOnUiThread(new Runnable() {
                @Override
            public void run() {
                    tvLelValue.setText("IOIO bağlantısı kesildi - Yeniden bağlanılıyor...");
                    Toast.makeText(GridActivity.this, "IOIO bağlantısı kesildi", Toast.LENGTH_SHORT).show();
                }
            });
            
            // Otomatik yeniden bağlantı
            reconnectHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                tvLelValue.setText("Yeniden bağlantı deneniyor...");
                            }
                        });
                        attemptAutoReconnect();
                    }
                }
            }, RECONNECT_DELAY);
        }
        
        /**
         * SPI transfer işlemi
         */
                 public void performSpiTransfer() {
            if (!spiHazir_ || spi_ == null || pin39_ == null) {
                Log.e("GridActivity", "SPI hazır değil - spiHazir_:" + spiHazir_ + 
                      " spi_:" + (spi_ != null) + " pin39_:" + (pin39_ != null));
                return;
            }
            
            try {
                byte[] writeData = {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
                byte[] readData = new byte[5];
                
                try {
                    pin39_.write(false);
                    Thread.sleep(10);
                    
                    Log.d("GridActivity", "SPI veri gönderiliyor: 5 byte");
                    spi_.writeRead(0, writeData, writeData.length, writeData.length, readData, readData.length);
                    
                    pin39_.write(true);
                    
                    final byte[] finalData = readData.clone();
                    
                    // SPI verilerini logla
                    StringBuilder logText = new StringBuilder("SPI Veriler: ");
                    for (byte b : readData) {
                        logText.append(String.format("0x%02X ", b & 0xFF));
                    }
                    Log.i("GridActivity", logText.toString());
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateGridColors(finalData);
                        }
                    });
                    
                    Thread.sleep(100);
                    
                } catch (ConnectionLostException e) {
                    Log.e("GridActivity", "IOIO bağlantısı koptu", e);
                    spiHazir_ = false;
                    throw e;
                } catch (Exception e) {
                    Log.e("GridActivity", "SPI transfer hatası", e);
                    throw e;
                }
                
            } catch (Exception e) {
                Log.e("GridActivity", "SPI işlemi sırasında hata", e);
            }
        }
        
        private void startPWM() {
            if (pwmHazir_) {
                try {
                    pwmPin_.setDutyCycle(PWM_DUTY);
                    Log.i("IOIOThread", "PWM başlatıldı: Pin=" + PWM_PIN + ", Duty=" + PWM_DUTY);
                } catch (ConnectionLostException e) {
                    Log.e("IOIOThread", "PWM başlatma hatası: " + e.getMessage());
                }
            }
        }
        
        private void stopPWM() {
            if (pwmHazir_) {
                try {
                    pwmPin_.setDutyCycle(0);
                    Log.i("IOIOThread", "PWM durduruldu");
                } catch (ConnectionLostException e) {
                    Log.e("IOIOThread", "PWM durdurma hatası: " + e.getMessage());
                }
            }
        }
        
        private void startValfControl() {
            if (!valfHazir_) return;
            
            isValfControlActive = true;
            valfRunnable = new Runnable() {
                    @Override
                    public void run() {
                    if (!isValfControlActive) return;
                    
                    try {
                        // Valfi 1 saniye aç
                        valfPin_.write(true);
                
                        // Thread.sleep yerine Handler.postDelayed kullanıyoruz
                        valfHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                                    // Valfi kapat
                                    valfPin_.write(false);
                                    
                                    // 5 saniye sonra tekrar başlat
                                    if (isValfControlActive) {
                                        valfHandler.postDelayed(valfRunnable, 10000);
                                    }
                                } catch (ConnectionLostException e) {
                                    Log.e("IOIOThread", "Valf kapatma hatası: " + e.getMessage());
                            }
                            }
                        }, 1000);
                        
                    } catch (Exception e) {
                        Log.e("IOIOThread", "Valf kontrol hatası: " + e.getMessage());
                    }
                }
            };
            
            // Valf döngüsünü başlat
            valfHandler.post(valfRunnable);
        }
        
        private void stopValfControl() {
            isValfControlActive = false;
            valfHandler.removeCallbacks(valfRunnable);
            try {
                if (valfHazir_) {
                    valfPin_.write(false);
                        }
            } catch (ConnectionLostException e) {
                Log.e("IOIOThread", "Valf kapatma hatası: " + e.getMessage());
            }
        }
    }
    
    /**
     * SPI işlemlerini başlat
     */
    private void startSpiOperations() {
        if (spiCalisiyorMu) return;
        
        spiCalisiyorMu = true;
        Log.i("GridActivity", "SPI operasyonları başlatılıyor");
        
        // İlk transferi hemen yap
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (currentThread_ != null) {
                    currentThread_.performSpiTransfer();
                }
            }
        }).start();
        
        // Sonraki transferleri zamanla
        final Runnable transferRunnable = new Runnable() {
            @Override
            public void run() {
                if (spiCalisiyorMu && currentThread_ != null && currentThread_.isSpiHazir()) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            currentThread_.performSpiTransfer();
                        }
                    }).start();
                    
                    spiHandler.postDelayed(this, SPI_INTERVAL);
                }
            }
        };
        
        spiHandler.postDelayed(transferRunnable, SPI_INTERVAL);
        }

    private void updateLelValue() {
        float lelValue = preferences.getFloat("last_valid_lel", 30.0f);
        boolean hasValidData = preferences.getBoolean("has_valid_data", false);
        
        if (hasValidData) {
            tvLelValue.setText(String.format("LEL: %.1f%%", lelValue));
            
            // LEL değerine göre renk değişimi
            if (lelValue < 5.0f) {
                tvLelValue.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
            } else if (lelValue > 25.0f) {
                tvLelValue.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            } else {
                tvLelValue.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        stopTimer();
        stopCountdown();
        ((IOIOSpiThread)currentThread_).stopPWM();
        ((IOIOSpiThread)currentThread_).stopValfControl();
        super.onDestroy();
        
        // SPI işlemlerini durdur
        spiCalisiyorMu = false;
        
        // Bluetooth receiver'ı kaldır
        if (bluetoothReceiver != null) {
            try {
                unregisterReceiver(bluetoothReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver zaten kaldırılmış
            }
        }
        
        // Handler callbacks'leri temizle
        if (reconnectHandler != null) {
            reconnectHandler.removeCallbacksAndMessages(null);
        }
        if (spiHandler != null) {
            spiHandler.removeCallbacksAndMessages(null);
        }
    }

    // Grid renklendirme fonksiyonu
    private void updateGridColors(byte[] data) {
        try {
            float currentLel = lastLelValue; // ADC'den gelen son LEL değerini kullan
            Log.d("GridActivity", "=== DURUM KONTROLÜ ===");
            Log.d("GridActivity", "Heat Timer: " + (isHeatTimerRunning ? "ÇALIŞIYOR" : "DURDU"));
            Log.d("GridActivity", "Mevcut LEL: " + currentLel);
            Log.d("GridActivity", "Mevcut Voltaj: " + lastVoltage);
            Log.d("GridActivity", "Gri Liste: " + grayDetectors.size() + " detektör");
            Log.d("GridActivity", "Yeşil Liste: " + greenDetectors.size() + " detektör");

            // Her byte için (5 satır) - Yukarıdan aşağıya
            for (int row = 0; row < data.length; row++) {
                byte currentByte = data[row];
                String binaryStr = String.format("%8s", Integer.toBinaryString(currentByte & 0xFF)).replace(' ', '0');
                Log.d("GridActivity", String.format("Byte %d: 0x%02X (%s)", row+1, currentByte, binaryStr));
                
                // Her satırdaki başlangıç hücre numarası
                int startCell = (5 - row) * 7 - 6;  // 29, 22, 15, 8, 1
                
                // Bit sıralaması: 7->1, 6->2, 8->7, 1->3, 2->4, 3->5, 4->6
                boolean[] cellStates = new boolean[7];
                cellStates[0] = (currentByte & 0b01000000) != 0; // 7. bit -> 1. göz
                cellStates[1] = (currentByte & 0b00100000) != 0; // 6. bit -> 2. göz
                cellStates[2] = (currentByte & 0b00000001) != 0; // 1. bit -> 3. göz
                cellStates[3] = (currentByte & 0b00000010) != 0; // 2. bit -> 4. göz
                cellStates[4] = (currentByte & 0b00000100) != 0; // 3. bit -> 5. göz
                cellStates[5] = (currentByte & 0b00001000) != 0; // 4. bit -> 6. göz
                cellStates[6] = (currentByte & 0b10000000) != 0; // 8. bit -> 7. göz
                
                for (int i = 0; i < 7; i++) {
                    int cellNumber = startCell + i;
                    String cellId = "cell_" + (cellNumber - 1);
                    int resId = getResources().getIdentifier(cellId, "id", getPackageName());
                    
                    if (resId != 0) {
                        TextView cell = findViewById(resId);
                        if (cell != null) {
                            boolean isGray = cellStates[i];
                            
                            if (isGray) { // Detektör tetiklendi (GRİ)
                                if (isHeatTimerRunning && !greenDetectors.contains(cell)) {
                                    // Tetiklenme sayısını artır
                                    int currentCount = grayConfirmCount.getOrDefault(cell, 0) + 1;
                                    grayConfirmCount.put(cell, currentCount);
                                    
                                    Log.d("GridActivity", String.format("Detektör %d -> GRİ tetikleme sayısı: %d/%d", 
                                        cellNumber, currentCount, REQUIRED_GRAY_COUNT));

                                    // Yeterli sayıda tetiklendiyse gri listeye ekle
                                    if (currentCount >= REQUIRED_GRAY_COUNT) {
                                        grayDetectors.put(cell, true);
                                        cell.setBackgroundResource(R.drawable.bg_gray_border);
                                        cell.setText("");
                                        Log.d("GridActivity", "Detektör " + cellNumber + " griye döndü ve kaydedildi");
                                    }
                                }
                            } else { // Detektör normale döndü
                                // Heat Timer çalışıyorsa ve gri değilse sayacı sıfırla
                                if (isHeatTimerRunning && !grayDetectors.containsKey(cell)) {
                                    grayConfirmCount.remove(cell);
                                }
                                
                                if (!isHeatTimerRunning && grayDetectors.containsKey(cell) && !greenDetectors.contains(cell)) {
                                    // LEL değerini sakla ve rengi ayarla
                                    detectorLelValues.put(cell, currentLel);
                                    cell.setBackgroundResource(getDetectorColorByLel(currentLel));
                                    String lelText;
                                    if (currentLel < 0) {
                                        lelText = String.format("↓%.1f%%", Math.abs(currentLel));
                                    } else {
                                        lelText = String.format("%.1f%%", currentLel);
                                    }
                                    cell.setText(lelText);
                                    cell.setTextColor(Color.BLACK);
                                    greenDetectors.add(cell);
                                    grayDetectors.remove(cell);
                                    Log.d("GridActivity", String.format("Detektör %d -> LEL: %s, Renk: %s", 
                                        cellNumber, lelText, 
                                        currentLel < LEL_YELLOW_THRESHOLD ? "SARI" : 
                                        currentLel <= LEL_GREEN_THRESHOLD ? "YEŞİL" : "KIRMIZI"));
                                } else if (greenDetectors.contains(cell)) {
                                    // Yeşil detektörün saklanan LEL değerini göster
                                    float savedLel = detectorLelValues.getOrDefault(cell, 0.0f);
                                    String lelText;
                                    if (savedLel < 0) {
                                        lelText = String.format("↓%.1f%%", Math.abs(savedLel));
                                    } else {
                                        lelText = String.format("%.1f%%", savedLel);
                                    }
                                    cell.setText(lelText);
                                    // Rengi güncelle
                                    cell.setBackgroundResource(getDetectorColorByLel(savedLel));
                                } else if (!greenDetectors.contains(cell) && !grayDetectors.containsKey(cell)) {
                                    // Normal duruma getir
                                    cell.setBackgroundResource(R.drawable.bg_grid_normal);
                                    cell.setText("");
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e("GridActivity", "Grid güncelleme hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void updateVoltageDisplay(final float voltage) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tvVoltage.setText(String.format("%.3fV", voltage));
            }
        });
    }

    private void onCountdownFinished() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), 
                    "Geri sayım tamamlandı! Valf kontrolü başlıyor.", 
                    Toast.LENGTH_SHORT).show();
                ((IOIOSpiThread)currentThread_).startValfControl();
            }
        });
    }

    // SPI verilerini işle
    private void handleSpiData(final byte[] receivedData) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateGridColors(receivedData);
            }
        });
    }

    private float getCurrentLELValue() {
        try {
            String lelText = tvLelValue.getText().toString();
            String[] parts = lelText.split("%");
            if (parts.length > 0) {
                String numStr = parts[0].replace("LEL: ", "").trim();
                return Float.parseFloat(numStr);
            }
        } catch (Exception e) {
            Log.e("GridActivity", "LEL değeri alınamadı: " + e.getMessage());
        }
        return 0.0f;
    }
    
    // Heat Timer başlatma fonksiyonu
    private void startHeatTimer() {
        isHeatTimerRunning = true;
        Log.d("GridActivity", "Heat Timer BAŞLATILDI");
        // Tüm listeleri temizle
        grayDetectors.clear();
        detectorLelValues.clear();
        grayConfirmCount.clear();
    }

    // Heat Timer durdurma fonksiyonu
    private void stopHeatTimer() {
        isHeatTimerRunning = false;
        Log.d("GridActivity", "Heat Timer DURDURULDU");
        Log.d("GridActivity", "Gri listede " + grayDetectors.size() + " detektör var");
                }

    private void initializeDetektorViews() {
        detektorTextViews = new TextView[35];
        for (int i = 1; i <= 35; i++) {
            int viewId = getResources().getIdentifier("textView" + i, "id", getPackageName());
            if (viewId != 0) {
                detektorTextViews[i-1] = findViewById(viewId);
            }
        }
    }

    /**
     * LEL değerine göre detektör rengini belirler
     */
    private int getDetectorColorByLel(float lelValue) {
        if (lelValue < 0) {
            return R.drawable.bg_gray_border;  // Negatif değerler için gri
        } else if (lelValue < LEL_YELLOW_THRESHOLD) {
            return R.drawable.bg_yellow_border;  // %7.5 altı sarı
        } else if (lelValue < LEL_GREEN_THRESHOLD) {
            return R.drawable.bg_green_border;   // %7.5-%12.5 arası yeşil
        } else {
            return R.drawable.bg_red_border;     // %12.5 üstü kırmızı
        }
    }
} 