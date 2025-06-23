package com.ozancansari.ioio2;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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
    
    @Override
    protected boolean shouldWaitForConnect() {
        return true;
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_led_kontrol);
        
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
        mainMenuButton.setOnClickListener(v -> {
            Intent intent = new Intent(LEDKontrolActivity.this, MainActivity.class);
            startActivity(intent);
        });
    }
    

    
    private void initializeBluetooth() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        
        if (bluetoothAdapter == null) {
            statusText_.setText("Bu cihazda Bluetooth desteklenmiyor!");
            return;
        }
        
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
                // Kısa gecikme ile bağlantıyı başlat
                statusText_.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        statusText_.setText("IOIO bağlantısı bekleniyor...");
                    }
                }, 1000);
            }
        } catch (SecurityException e) {
            statusText_.setText("Bluetooth güvenlik hatası - İzinleri kontrol edin");
            Toast.makeText(this, "Android 12+ için Bluetooth izinleri gerekli", Toast.LENGTH_LONG).show();
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
        private TwiMaster twi_;
        private AnalogInput adcPin_;
        private boolean spiHazir_ = false;
        private boolean i2cHazir_ = false;
        private boolean adcHazir_ = false;

        @Override
        protected void setup() throws ConnectionLostException {
            try {
                // LED ve PWM çıkışlarını başlat
            statLed_ = ioio_.openDigitalOutput(IOIO.LED_PIN, false);
            led1_ = ioio_.openDigitalOutput(1, false);
                pwmPin_ = ioio_.openPwmOutput(3, 1000); // Pin 3, 1kHz frekans
                
                
                // SPI Master'ı başlat (hata olursa sadece uyarı ver)
                try {
                    if (ioio_ != null) {
                        // Daha yüksek rate ile başlayalım - 31K çok yavaş olabilir
                        spi_ = ioio_.openSpiMaster(10, 11, 12, new int[]{13}, SpiMaster.Rate.RATE_31K);
                        spiHazir_ = true;
                        Thread.sleep(200); // SPI başlatma sonrası daha uzun bekleme
                    }
                } catch (Exception e) {
                    spiHazir_ = false;
                    spi_ = null;
                    final String errorMsg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "SPI başlatılamadı: " + errorMsg, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                
                // I²C (TWI) Master'ı başlat
                try {
                    if (ioio_ != null) {
                        // TWI0 modülü deneyelim - Pin 4/5 kullanır
                        twi_ = ioio_.openTwiMaster(0, TwiMaster.Rate.RATE_100KHz, false);
                        i2cHazir_ = true;
                        Thread.sleep(200); // I²C başlatma sonrası bekleme
                    }
                } catch (Exception e) {
                    i2cHazir_ = false;
                    twi_ = null;
                    final String errorMsg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "I²C başlatılamadı: " + errorMsg, Toast.LENGTH_SHORT).show();
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
                
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                        statusText_.setText("IOIO bağlandı! Kontroller aktif.");
                    
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
                                        
                                        if (yeniDurum) {
                                            if (twi_ != null && i2cHazir_) {
                                                i2cClockAktif_ = true;
                                                i2cResponseText_.setText("I²C Clock Aktif!\nPin 5 (SCL) sürekli clock\nMinimal START-STOP cycle'ları");
                                                i2cSendButton_.setText("I²C Clock: AÇIK");
                                            } else {
                                                i2cClockAktif_ = false;
                                                i2cSendButton_.setText("I²C Clock: KAPALI");
                                                i2cResponseText_.setText("I²C Hatası: I²C hazır değil");
                                            }
                                        } else {
                                            i2cClockAktif_ = false;
                                            i2cResponseText_.setText("I²C Clock Durduruldu");
                                            i2cSendButton_.setText("I²C Clock: KAPALI");
                                        }
                                    } catch (Exception e) {
                                        i2cClockAktif_ = false;
                                        i2cSendButton_.setText("I²C Clock: KAPALI");
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
                                        
                                        if (yeniDurum) {
                                            if (twi_ != null && i2cHazir_) {
                                                i2cTestAktif_ = true;
                                                i2cResponseText_.setText("I²C Hızlı Test (No-ACK):\nPin 5 (SCL) - Hızlı Burst\nACK beklemeden transfer\nOsiloskop: 20µs/div, AC coupling");
                                                i2cTestButton_.setText("I²C Test: AÇIK");
                                            } else {
                                                i2cTestAktif_ = false;
                                                i2cTestButton_.setText("I²C Test: KAPALI");
                                                i2cResponseText_.setText("I²C Hatası: I²C hazır değil veya null");
                                            }
                                        } else {
                                            i2cTestAktif_ = false;
                                            i2cTestButton_.setText("I²C Test: KAPALI");
                                            i2cResponseText_.setText("I²C Sürekli Test Durduruldu");
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
                final String finalText = inputText.isEmpty() ? "0x41" : inputText;
                
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
                byte[] writeData = {dataToSend, dataToSend, dataToSend, dataToSend}; // 4 byte gönder
                byte[] readData = new byte[4]; // 4 byte oku
                
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        spiResponseText_.setText("SPI transferi yapılıyor... (CLK sinyalini kontrol edin)");
                    }
                });
                
                // 10 kez SPI transferi yap (CLK sinyalinin görülmesi için)
                for (int i = 0; i < 10; i++) {
                    spi_.writeRead(0, writeData, 4, 4, readData, 4); // Slave 0, 4 byte transfer
                    Thread.sleep(100); // Transfer arası bekleme
                }
                
                final String response = String.format("Transfer tamamlandı!\nGönderilen: 0x%02X (4 byte x 10 kez)\nAlınan son: 0x%02X", 
                    dataToSend & 0xFF, readData[0] & 0xFF);
                
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

        @Override
        protected void loop() throws ConnectionLostException, InterruptedException {
            // LED durumlarını güncelle
            statLed_.write(!statLedDurum_);
            led1_.write(led1Durum_);
            
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
                    byte[] testData = {(byte) 0xAA, (byte) 0x55};
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
            
            // I²C clock toggle - minimal cycle'lar
            if (i2cClockAktif_ && twi_ != null && i2cHazir_) {
                try {
                    // En minimal I²C transfer - sadece START-STOP
                    // Boş veri ile sadece clock oluştur
                    byte[] emptyData = {}; // Hiç veri yok
                    byte[] readData = new byte[0];
                    
                    // Minimal adresle sadece START-ADDRESS-STOP cycle'ı
                    // 0x00 adresi - genellikle boş/invalid
                    TwiMaster.Result result = twi_.writeReadAsync(0x00, false, emptyData, 0, readData, 0);
                    // Result'ı beklemiyoruz - sadece clock sinyali istiyoruz
                    
                    Thread.sleep(10); // 100Hz clock cycle
                } catch (Exception e) {
                    // Hataları sessizce geç, clock devam etsin
                }
            }
            
            // I²C sürekli test  
            if (i2cTestAktif_ && twi_ != null && i2cHazir_) {
                try {
                    // Hızlı asenkron I²C - yanıt beklemiyor
                    int[] testAddresses = {0x48, 0x50, 0x68}; // Daha az adres, daha hızlı
                    byte[] testData = {0x00, (byte)0xFF, 0x55}; // 3 farklı pattern
                    byte[] readData = new byte[0]; // Hiç okuma yapma
                    
                    StringBuilder resultText = new StringBuilder("I²C Hızlı Test (No-ACK):\n");
                    
                    // Async transferler - yanıt beklemeden devam et
                    for (int addr : testAddresses) {
                        for (byte data : testData) {
                            try {
                                byte[] singleData = {data};
                                // Async kullan - bekleme yok
                                TwiMaster.Result result = twi_.writeReadAsync(addr, false, singleData, 1, readData, 0);
                                // Result'ı beklemiyoruz - sadece gönder ve devam et
                                Thread.sleep(1); // Minimum aralar
                            } catch (Exception e) {
                                // Hataları sessizce geç
                            }
                        }
                    }
                    
                    resultText.append("Pin 5 (SCL) - Hızlı Burst\n");
                    resultText.append("ACK beklemeden transfer\n");
                    resultText.append("Osiloskop: 20µs/div, AC coupling");
                    
                    final String finalText = resultText.toString();
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            i2cResponseText_.setText(finalText);
                        }
                    });
                    
                    Thread.sleep(50); // 20Hz test - çok hızlı
                } catch (Exception e) {
                    i2cTestAktif_ = false;
                    final String errorMsg = "I²C Test Hatası: " + e.getMessage();
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            i2cTestButton_.setText("I²C Test: KAPALI");
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
                if (spi_ != null) {
                    spi_.close();
                    spi_ = null;
                }
                if (twi_ != null) {
                    twi_.close();
                    twi_ = null;
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
                    statusText_.setText("IOIO Bağlantısı Kesildi");
                    pwmDurumText_.setText("PWM Durumu: Kullanılamıyor");
                    spiResponseText_.setText("SPI Yanıtı: -");
                    i2cResponseText_.setText("I²C Yanıtı: -");
                    adcSonucText_.setText("ADC Sonucu: -");
                }
            });
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

    // I²C veri gönderme metodu
    private void sendI2cData() {
        if (currentThread_ == null || currentThread_.twi_ == null || !currentThread_.i2cHazir_) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    i2cResponseText_.setText("I²C Hatası: I²C kullanılamıyor");
                }
            });
            return;
        }
        
        try {
            String adresText = i2cAdresInput_.getText().toString().trim();
            String dataText = i2cDataInput_.getText().toString().trim();
            
            // Varsayılan değerler
            if (adresText.isEmpty()) {
                adresText = "0x48";
            }
            if (dataText.isEmpty()) {
                dataText = "0x00";
            }
            
            // Hex string'i int/byte'a çevir
            int slaveAddress;
            if (adresText.startsWith("0x") || adresText.startsWith("0X")) {
                slaveAddress = Integer.parseInt(adresText.substring(2), 16);
            } else {
                slaveAddress = Integer.parseInt(adresText, 16);
            }
            
            byte data;
            if (dataText.startsWith("0x") || dataText.startsWith("0X")) {
                data = (byte) Integer.parseInt(dataText.substring(2), 16);
            } else {
                data = (byte) Integer.parseInt(dataText, 16);
            }
            
            // I²C transfer'ı gerçekleştir - async mode
            byte[] sendData = {data};
            byte[] readData = new byte[0]; // Sadece yazma işlemi
            
            try {
                // Async transfer - ACK beklemesini minimize et
                TwiMaster.Result result = currentThread_.twi_.writeReadAsync(slaveAddress, false, sendData, sendData.length, readData, 0);
                
                // Kısa süre bekle ve result'ı kontrol et
                Thread.sleep(10); // 10ms bekle
                boolean success = false;
                try {
                    success = result.waitReady(); // Hızlıca kontrol et
                } catch (Exception e) {
                    // Timeout olursa success false kalır
                }
                
                final String responseText = String.format("I²C Hızlı Transfer:\nAdres: 0x%02X\nVeri: 0x%02X\nDurum: %s\n%s", 
                                                          slaveAddress, data, 
                                                          success ? "BAŞARILI (ACK)" : "GÖNDERILDI (TIMEOUT/NACK)",
                                                          "Clock sinyali Pin 5'te");
                
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        i2cResponseText_.setText(responseText);
                    }
                });
            } catch (Exception asyncEx) {
                final String errorMsg = "I²C Async Hatası: " + asyncEx.getMessage();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        i2cResponseText_.setText(errorMsg);
                    }
                });
            }
            
        } catch (NumberFormatException e) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    i2cResponseText_.setText("I²C Hatası: Geçersiz hex formatı (örn: 0x48)");
                }
            });
        } catch (Exception e) {
            final String errorMsg = e.getMessage();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    i2cResponseText_.setText("I²C Hatası: " + errorMsg);
                }
            });
        }
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
} 