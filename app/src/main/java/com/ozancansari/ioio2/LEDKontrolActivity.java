package com.ozancansari.ioio2;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.EditText;

import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

import ioio.lib.api.AnalogInput;
import ioio.lib.api.DigitalOutput;
import ioio.lib.api.DigitalInput;
import ioio.lib.api.IOIO;
import ioio.lib.api.PwmOutput;
import ioio.lib.api.SpiMaster;
import ioio.lib.api.TwiMaster;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.android.AbstractIOIOActivity;

/**
 * IOIO cihazına Bluetooth ile bağlanıp LED'leri kontrol etmek için örnek Activity.
 * 
 * Bu Activity, IOIO'nun stat LED'ini (pin 0) ve 2-5 numaralı pinlerdeki 
 * harici LED'leri kontrol eder.
 */
public class LEDKontrolActivity extends AbstractIOIOActivity {
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1001;
    private static final int REQUEST_ENABLE_BT = 1002;
    
    // Bluetooth yönetimi
    private BluetoothAdapter bluetoothAdapter;
    private SharedPreferences preferences;
    private Handler reconnectHandler;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final int RECONNECT_DELAY = 3000; // 3 saniye
    
    // BME280 Pin Kontrolleri
    private Button pin9Button_;
    private Button pin10Button_;
    private Button pin11Button_;
    private Button pin12Button_;
    private Button pin13Button_;
    private Button pin39Button_;
    private Button pin15Button_;
    private boolean pin9Durum_ = false;
    private boolean pin10Durum_ = false;
    private boolean pin11Durum_ = false;
    private boolean pin12Durum_ = false;
    private boolean pin13Durum_ = false;
    private boolean pin39Durum_ = false;
    private boolean pin15Durum_ = false;
    
    private Button statLedButton_;
    private Button led1Button_;
    private Button pwmButton_;
    private Button spiSendButton_;
    private Button spiTestButton_;
    private Button i2cSendButton_;
    private Button i2cTestButton_;
    private Button adcOkuButton_;
    private Button adcSurekliButton_;
    private SeekBar pwmSeekBar_;
    private EditText spiDataInput_;
    private EditText i2cAdresInput_;
    private EditText i2cDataInput_;
    private TextView statusText_;
    private TextView pwmDurumText_;
    private TextView spiResponseText_;
    private TextView i2cResponseText_;
    private TextView adcSonucText_;
    
    // LED, PWM, SPI, I²C ve ADC durumları
    private boolean statLedDurum_ = false;
    private boolean led1Durum_ = false;
    private boolean pwmAktif_ = false;
    private boolean spiTestAktif_ = false;
    private boolean i2cTestAktif_ = false;
    private boolean i2cClockAktif_ = false; // Yeni: I²C clock toggle
    private boolean adcSurekliAktif_ = false;
    private float pwmDeger_ = 0.5f; // 0.0 - 1.0 arası
    
    // Thread referansı
    private IOIOKontrolThread currentThread_;
    
    private TwiMaster twi_;
    private BME280Sensor bme280_;
    private boolean twiHazir_ = false;
    
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
    
    @Override
    protected boolean shouldWaitForConnect() {
        return true;
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_led_kontrol);
        
        // SharedPreferences başlat
        preferences = getSharedPreferences("IOIO_PREFS", MODE_PRIVATE);
        reconnectHandler = new Handler();
        
        // UI elemanlarını bul
        statusText_ = findViewById(R.id.statusText);
        pwmDurumText_ = findViewById(R.id.pwmDurumText);
        spiResponseText_ = findViewById(R.id.spiResponseText);
        i2cResponseText_ = findViewById(R.id.i2cResponseText);
        adcSonucText_ = findViewById(R.id.adcSonucText);
        statLedButton_ = findViewById(R.id.statLedButton);
        led1Button_ = findViewById(R.id.led1Button);
        pwmButton_ = findViewById(R.id.pwmButton);
        spiSendButton_ = findViewById(R.id.spiSendButton);
        spiTestButton_ = findViewById(R.id.spiTestButton);
        i2cSendButton_ = findViewById(R.id.i2cSendButton);
        i2cTestButton_ = findViewById(R.id.i2cTestButton);
        adcOkuButton_ = findViewById(R.id.adcOkuButton);
        adcSurekliButton_ = findViewById(R.id.adcSurekliButton);
        pwmSeekBar_ = findViewById(R.id.pwmSeekBar);
        spiDataInput_ = findViewById(R.id.spiDataInput);
        i2cAdresInput_ = findViewById(R.id.i2cAdresInput);
        i2cDataInput_ = findViewById(R.id.i2cDataInput);
        
        // BME280 Pin Butonları
        pin9Button_ = findViewById(R.id.pin9Button);
        pin10Button_ = findViewById(R.id.pin10Button);
        pin11Button_ = findViewById(R.id.pin11Button);
        pin12Button_ = findViewById(R.id.pin12Button);
        pin13Button_ = findViewById(R.id.pin13Button);
        pin39Button_ = findViewById(R.id.pin16Button);
        pin15Button_ = findViewById(R.id.pin15Button);
        
        // SeekBar listener'ı
        pwmSeekBar_.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pwmDeger_ = progress / 100.0f;
                pwmDurumText_.setText(String.format("PWM Değeri: %.1f%%", pwmDeger_ * 100));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        // Bluetooth kontrolü
        initializeBluetooth();

