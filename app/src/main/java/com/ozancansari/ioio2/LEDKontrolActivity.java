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

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import ioio.lib.api.DigitalOutput;
import ioio.lib.api.IOIO;
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
    private Button[] ledButtons_;
    private TextView statusText_;
    
    // LED durumları
    private boolean statLedDurum_ = false;
    private boolean led1Durum_ = false; // Pin 1 için
    private boolean[] ledDurumlar_ = {false, false, false, false}; // Pin 2,3,4,5 için
    
    @Override
    protected boolean shouldWaitForConnect() {
        return true; // IOIO bağlantısı için bekle
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_led_kontrol);
        
        // UI elemanlarını bul
        statusText_ = findViewById(R.id.statusText);
        statLedButton_ = findViewById(R.id.statLedButton);
        led1Button_ = findViewById(R.id.led1Button);
        
        ledButtons_ = new Button[4];
        ledButtons_[0] = findViewById(R.id.led2Button);
        ledButtons_[1] = findViewById(R.id.led3Button);
        ledButtons_[2] = findViewById(R.id.led4Button);
        ledButtons_[3] = findViewById(R.id.led5Button);
        
        // Bluetooth izinlerini kontrol et ve iste
        checkBluetoothPermissions();
    }
    
    private void checkBluetoothPermissions() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        
        if (bluetoothAdapter == null) {
            statusText_.setText("Bu cihazda Bluetooth desteklenmiyor!");
            return;
        }
        
        // Android 12+ için yeni izinleri kontrol et
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
                    != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) 
                    != PackageManager.PERMISSION_GRANTED) {
                
                ActivityCompat.requestPermissions(this, 
                    new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    }, REQUEST_BLUETOOTH_PERMISSIONS);
                return;
            }
        }
        
        // Bluetooth'un açık olup olmadığını kontrol et
        if (!bluetoothAdapter.isEnabled()) {
            statusText_.setText("Bluetooth'u açmak gerekiyor...");
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            statusText_.setText("Bluetooth hazır, IOIO bağlantısı bekleniyor...");
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
                checkBluetoothPermissions(); // Tekrar kontrol et
            } else {
                statusText_.setText("Bluetooth izinleri gerekli!");
                Toast.makeText(this, "IOIO bağlantısı için Bluetooth izinleri gerekli", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                statusText_.setText("Bluetooth açıldı, IOIO bağlantısı bekleniyor...");
            } else {
                statusText_.setText("Bluetooth gerekli!");
                Toast.makeText(this, "IOIO bağlantısı için Bluetooth gerekli", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected IOIOThread createIOIOThread() {
        return new LEDKontrolThread();
    }

    /**
     * IOIO iletişimi için özel thread sınıfı
     */
    private class LEDKontrolThread extends IOIOThread {
        private DigitalOutput statLed_;
        private DigitalOutput led1_;
        private DigitalOutput[] leds_;

        @Override
        protected void setup() throws ConnectionLostException {
            // Stat LED'i aç (Pin 0)
            statLed_ = ioio_.openDigitalOutput(IOIO.LED_PIN, false);
            
            // Pin 1 LED'ini aç
            led1_ = ioio_.openDigitalOutput(1, false);
            
            // Diğer LED'leri aç (Pin 2,3,4,5)
            leds_ = new DigitalOutput[4];
            for (int i = 0; i < 4; i++) {
                leds_[i] = ioio_.openDigitalOutput(i + 2, false);
            }
            
            // UI'ı ana thread'de güncelle
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    statusText_.setText("IOIO bağlandı! LED'leri kontrol edebilirsiniz.");
                    
                    // Butonları etkinleştir ve click listener'ları ekle
                    statLedButton_.setEnabled(true);
                    statLedButton_.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            statLedDurum_ = !statLedDurum_;
                        }
                    });
                    
                    // Pin 1 LED butonu
                    led1Button_.setEnabled(true);
                    led1Button_.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            led1Durum_ = !led1Durum_;
                        }
                    });
                    
                    for (int i = 0; i < 4; i++) {
                        final int pinIndex = i;
                        ledButtons_[i].setEnabled(true);
                        ledButtons_[i].setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                ledDurumlar_[pinIndex] = !ledDurumlar_[pinIndex];
                            }
                        });
                    }
                }
            });
        }

        @Override
        protected void loop() throws ConnectionLostException, InterruptedException {
            // Stat LED'i güncelle
            statLed_.write(statLedDurum_);
            
            // Pin 1 LED'ini güncelle
            led1_.write(led1Durum_);
            
            // Diğer LED'leri güncelle
            for (int i = 0; i < 4; i++) {
                leds_[i].write(ledDurumlar_[i]);
            }
            
            // UI'ı güncelle
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    statLedButton_.setText("Stat LED: " + (statLedDurum_ ? "AÇIK" : "KAPALI"));
                    led1Button_.setText("LED 1: " + (led1Durum_ ? "AÇIK" : "KAPALI"));
                    for (int i = 0; i < 4; i++) {
                        ledButtons_[i].setText("LED " + (i + 2) + ": " + (ledDurumlar_[i] ? "AÇIK" : "KAPALI"));
                    }
                }
            });
            
            // Kısa bekleme
            Thread.sleep(100);
        }

        @Override
        protected void disconnected() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    statusText_.setText("IOIO bağlantısı kesildi!");
                    statLedButton_.setEnabled(false);
                    led1Button_.setEnabled(false);
                    for (int i = 0; i < 4; i++) {
                        ledButtons_[i].setEnabled(false);
                    }
                }
            });
        }

        @Override
        protected void incompatible() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    statusText_.setText("Uyumsuz IOIO firmware versiyonu!");
                }
            });
        }
    }
} 