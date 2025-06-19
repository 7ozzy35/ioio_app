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

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import ioio.lib.api.DigitalOutput;
import ioio.lib.api.IOIO;
import ioio.lib.api.PwmOutput;
import ioio.lib.api.SpiMaster;
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
    private SeekBar pwmSeekBar_;
    private EditText spiDataInput_;
    private TextView statusText_;
    private TextView pwmDurumText_;
    private TextView spiResponseText_;
    
    // LED, PWM ve SPI durumları
    private boolean statLedDurum_ = false;
    private boolean led1Durum_ = false;
    private boolean pwmAktif_ = false;
    private boolean spiTestAktif_ = false;
    private float pwmDeger_ = 0.5f; // 0.0 - 1.0 arası
    
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
        statLedButton_ = findViewById(R.id.statLedButton);
        led1Button_ = findViewById(R.id.led1Button);
        pwmButton_ = findViewById(R.id.pwmButton);
        spiSendButton_ = findViewById(R.id.spiSendButton);
        spiTestButton_ = findViewById(R.id.spiTestButton);
        pwmSeekBar_ = findViewById(R.id.pwmSeekBar);
        spiDataInput_ = findViewById(R.id.spiDataInput);
        
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
    }
    
    private void initializeBluetooth() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        
        if (bluetoothAdapter == null) {
            statusText_.setText("Bu cihazda Bluetooth desteklenmiyor!");
            return;
        }
        
        // Android 6+ için izin kontrolü
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) 
                    != PackageManager.PERMISSION_GRANTED) {
                
                ActivityCompat.requestPermissions(this, 
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 
                    REQUEST_BLUETOOTH_PERMISSIONS);
                return;
            }
        }
        
        // Bluetooth açık kontrolü
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
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeBluetooth(); // Tekrar kontrol et
            } else {
                statusText_.setText("Konum izni gerekli (Android 6+)!");
                Toast.makeText(this, "Bluetooth tarama için konum izni gerekli", Toast.LENGTH_LONG).show();
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
        return new IOIOKontrolThread();
    }

    /**
     * IOIO iletişimi için özel thread sınıfı
     */
    private class IOIOKontrolThread extends IOIOThread {
        private DigitalOutput statLed_;
        private DigitalOutput led1_;
        private PwmOutput pwmPin_;
        private SpiMaster spi_;
        private boolean spiHazir_ = false;

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
                    }
                });
            } catch (ConnectionLostException e) {
                throw e;
            } catch (Exception e) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        statusText_.setText("IOIO bağlantı hatası: " + e.getMessage());
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
            
            // SPI sürekli test modu
            if (spiTestAktif_ && spiHazir_ && spi_ != null) {
                try {
                    // Daha kısa veri paketi ve daha az sıklık
                    byte[] testData = {(byte)0xAA, (byte)0x55}; // 2 byte test pattern
                    byte[] readData = new byte[2];
                    spi_.writeRead(0, testData, 2, 2, readData, 2);
                    Thread.sleep(100); // 10 Hz test hızı (daha yavaş)
                } catch (ConnectionLostException e) {
                    // IOIO bağlantısı kesildi, exception'ı yukarı fırlat
                    throw e;
                } catch (Exception e) {
                    // SPI hatası durumunda test modunu kapat
                    spiTestAktif_ = false;
                    final String errorMsg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (spiTestButton_ != null) {
                                spiTestButton_.setText("SPI Test: KAPALI");
                            }
                            if (spiResponseText_ != null) {
                                spiResponseText_.setText("SPI Test Hatası: " + errorMsg);
                            }
                        }
                    });
                    Thread.sleep(200); // Hata sonrası daha uzun bekleme
                }
            } else {
                Thread.sleep(50); // Normal bekleme - daha az CPU kullanımı
            }
        }

        @Override
        protected void disconnected() {
            spiTestAktif_ = false; // Test modunu hemen durdur
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        statusText_.setText("IOIO bağlantısı kesildi!");
                        if (statLedButton_ != null) statLedButton_.setEnabled(false);
                        if (led1Button_ != null) led1Button_.setEnabled(false);
                        if (pwmButton_ != null) pwmButton_.setEnabled(false);
                        if (pwmSeekBar_ != null) pwmSeekBar_.setEnabled(false);
                        if (spiSendButton_ != null) spiSendButton_.setEnabled(false);
                        if (spiTestButton_ != null) {
                            spiTestButton_.setEnabled(false);
                            spiTestButton_.setText("SPI Test: KAPALI");
                        }
                        if (spiDataInput_ != null) spiDataInput_.setEnabled(false);
                        if (spiResponseText_ != null) spiResponseText_.setText("IOIO bağlantısı kesildi");
                    } catch (Exception e) {
                        // UI güncelleme hatası durumunda sessizce devam et
                    }
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
} 