        Button mainMenuButton = findViewById(R.id.mainMenuButton);
        mainMenuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(LEDKontrolActivity.this, MainActivity.class);
                startActivity(intent);
            }
        });
    }
    

    
    private void initializeBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        
        if (bluetoothAdapter == null) {
            statusText_.setText("Bu cihazda Bluetooth desteklenmiyor!");
            return;
        }
        
        // Bluetooth receiver'ı kaydet
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(bluetoothReceiver, filter);
        
        // Android 12+ (API 31+) için yeni Bluetooth izinleri
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
        
        // Bluetooth açık kontrolü - Android 12+ için ek kontrol
        try {
            if (!bluetoothAdapter.isEnabled()) {
                statusText_.setText("Bluetooth'u açınız...");
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            } else {
                statusText_.setText("Bluetooth hazır, IOIO bağlanıyor...");
                // Otomatik bağlantı deneme
                attemptAutoReconnect();
            }
        } catch (SecurityException e) {
            statusText_.setText("Bluetooth güvenlik hatası - İzinleri kontrol edin");
            Toast.makeText(this, "Android 12+ için Bluetooth izinleri gerekli", Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * Otomatik yeniden bağlantı denemesi
     */
    private void attemptAutoReconnect() {
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++;
            statusText_.setText(String.format("IOIO bağlantısı deneniyor... (%d/%d)", 
                    reconnectAttempts, MAX_RECONNECT_ATTEMPTS));
                    
            reconnectHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        statusText_.setText("IOIO bağlantısı bekleniyor...");
                    }
            }, 2000);
        } else {
            statusText_.setText("IOIO bağlantısı kurulamadı - Manuel kontrol gerekli");
            reconnectAttempts = 0; // Reset için
        }
    }
    
    /**
     * Bluetooth durum değişikliği işleyicisi
     */
    private void handleBluetoothStateChange(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_ON:
                statusText_.setText("Bluetooth açık - IOIO bağlanıyor...");
                reconnectAttempts = 0; // Reset counter
                attemptAutoReconnect();
                break;
            case BluetoothAdapter.STATE_OFF:
                statusText_.setText("Bluetooth kapalı");
                preferences.edit().putBoolean("IOIO_CONNECTED", false).apply();
                break;
            case BluetoothAdapter.STATE_TURNING_ON:
                statusText_.setText("Bluetooth açılıyor...");
                break;
            case BluetoothAdapter.STATE_TURNING_OFF:
                statusText_.setText("Bluetooth kapanıyor...");
                break;
        }
    }
    
    /**
     * Bluetooth cihaz bağlantısı işleyicisi
     */
    private void handleBluetoothDeviceConnected(BluetoothDevice device) {
        if (device != null && device.getName() != null) {
            String deviceName = device.getName().toLowerCase();
            if (deviceName.contains("ioio")) {
                statusText_.setText("IOIO kartı bağlandı: " + device.getName());
                preferences.edit().putBoolean("IOIO_CONNECTED", true).apply();
                reconnectAttempts = 0; // Reset counter on successful connection
                Toast.makeText(this, "IOIO kartı başarıyla bağlandı!", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    /**
     * Bluetooth cihaz bağlantısı kopması işleyicisi
     */
    private void handleBluetoothDeviceDisconnected(BluetoothDevice device) {
        if (device != null && device.getName() != null) {
            String deviceName = device.getName().toLowerCase();
            if (deviceName.contains("ioio")) {
                statusText_.setText("IOIO bağlantısı kesildi - Yeniden bağlanılıyor...");
                preferences.edit().putBoolean("IOIO_CONNECTED", false).apply();
                
                // Kısa gecikme ile otomatik yeniden bağlantı
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
                initializeBluetooth(); // Tekrar kontrol et
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    statusText_.setText("Bluetooth izinleri gerekli (Android 12+)!");
                    Toast.makeText(this, "IOIO Bluetooth bağlantısı için Bluetooth izinleri gerekli", Toast.LENGTH_LONG).show();
                } else {
                    statusText_.setText("Konum izni gerekli (Android 6-11)!");
                    Toast.makeText(this, "Bluetooth tarama için konum izni gerekli", Toast.LENGTH_LONG).show();
                }
            }
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                statusText_.setText("Bluetooth açıldı, IOIO bağlanıyor...");
                statusText_.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        statusText_.setText("IOIO bağlantısı bekleniyor...");
                    }
                }, 1000);
            } else {
                statusText_.setText("Bluetooth gerekli!");
                Toast.makeText(this, "IOIO bağlantısı için Bluetooth gerekli", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected IOIOThread createIOIOThread() {
        IOIOKontrolThread thread = new IOIOKontrolThread();
        currentThread_ = thread;
        return thread;
    }

    /**
     * IOIO iletişimi için özel thread sınıfı
     */
    private class IOIOKontrolThread extends IOIOThread {
        private DigitalOutput statLed_;
        private DigitalOutput led1_;
        private PwmOutput pwmPin_;
        private SpiMaster spi_;
        private AnalogInput adcPin_;
        
        // Manuel I2C Master GPIO pinleri
        private DigitalOutput sclPin_;     // Pin 5 - SCL (Clock)
        private DigitalOutput csbPin_;     // Pin 6 - CSB (Chip Select)
        private DigitalOutput sdaOutPin_;  // Pin 4 - SDA (Data Output)
        private DigitalInput sdaInPin_;    // Pin 4 - SDA (Data Input)
        private boolean sdaIsOutput_ = true;
        
        // BME280 Pin Çıkışları
        private DigitalOutput pin9_;
        private DigitalOutput pin10_;
        private DigitalOutput pin11_;
        private DigitalOutput pin12_;
        private DigitalOutput pin13_;
        private DigitalOutput pin39_;
        private DigitalOutput pin15_;
        
        private boolean spiHazir_ = false;
        private boolean i2cHazir_ = false;
        private boolean adcHazir_ = false;

        @Override
        protected void setup() throws ConnectionLostException {
            try {
                // LED ve PWM çıkışlarını başlat
                statLed_ = ioio_.openDigitalOutput(IOIO.LED_PIN, false);
                led1_ = ioio_.openDigitalOutput(1, false);
                pwmPin_ = ioio_.openPwmOutput(14, 1000); // Pin 14, 1kHz frekans
                
                // BME280 Pin Çıkışları
                pin9_ = ioio_.openDigitalOutput(9, false);
                pin10_ = ioio_.openDigitalOutput(10, false);
                pin11_ = ioio_.openDigitalOutput(11, false);
                pin12_ = ioio_.openDigitalOutput(12, false);
                pin13_ = ioio_.openDigitalOutput(13, false);
                pin39_ = ioio_.openDigitalOutput(39, false);
                pin15_ = ioio_.openDigitalOutput(15, false);
                

                // SPI Master'ı başlat - Pin 37(MISO), 39(MOSI), 38(CLK), 36(SS)
                try {
                    if (ioio_ != null) {
                        // Pin 37: MISO, Pin 36: MOSI, Pin 38: CLK, Pin 35: SS
                        spi_ = ioio_.openSpiMaster(37, 35, 38, new int[]{36}, SpiMaster.Rate.RATE_31K);
                        spiHazir_ = true;
                        Thread.sleep(200); // SPI başlatma sonrası bekleme
                    }
                } catch (Exception e) {
                    spiHazir_ = false;
                    spi_ = null;
                    final String errorMsg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "SPI başlatılamadı (Pin37,39,38,36): " + errorMsg, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                
                // Manuel I²C Master GPIO başlat
                try {
                    if (ioio_ != null) {
                        // Pin 4, 5, 6 manuel I2C için
                        sclPin_ = ioio_.openDigitalOutput(5, false);    // SCL (Clock)
                        csbPin_ = ioio_.openDigitalOutput(6, false);    // CSB (başlangıçta LOW)
                        sdaOutPin_ = ioio_.openDigitalOutput(4, false); // SDA Output
                        sdaInPin_ = null; // Sonra açılacak
                        sdaIsOutput_ = true;
                        
                        // I2C Master başlatma protokolü
                        initManuelI2CMaster();
                        
                        i2cHazir_ = true;
                        Thread.sleep(200); // I²C başlatma sonrası bekleme
                    }
                } catch (Exception e) {
                    i2cHazir_ = false;
                    final String errorMsg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "Manuel I²C başlatılamadı: " + errorMsg, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                
                // ADC (Analog Input) başlat
                try {
                    if (ioio_ != null) {
                        // Pin 31'de ADC - 12-bit çözünürlük (0-4095)
                        adcPin_ = ioio_.openAnalogInput(31);
                        adcHazir_ = true;
                        Thread.sleep(100); // ADC başlatma sonrası bekleme
                    }
                } catch (Exception e) {
                    adcHazir_ = false;
                    adcPin_ = null;
                    final String errorMsg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "ADC başlatılamadı: " + errorMsg, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                
                // TWI (I2C) başlat - 100KHz
                try {
                    if (ioio_ != null) {
                        twi_ = ioio_.openTwiMaster(0, TwiMaster.Rate.RATE_100KHz, false);
                        bme280_ = new BME280Sensor(twi_);
                        twiHazir_ = true;
                        Thread.sleep(100);
                        
                        // BME280 bağlantı testi
                        if (bme280_.isConnected()) {
                            Log.d("BME280", "BME280 sensörü başarıyla bağlandı!");
                        } else {
                            Log.e("BME280", "BME280 sensörü bulunamadı!");
                        }
                    }
                } catch (Exception e) {
                    twiHazir_ = false;
                    twi_ = null;
                    bme280_ = null;
                    final String errorMsg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "TWI başlatılamadı: " + errorMsg, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                
            // Bağlantı durumunu güncelle ve MainActivity'i bilgilendir
            preferences.edit().putBoolean("IOIO_CONNECTED", true).apply();
            reconnectAttempts = 0; // Başarılı bağlantı sonrası sayacı sıfırla
                
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                        statusText_.setText("IOIO başarıyla bağlandı! Tüm kontroller aktif.");
                        Toast.makeText(LEDKontrolActivity.this, "IOIO kartı başarıyla bağlandı!", Toast.LENGTH_SHORT).show();
                    
                        // BME280 Pin Butonları
                        pin9Button_.setEnabled(true);
                        pin10Button_.setEnabled(true);
                        pin11Button_.setEnabled(true);
                        pin12Button_.setEnabled(true);
                        pin13Button_.setEnabled(true);
                        pin39Button_.setEnabled(true);
                        pin15Button_.setEnabled(true);
                        
                        pin9Button_.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                pin9Durum_ = !pin9Durum_;
                                pin9Button_.setText("Pin 9\n" + (pin9Durum_ ? "AÇIK" : "KAPALI"));
                            }
                        });
                        
                        pin10Button_.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                pin10Durum_ = !pin10Durum_;
                                pin10Button_.setText("Pin 10\n" + (pin10Durum_ ? "AÇIK" : "KAPALI"));
                            }
                        });
                        
                        pin11Button_.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                pin11Durum_ = !pin11Durum_;
                                pin11Button_.setText("Pin 11\n" + (pin11Durum_ ? "AÇIK" : "KAPALI"));
                            }
                        });
                        
                        pin12Button_.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                pin12Durum_ = !pin12Durum_;
                                pin12Button_.setText("Pin 12\n" + (pin12Durum_ ? "AÇIK" : "KAPALI"));
                            }
                        });
                        
                        pin13Button_.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                pin13Durum_ = !pin13Durum_;
                                pin13Button_.setText("Pin 13\n" + (pin13Durum_ ? "AÇIK" : "KAPALI"));
                            }
                        });
                        
                        pin39Button_.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                pin39Durum_ = !pin39Durum_;
                                pin39Button_.setText("Pin 16\n" + (pin39Durum_ ? "AÇIK" : "KAPALI"));
                            }
                        });
                        
                        pin15Button_.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                pin15Durum_ = !pin15Durum_;
                                pin15Button_.setText("Pin 15\n" + (pin15Durum_ ? "AÇIK" : "KAPALI"));
                            }
                        });
                        
                        // LED kontrolleri
                        statLedButton_.setEnabled(true);
                        statLedButton_.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                statLedDurum_ = !statLedDurum_;
                                statLedButton_.setText("Stat LED: " + (statLedDurum_ ? "AÇIK" : "KAPALI"));
                            }
                        });
                        
                        led1Button_.setEnabled(true);
                        led1Button_.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                led1Durum_ = !led1Durum_;
                                led1Button_.setText("LED 1: " + (led1Durum_ ? "AÇIK" : "KAPALI"));
                            }
                        });
                        
                        // PWM kontrolleri
                        pwmButton_.setEnabled(true);
                        pwmSeekBar_.setEnabled(true);
                        pwmButton_.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                pwmAktif_ = !pwmAktif_;
                                pwmButton_.setText("PWM: " + (pwmAktif_ ? "AÇIK" : "KAPALI"));
                            }
                        });
                        
                        // SPI kontrolleri (sadece başarılı olursa)
                        if (spiHazir_) {
                            spiDataInput_.setEnabled(true);
                            spiSendButton_.setEnabled(true);
                            spiTestButton_.setEnabled(true);
                            
                            spiSendButton_.setOnClickListener(new OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            sendSpiData();
                                        }
                                    }).start();
                                }
                            });
                            
                            spiTestButton_.setOnClickListener(new OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    try {
                                        boolean yeniDurum = !spiTestAktif_;
                                        spiTestButton_.setText("SPI Test: " + (yeniDurum ? "AÇILIYOR..." : "KAPALI"));
                                        
                                        if (yeniDurum) {
                                            if (spi_ != null && spiHazir_) {
                                                spiTestAktif_ = true;
                                                spiResponseText_.setText("SPI Sürekli Test Başladı - CLK Pin 12'yi kontrol edin!\nRate: 125kHz, 10Hz test");
                                                spiTestButton_.setText("SPI Test: AÇIK");
                                            } else {
                                                spiTestAktif_ = false;
                                                spiTestButton_.setText("SPI Test: KAPALI");
                                                spiResponseText_.setText("SPI Hatası: SPI hazır değil veya null");
                                            }
                                        } else {
                                            spiTestAktif_ = false;
                                            spiResponseText_.setText("SPI Sürekli Test Durduruldu");
                                            spiTestButton_.setText("SPI Test: KAPALI");
                                        }
                                    } catch (Exception e) {
                                        spiTestAktif_ = false;
                                        spiTestButton_.setText("SPI Test: KAPALI");
                                        spiResponseText_.setText("SPI Buton Hatası: " + e.getMessage());
                                    }
                                }
                            });
                        } else {
                            spiResponseText_.setText("SPI Hatası: Kullanılamıyor");
                        }
                        
                        // I²C kontrolleri (sadece başarılı olursa)
                        if (i2cHazir_) {
                            i2cAdresInput_.setEnabled(true);
                            i2cDataInput_.setEnabled(true);
                            i2cSendButton_.setEnabled(true);
                            i2cTestButton_.setEnabled(true);
                            
                            i2cSendButton_.setOnClickListener(new OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    try {
                                        boolean yeniDurum = !i2cClockAktif_;
                                        i2cSendButton_.setText("I²C Clock: " + (yeniDurum ? "AÇILIYOR..." : "KAPALI"));
                                        
                                        // Eğer işlem devam ediyorsa ignore et
                                        if (i2cClockAktif_) {
                                            i2cResponseText_.setText("BME280 I2C işlem devam ediyor, bekleyin...");
                                            return;
                                        }
                                        
                                        if (yeniDurum) {
                                            if (i2cHazir_) {
                                                i2cClockAktif_ = true;
                                                i2cResponseText_.setText("BME280 I²C Transaction:\nWrite: 0xEC → 0xD0\nRead: 0xED ← data\nTek işlem başlıyor...");
                                                i2cSendButton_.setText("I²C Send: İŞLEM...");
                                            } else {
                                                i2cClockAktif_ = false;
                                                i2cSendButton_.setText("I²C Send: KAPALI");
                                                i2cResponseText_.setText("Manuel I²C Hatası: GPIO hazır değil");
                                            }
                                        }
                                    } catch (Exception e) {
                                        i2cClockAktif_ = false;
                                        i2cSendButton_.setText("I²C Send: HATA");
                                        i2cResponseText_.setText("I²C Buton Hatası: " + e.getMessage());
                                    }
                                }
                            });
                            
                            i2cTestButton_.setOnClickListener(new OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    try {
                                        boolean yeniDurum = !i2cTestAktif_;
                                        i2cTestButton_.setText("I²C Test: " + (yeniDurum ? "AÇILIYOR..." : "KAPALI"));
                                        
                                        // Eğer test devam ediyorsa ignore et
                                        if (i2cTestAktif_) {
                                            i2cResponseText_.setText("BME280 I2C test devam ediyor, bekleyin...");
                                            return;
                                        }
                                        
                                        if (yeniDurum) {
                                            if (i2cHazir_) {
                                                i2cTestAktif_ = true;
                                                i2cResponseText_.setText("Manuel I²C Master Test:\nPin 5 (SCL) - Clock Master\nPin 4 (SDA) - Data Line\nTek işlem başlıyor...");
                                                i2cTestButton_.setText("I²C Test: İŞLEM...");
                                            } else {
                                                i2cTestAktif_ = false;
                                                i2cTestButton_.setText("I²C Test: KAPALI");
                                                i2cResponseText_.setText("Manuel I²C Hatası: GPIO hazır değil");
                                            }
                                        }
                                    } catch (Exception e) {
                                        i2cTestAktif_ = false;
                                        i2cTestButton_.setText("I²C Test: KAPALI");
                                        i2cResponseText_.setText("I²C Buton Hatası: " + e.getMessage());
                                    }
                                }
                            });
                        } else {
                            i2cResponseText_.setText("I²C Hatası: Kullanılamıyor");
                        }
                        
                        // ADC kontrolleri (sadece başarılı olursa)
                        if (adcHazir_) {
                            adcOkuButton_.setEnabled(true);
                            adcSurekliButton_.setEnabled(true);
                            
                            adcOkuButton_.setOnClickListener(new OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            readAdcOnce();
                                        }
                                    }).start();
                                }
                            });
                            
                            adcSurekliButton_.setOnClickListener(new OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    try {
                                        boolean yeniDurum = !adcSurekliAktif_;
                                        adcSurekliButton_.setText("ADC: " + (yeniDurum ? "AÇILIYOR..." : "KAPALI"));
                                        
                                        if (yeniDurum) {
                                            if (adcPin_ != null && adcHazir_) {
                                                adcSurekliAktif_ = true;
                                                adcSonucText_.setText("ADC Sürekli Okuma Başladı\nPin 31'e analog gerilim uygulayın (0-3.3V)");
                                                adcSurekliButton_.setText("ADC: AÇIK");
                                            } else {
                                                adcSurekliAktif_ = false;
                                                adcSurekliButton_.setText("ADC: KAPALI");
                                                adcSonucText_.setText("ADC Hatası: ADC hazır değil veya null");
                                            }
                                        } else {
                                            adcSurekliAktif_ = false;
                                            adcSonucText_.setText("ADC Sürekli Okuma Durduruldu");
                                            adcSurekliButton_.setText("ADC: KAPALI");
                                        }
                                    } catch (Exception e) {
                                        adcSurekliAktif_ = false;
                                        adcSurekliButton_.setText("ADC: KAPALI");
                                        adcSonucText_.setText("ADC Buton Hatası: " + e.getMessage());
                                    }
                                }
                            });
                        } else {
                            adcSonucText_.setText("ADC Hatası: Kullanılamıyor");
                        }
                    }
                });
            } catch (ConnectionLostException e) {
                throw e;
            } catch (Exception e) {
                final String errorMessage = e.getMessage();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        statusText_.setText("IOIO bağlantı hatası: " + errorMessage);
                    }
                });
            }
        }

        private void sendSpiData() {
            if (!spiHazir_) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        spiResponseText_.setText("SPI Hatası: SPI başlatılmamış");
                    }
                });
                return;
            }
            
            try {
                final String inputText = spiDataInput_.getText().toString().trim();
                final String finalText = inputText.isEmpty() ? "0x11" : inputText;
                
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        spiResponseText_.setText("SPI işlemi başlıyor...");
                    }
                });
                
                // Hex string'i byte'a çevir
                byte tempData;
                if (finalText.startsWith("0x") || finalText.startsWith("0X")) {
                    tempData = (byte) Integer.parseInt(finalText.substring(2), 16);
                } else {
                    try {
                        tempData = (byte) Integer.parseInt(finalText);
                    } catch (NumberFormatException e) {
                        tempData = (byte) Integer.parseInt(finalText, 16);
                    }
                }
                final byte dataToSend = tempData;
                
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        spiResponseText_.setText("SPI verisi hazırlandı: 0x" + Integer.toHexString(dataToSend & 0xFF).toUpperCase());
                    }
                });
                
                Thread.sleep(500); // Kullanıcının görmesi için bekleme
                
                                // SPI işlemi - Daha uzun veri transferi için
                byte[] writeData = {dataToSend, dataToSend, dataToSend, dataToSend, dataToSend}; // 5 byte gönder
                
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        spiResponseText_.setText("SPI transferi yapılıyor... (CLK sinyalini kontrol edin)");
                    }
                });
                
                // SPI transfer sonuçlarını topla
                StringBuilder transferSonuclari = new StringBuilder();
                transferSonuclari.append("Transfer tamamlandı!\n");
                transferSonuclari.append(String.format("Gönderilen: 0x%02X (5 byte x 5 kez)\n\n", dataToSend & 0xFF));
                transferSonuclari.append("Transfer 1: ");
                
                // 5 kez SPI transferi yap (CLK sinyalinin görülmesi için)
                for (int i = 0; i < 1; i++) {
                    // Her transfer için ayrı byte array kullan
                    byte[] readData = new byte[5]; // Her transfer için yeni array
                    
                    // Her SPI transfer öncesi Pin 39'u aç-kapat (Enable/Select sinyali)
                    pin39_.write(true);   // Pin 39'u aç
                    Thread.sleep(10);     // Kısa bekleme
                    pin39_.write(false);  // Pin 39'u kapat
                    Thread.sleep(10);     // Kısa bekleme
                    
                    spi_.writeRead(0, writeData, 5, 5, readData, 5); // Slave 0, 5 byte transfer
                    
                    // Bu transfer'in sonuçlarını tek satırda ekle
                    for (int j = 0; j < 5; j++) {
                        transferSonuclari.append(String.format("0x%02X ", readData[j] & 0xFF));
                    }
                    
                    Thread.sleep(100); // Transfer arası bekleme
                }
                
                final String response = transferSonuclari.toString();
                
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        spiResponseText_.setText(response);
                    }
                });
                
            } catch (Exception e) {
                final String errorMsg = e.getMessage();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        spiResponseText_.setText("SPI Hatası: " + errorMsg);
                    }
                });
            }
        }
        
        /**
         * BME280 I2C Master başlatma protokolü
         */
        private void initManuelI2CMaster() throws ConnectionLostException, InterruptedException {
            // BME280 SPI→I2C mode switching
            csbPin_.write(false);    // SPI mode reset
            Thread.sleep(50);
            csbPin_.write(true);     // I2C mode enable
            Thread.sleep(10);
            
            // I2C bus idle state
            setSDAOutput();
            sdaOutPin_.write(true);  // SDA = HIGH (idle)
            sclPin_.write(true);     // SCL = HIGH (idle)
            Thread.sleep(10);
        }
        
        /**
         * BME280 I2C Master - Tek işlem fonksiyonu
         * Butona basıldığında BME280 ID register'ını okur
         */
        private void performSingleI2CTransaction() throws ConnectionLostException, InterruptedException {
            final int BME280_SLAVE_ADDR = 0x76;  // BME280 write address
            final int BME280_ID_REG = 0xD0;      // Chip ID register
            final int EXPECTED_ID = 0x60;        // BME280 chip ID
            
            try {
                // BME280 dokümantasyon Figure 10: I2C read sequence
                
                // PHASE 1: Write register address
                bme280Start();
                boolean writeAck = bme280WriteByte(BME280_SLAVE_ADDR);
                if (!writeAck) throw new Exception("BME280 slave no ACK");
                
                boolean regAck = bme280WriteByte(BME280_ID_REG);
                if (!regAck) throw new Exception("Register write no ACK");
                
                // PHASE 2: Repeated start + read data
                bme280Start(); // Repeated start
                boolean readAck = bme280WriteByte(BME280_SLAVE_ADDR | 0x01); // 0xED
                if (!readAck) throw new Exception("Read address no ACK");
                
                int chipId = bme280ReadByte(false); // NACK (last byte)
                bme280Stop();
                
                // Result report
                final boolean success = (chipId == EXPECTED_ID);
                final String result = String.format(
                    "BME280 I2C Test:\nWrite: 0x%02X → 0x%02X\nRead: 0x%02X → 0x%02X\nResult: %s", 
                    BME280_SLAVE_ADDR, BME280_ID_REG,
                    BME280_SLAVE_ADDR | 0x01, chipId,
                    success ? "SUCCESS (BME280 detected!)" : "FAIL (wrong ID)"
                );
                
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        i2cResponseText_.setText(result);
                    }
                });
                
            } catch (Exception e) {
                bme280Stop(); // Bus cleanup
                final String error = "BME280 I2C Error: " + e.getMessage();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        i2cResponseText_.setText(error);
                    }
                });
            }
        }
        
        /**
         * BME280 I2C Protocol Implementation
         */
        private void setSDAOutput() throws ConnectionLostException, InterruptedException {
            if (!sdaIsOutput_) {
                if (sdaInPin_ != null) { sdaInPin_.close(); sdaInPin_ = null; }
                sdaOutPin_ = ioio_.openDigitalOutput(4, false);
                sdaIsOutput_ = true;
                Thread.sleep(1);
            }
        }
        
        private void setSDAInput() throws ConnectionLostException, InterruptedException {
            if (sdaIsOutput_) {
                if (sdaOutPin_ != null) { sdaOutPin_.close(); sdaOutPin_ = null; }
                sdaInPin_ = ioio_.openDigitalInput(4, DigitalInput.Spec.Mode.PULL_UP);
                sdaIsOutput_ = false;
                Thread.sleep(1);
            }
        }
        
        private void bme280Start() throws ConnectionLostException, InterruptedException {
            setSDAOutput();
            sdaOutPin_.write(true);  sclPin_.write(true);  // Bus idle state
            sdaOutPin_.write(false); /* START condition */  // SDA LOW while SCL HIGH
            sclPin_.write(false);    // SCL LOW - ready for data
        }
        
        private void bme280Stop() throws ConnectionLostException, InterruptedException {
            setSDAOutput();
            sdaOutPin_.write(false); sclPin_.write(false); // Setup for STOP
            sclPin_.write(true);     // SCL HIGH first
            sdaOutPin_.write(true);  // STOP: SDA LOW→HIGH while SCL HIGH
        }
        
        private boolean bme280WriteByte(int data) throws ConnectionLostException, InterruptedException {
            setSDAOutput();
            
            // 8 data bits - Maximum speed (Android GPIO limiti)
            for (int i = 7; i >= 0; i--) {
                sclPin_.write(false);  // SCL LOW
                sdaOutPin_.write((data & (1 << i)) != 0);  // Set data bit
                sclPin_.write(true);   // SCL HIGH - immediate
            }
            
            // ACK bit - No delays for maximum speed
            sclPin_.write(false);     // SCL LOW for ACK
            setSDAInput();            // Switch to input
            sclPin_.write(true);      // SCL HIGH - read ACK
            boolean ack = !sdaInPin_.read();  // ACK = LOW
            sclPin_.write(false);     // SCL LOW
            return ack;
        }
        
        private int bme280ReadByte(boolean sendAck) throws ConnectionLostException, InterruptedException {
            setSDAInput();
            int data = 0;
            
            // Read 8 data bits - Maximum speed
            for (int i = 7; i >= 0; i--) {
                sclPin_.write(false); // SCL LOW
                sclPin_.write(true);  // SCL HIGH - immediate
                if (sdaInPin_.read()) data |= (1 << i); // Read bit during HIGH
            }
            
            // Send ACK/NACK - No delays for maximum speed  
            sclPin_.write(false);     // SCL LOW
            setSDAOutput();           // Switch to output
            sdaOutPin_.write(!sendAck); // ACK=LOW, NACK=HIGH
            sclPin_.write(true);      // Clock ACK/NACK
            sclPin_.write(false);     // SCL LOW
            return data;
        }

        @Override
        protected void loop() throws ConnectionLostException, InterruptedException {
            // LED durumlarını güncelle
            statLed_.write(!statLedDurum_);
            led1_.write(led1Durum_);
            
            // BME280 Pin durumlarını güncelle
            pin9_.write(pin9Durum_);
            pin10_.write(pin10Durum_);
            pin11_.write(pin11Durum_);
            pin12_.write(pin12Durum_);
            pin13_.write(pin13Durum_);
            pin39_.write(pin39Durum_);
            pin15_.write(pin15Durum_);
            
            // PWM durumunu güncelle
            if (pwmAktif_) {
                pwmPin_.setDutyCycle(pwmDeger_);
            } else {
                pwmPin_.setDutyCycle(0);
            }
            
            // SPI sürekli test
            if (spiTestAktif_ && spi_ != null && spiHazir_) {
                try {
                    // Test verisi: 0xAA, 0x55 (1010 1010, 0101 0101 binary)
                    byte[] testData = {(byte) 0x00, (byte) 0x55};
                    byte[] response = new byte[2];
                    spi_.writeRead(testData, testData.length, testData.length, response, response.length);
                    
                    final StringBuilder responseBuilder = new StringBuilder();
                    responseBuilder.append("SPI Test - Gönderilen: ");
                    for (byte b : testData) {
                        responseBuilder.append(String.format("0x%02X ", b));
                    }
                    responseBuilder.append("\nAlınan: ");
                    for (byte b : response) {
                        responseBuilder.append(String.format("0x%02X ", b));
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            spiResponseText_.setText(responseBuilder.toString());
                        }
                    });
                    
                    Thread.sleep(100); // 10Hz test
                } catch (Exception e) {
                    spiTestAktif_ = false;
                    final String errorMsg = "SPI Test Hatası: " + e.getMessage();
                    
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                            spiTestButton_.setText("SPI Test: KAPALI");
                            spiResponseText_.setText(errorMsg);
                        }
                    });
                }
            }
            
            // I2C Clock butonu: TEK işlem kontrolü
            if (i2cClockAktif_ && i2cHazir_) {
                try {
                    // BME280'e tek seferlik ID register oku
                    performSingleI2CTransaction();
                    
                    // İşlem tamamlandı - flag'i sıfırla
                    i2cClockAktif_ = false;
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            i2cSendButton_.setText("I²C Send: TAMAMLANDI");
                        }
                    });
                    
                } catch (Exception e) {
                    i2cClockAktif_ = false;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            i2cSendButton_.setText("I²C Send: HATA");
                        }
                    });
                }
            }
            
            // BME280 I2C test - Tek işlem moduna çevrildi
            if (i2cTestAktif_ && twiHazir_ && bme280_ != null) {
                try {
                    int chipId = bme280_.readChipId();
                    final String resultText = String.format("BME280 Test:\nChip ID: 0x%02X\nDurum: %s", 
                        chipId, (chipId == 0x60 ? "BAŞARILI!" : "HATALI ID"));
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            i2cResponseText_.setText(resultText);
                            i2cTestButton_.setText("I²C Test: TAMAMLANDI");
                        }
                    });
                    
                    // Test tamamlandı
                    i2cTestAktif_ = false;
                    
                } catch (Exception e) {
                    i2cTestAktif_ = false;
                    final String errorMsg = "BME280 Test Hatası: " + e.getMessage();
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            i2cTestButton_.setText("I²C Test: HATA");
                            i2cResponseText_.setText(errorMsg);
                        }
                    });
                }
            }
            
            // ADC sürekli okuma
            if (adcSurekliAktif_ && adcPin_ != null && adcHazir_) {
                try {
                    // ADC değerini oku (0.0 - 1.0 arası, 3.3V referans)
                    float adcValue = adcPin_.read();
                    
                    // Değerleri hesapla
                    
                    int rawValue = (int)(adcValue * 4095); // 12-bit (0-4095)
                    float voltage = adcValue * 3.3f; // Gerilim (0-3.3V)
                    
                    final String resultText = String.format("ADC Okuması:\nHam Değer: %d / 4095\nGerilim: %.3f V\nYüzde: %.1f%%", 
                                                           rawValue, voltage, adcValue * 100);
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            adcSonucText_.setText(resultText);
                        }
                    });
                    
                    Thread.sleep(200); // 5Hz okuma hızı
                } catch (Exception e) {
                    adcSurekliAktif_ = false;
                    final String errorMsg = "ADC Okuma Hatası: " + e.getMessage();
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            adcSurekliButton_.setText("ADC: KAPALI");
                            adcSonucText_.setText(errorMsg);
                        }
                    });
                }
            }
            
            Thread.sleep(50); // Ana döngü hızı
        }

        @Override
        protected void disconnected() {
            // Test durumlarını kapat
            spiTestAktif_ = false;
            i2cTestAktif_ = false;
            i2cClockAktif_ = false;
            adcSurekliAktif_ = false;
            
            // Bağlantı durumunu güncelle
            preferences.edit().putBoolean("IOIO_CONNECTED", false).apply();
            
            // Kaynakları temizle
            try {
                if (statLed_ != null) {
                    statLed_.close();
                    statLed_ = null;
                }
                if (led1_ != null) {
                    led1_.close();
                    led1_ = null;
                }
                if (pwmPin_ != null) {
                    pwmPin_.close();
                    pwmPin_ = null;
                }
                
                // BME280 Pinlerini temizle
                if (pin9_ != null) {
                    pin9_.close();
                    pin9_ = null;
                }
                if (pin10_ != null) {
                    pin10_.close();
                    pin10_ = null;
                }
                if (pin11_ != null) {
                    pin11_.close();
                    pin11_ = null;
                }
                if (pin12_ != null) {
                    pin12_.close();
                    pin12_ = null;
                }
                if (pin13_ != null) {
                    pin13_.close();
                    pin13_ = null;
                }
                if (pin39_ != null) {
                    pin39_.close();
                    pin39_ = null;
                }
                if (pin15_ != null) {
                    pin15_.close();
                    pin15_ = null;
                }
                
                if (spi_ != null) {
                    spi_.close();
                    spi_ = null;
                }
                
                // Manuel I2C GPIO pinleri kapat
                if (sclPin_ != null) {
                    sclPin_.close();
                    sclPin_ = null;
                }
                if (csbPin_ != null) {
                    csbPin_.close();
                    csbPin_ = null;
                }
                if (sdaOutPin_ != null) {
                    sdaOutPin_.close();
                    sdaOutPin_ = null;
                }
                if (sdaInPin_ != null) {
                    sdaInPin_.close();
                    sdaInPin_ = null;
                }
                if (adcPin_ != null) {
                    adcPin_.close();
                    adcPin_ = null;
                }
            } catch (Exception e) {
                // Kapatma hatalarını sessizce geç
            }
            
            spiHazir_ = false;
            i2cHazir_ = false;
            adcHazir_ = false;
            
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // UI güncellemeleri
                    statLedButton_.setText("Stat LED: KAPALI");
                    led1Button_.setText("LED 1: KAPALI");
                    pwmButton_.setText("PWM: KAPALI");
                    
                    // BME280 Pin butonlarını sıfırla
                    pin9Button_.setText("Pin 9\nKAPALI");
                    pin10Button_.setText("Pin 10\nKAPALI");
                    pin11Button_.setText("Pin 11\nKAPALI");
                    pin12Button_.setText("Pin 12\nKAPALI");
                    pin13Button_.setText("Pin 13\nKAPALI");
                    pin39Button_.setText("Pin 16\nKAPALI");
                    pin15Button_.setText("Pin 15\nKAPALI");
                    
                    pin9Button_.setEnabled(false);
                    pin10Button_.setEnabled(false);
                    pin11Button_.setEnabled(false);
                    pin12Button_.setEnabled(false);
                    pin13Button_.setEnabled(false);
                    pin39Button_.setEnabled(false);
                    pin15Button_.setEnabled(false);
                    
                    spiTestButton_.setText("SPI Test: KAPALI");
                    i2cTestButton_.setText("I²C Test: KAPALI");
                    i2cSendButton_.setText("I²C Clock: KAPALI");
                    adcSurekliButton_.setText("ADC: KAPALI");
                    pwmSeekBar_.setEnabled(false);
                    spiDataInput_.setEnabled(false);
                    spiSendButton_.setEnabled(false);
                    spiTestButton_.setEnabled(false);
                    i2cAdresInput_.setEnabled(false);
                    i2cDataInput_.setEnabled(false);
                    i2cSendButton_.setEnabled(false);
                    i2cTestButton_.setEnabled(false);
                    adcOkuButton_.setEnabled(false);
                    adcSurekliButton_.setEnabled(false);
                    statusText_.setText("IOIO Bağlantısı Kesildi - Yeniden bağlanılıyor...");
                    pwmDurumText_.setText("PWM Durumu: Kullanılamıyor");
                    spiResponseText_.setText("SPI Yanıtı: -");
                    i2cResponseText_.setText("I²C Yanıtı: -");
                    adcSonucText_.setText("ADC Sonucu: -");
                    
                    // Otomatik yeniden bağlantı denemesi
                    Toast.makeText(LEDKontrolActivity.this, "IOIO bağlantısı kesildi - Yeniden bağlanılıyor...", 
                            Toast.LENGTH_SHORT).show();
                }
            });
            
            // Otomatik yeniden bağlantı için gecikme
            reconnectHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                statusText_.setText("Yeniden bağlantı deneniyor...");
                            }
                        });
                        attemptAutoReconnect();
                    }
                }
            }, RECONNECT_DELAY);
        }

        @Override
        protected void incompatible() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "IOIO versiyonu uyumsuz!", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    // BME280 I2C tek işlem metodu - Her basışta yeni işlem
    private void sendI2cData() {
        if (currentThread_ == null || !currentThread_.i2cHazir_) {
            i2cResponseText_.setText("BME280 I2C Error: GPIO not ready");
            return;
        }
        
        // Eğer işlem devam ediyorsa ignore et
        if (i2cClockAktif_) {
            i2cResponseText_.setText("BME280 I2C: İşlem devam ediyor, bekleyin...");
            return;
        }
        
        // Yeni tek işlem başlat
        i2cClockAktif_ = true;
        i2cSendButton_.setText("I²C Send: İŞLEM...");
        i2cResponseText_.setText("BME280 I2C transaction starting...");
    }
    
    // ADC tek seferlik okuma metodu
    private void readAdcOnce() {
        if (currentThread_ == null || currentThread_.adcPin_ == null || !currentThread_.adcHazir_) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    adcSonucText_.setText("ADC Hatası: ADC kullanılamıyor");
                }
            });
            return;
        }
        
        try {
            // ADC değerini oku
            float adcValue = currentThread_.adcPin_.read();
            
            // Değerleri hesapla
            int rawValue = (int)(adcValue * 4095); // 12-bit (0-4095)
            float voltage = adcValue * 3.3f; // Gerilim (0-3.3V)
            
            final String resultText = String.format("ADC Tek Okuma:\nHam Değer: %d / 4095\nGerilim: %.3f V\nYüzde: %.1f%%\nPin 31'den okundu", 
                                                   rawValue, voltage, adcValue * 100);
            
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    adcSonucText_.setText(resultText);
                }
            });
            
        } catch (Exception e) {
            final String errorMsg = "ADC Okuma Hatası: " + e.getMessage();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    adcSonucText_.setText(errorMsg);
                }
            });
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
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
    }
} 