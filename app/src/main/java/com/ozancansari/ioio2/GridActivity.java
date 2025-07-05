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
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import ioio.lib.api.DigitalOutput;
import ioio.lib.api.IOIO;
import ioio.lib.api.SpiMaster;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.android.AbstractIOIOActivity;

public class GridActivity extends AbstractIOIOActivity {
    
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1001;
    private static final int REQUEST_ENABLE_BT = 1002;
    
    // UI elemanları
    private TextView tvLelValue;
    private TextView tvSpiData;
    private Button btnClose;
    
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
    protected void onCreate(Bundle savedInstanceState) {
        try {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.grid_activity);
        
        // SharedPreferences başlat
        preferences = getSharedPreferences("IOIO_PREFS", MODE_PRIVATE);
            reconnectHandler = new Handler();
            spiHandler = new Handler();
        } catch (Exception e) {
            Log.e("GridActivity", "onCreate başlatma hatası: " + e.getMessage());
            e.printStackTrace();
            finish();
            return;
        }
        
        // UI elemanlarını güvenli şekilde bağla
        try {
        tvLelValue = findViewById(R.id.tvLelValue);
            tvSpiData = findViewById(R.id.tvSpiData); // SPI verileri için yeni TextView
        btnClose = findViewById(R.id.btnClose);
            
            // Layout kontrolü
            if (tvLelValue == null) {
                Log.e("GridActivity", "tvLelValue bulunamadı!");
            }
            if (tvSpiData == null) {
                Log.e("GridActivity", "tvSpiData bulunamadı! Layout kontrol edin.");
                // Geçici çözüm - SPI verileri için tvLelValue kullan
                tvSpiData = tvLelValue;
            }
            if (btnClose == null) {
                Log.e("GridActivity", "btnClose bulunamadı!");
            }
        } catch (Exception e) {
            Log.e("GridActivity", "UI elemanları başlatılırken hata: " + e.getMessage());
            // Crash önleme
            finish();
            return;
        }
        
        // Kapat butonu
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                finish();
            }
        });

        // Bluetooth kontrolü
        initializeBluetooth();
        
        // LEL güncelleme başlat
        updateLelValue();
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
        IOIOSpiThread thread = new IOIOSpiThread();
        currentThread_ = thread;
        return thread;
    }
    
    /**
     * IOIO SPI Thread sınıfı
     */
    private class IOIOSpiThread extends IOIOThread {
        private SpiMaster spi_;
        private DigitalOutput pin12_;
        private DigitalOutput pin39_;
        private boolean spiHazir_ = false;
        
        public boolean isSpiHazir() {
            return spiHazir_;
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
                    
                    // SPI hazır
                    spiHazir_ = true;
                    
                    // Bağlantı durumunu güncelle
                    preferences.edit().putBoolean("IOIO_CONNECTED", true).apply();
                    reconnectAttempts = 0;
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tvLelValue.setText("IOIO bağlandı!");
                            tvSpiData.setText("SPI başlatılıyor...");
                            
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
            Thread.sleep(100); // Ana döngü
        }
        
        @Override
        protected void disconnected() {
            spiCalisiyorMu = false;
            spiHazir_ = false;
            
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
            } catch (Exception e) {
                // Sessizce geç
            }
            
            runOnUiThread(new Runnable() {
                @Override
            public void run() {
                    tvLelValue.setText("IOIO bağlantısı kesildi - Yeniden bağlanılıyor...");
                    tvSpiData.setText("SPI bağlantısı kesildi");
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
                // Test verisi - 5 byte gönder
                byte[] writeData = {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
                byte[] readData = new byte[5];
                
                // Pin 39'u kontrol et
                try {
                    // Select aktif (LOW)
                    pin39_.write(false);
                    Thread.sleep(10);
                    
                    // Veriyi gönder
                    Log.d("GridActivity", "SPI veri gönderiliyor: 5 byte");
                    spi_.writeRead(0, writeData, writeData.length, writeData.length, readData, readData.length);
                    
                    // Select pasif (HIGH)
                    pin39_.write(true);
                    
                    // Veriyi göster ve grid'i güncelle
                    StringBuilder displayText = new StringBuilder("SPI Veriler:\n");
                    final byte[] finalData = readData.clone(); // UI thread için veriyi kopyala
                    
                    for (int i = 0; i < readData.length; i++) {
                        displayText.append(String.format("Byte %d: 0x%02X   ", i+1, readData[i] & 0xFF));
                        String binary = String.format("%8s", Integer.toBinaryString(readData[i] & 0xFF))
                                            .replace(' ', '0');
                        displayText.append("(" + binary + ")   ");
                    }
                    
                    final String finalText = displayText.toString();
                    Log.i("GridActivity", "SPI veriler alındı: " + finalText);
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tvSpiData.setText(finalText);
                            updateGridColors(finalData);
                        }
                    });
                    
                    // Başarılı transfer sonrası bekle
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
                final String errorMsg = "SPI Hatası: " + e.getMessage();
                Log.e("GridActivity", errorMsg, e);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvSpiData.setText(errorMsg);
                    }
                });
                
                // Hata durumunda SPI'ı yeniden başlatmayı dene
                spiHazir_ = false;
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (ioio_ != null) {
                                setup();
                            }
                        } catch (Exception ex) {
                            Log.e("GridActivity", "SPI yeniden başlatma hatası", ex);
                        }
                    }
                }, 2000);
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
            // Her byte için (5 satır) - Yukarıdan aşağıya
            for (int row = 0; row < data.length; row++) {
                byte currentByte = data[row];
                
                // Her satırdaki başlangıç hücre numarası (yukarıdan aşağıya)
                // byte 1: 29-35, byte 2: 22-28, byte 3: 15-21, byte 4: 8-14, byte 5: 1-7
                int startCell = (5 - row) * 7 - 6;  // 29, 22, 15, 8, 1
                
                // Debug için satır bilgisi
                Log.d("GridActivity", String.format("Byte %d işleniyor -> Hücreler %d-%d", 
                    row+1, startCell, startCell+6));
                
                // Bit sıralaması aynı: 7->1, 6->2, 8->7, 1->3, 2->4, 3->5, 4->6
                // 5. bit kullanılmıyor
                
                // 7. bit -> 1. göz
                updateCell(startCell + 0, (currentByte & 0b01000000) != 0);
                
                // 6. bit -> 2. göz
                updateCell(startCell + 1, (currentByte & 0b00100000) != 0);
                
                // 1. bit -> 3. göz
                updateCell(startCell + 2, (currentByte & 0b00000001) != 0);
                
                // 2. bit -> 4. göz
                updateCell(startCell + 3, (currentByte & 0b00000010) != 0);
                
                // 3. bit -> 5. göz
                updateCell(startCell + 4, (currentByte & 0b00000100) != 0);
                
                // 4. bit -> 6. göz
                updateCell(startCell + 5, (currentByte & 0b00001000) != 0);
                
                // 8. bit -> 7. göz
                updateCell(startCell + 6, (currentByte & 0b10000000) != 0);
                
                // Binary gösterim için debug log
                String binaryStr = String.format("%8s", Integer.toBinaryString(currentByte & 0xFF))
                                      .replace(' ', '0');
                Log.d("GridActivity", String.format("Satır %d (Hücreler %d-%d): 0x%02X (%s)", 
                    row+1, startCell, startCell+6, currentByte, binaryStr));
            }
        } catch (Exception e) {
            Log.e("GridActivity", "Grid güncelleme hatası: " + e.getMessage());
        }
    }
    
    // Tek bir hücreyi güncelle
    private void updateCell(int cellNumber, boolean isRed) {
        try {
            String cellId = "cell_" + (cellNumber - 1); // cell_0'dan başladığı için -1
            int resId = getResources().getIdentifier(cellId, "id", getPackageName());
            
            if (resId != 0) {
                TextView cell = findViewById(resId);
                if (cell != null) {
                    cell.setBackgroundResource(isRed ? R.drawable.bg_gray_border : R.drawable.bg_grid_normal);
                    
                    // Debug için log
                    Log.d("GridActivity", String.format("Hücre %d (%s) -> %s", 
                        cellNumber, cellId, isRed ? "GRİ" : "NORMAL"));
                }
            }
        } catch (Exception e) {
            Log.e("GridActivity", "Hücre güncelleme hatası: " + e.getMessage());
        }
    }
} 