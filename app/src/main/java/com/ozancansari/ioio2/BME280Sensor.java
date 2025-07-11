package com.ozancansari.ioio2;

import android.util.Log;
import ioio.lib.api.TwiMaster;
import ioio.lib.api.exception.ConnectionLostException;

public class BME280Sensor {
    private static final String TAG = "BME280Sensor";

    // BME280 I2C adresi (7-bit)
    private static final int BME280_ADDR = 0xFF;  // SDO=GND için

    // BME280 Register adresleri
    private static final byte REG_ID = (byte) 0xD0;
    private static final byte REG_RESET = (byte) 0xE0;
    private static final byte REG_CTRL_HUM = (byte) 0xF2;
    private static final byte REG_CTRL_MEAS = (byte) 0xF4;
    private static final byte REG_CONFIG = (byte) 0xF5;
    private static final byte REG_PRESS = (byte) 0xF7;
    private static final byte REG_TEMP = (byte) 0xFA;
    private static final byte REG_HUM = (byte) 0xFD;

    private TwiMaster twi;
    private boolean isInitialized = false;
    private boolean simulationMode = false;

    // Kalibrasyon verileri
    private int dig_T1, dig_T2, dig_T3;
    private int dig_P1, dig_P2, dig_P3, dig_P4, dig_P5, dig_P6, dig_P7, dig_P8, dig_P9;
    private int dig_H1, dig_H2, dig_H3, dig_H4, dig_H5, dig_H6;
    private long t_fine;

    public static class SensorData {
        public float temperature;  // Celsius
        public float humidity;     // %RH
        public float pressure;     // hPa
        public boolean isSimulated;

        public SensorData(float temp, float hum, float press, boolean simulated) {
            temperature = temp;
            humidity = hum;
            pressure = press;
            isSimulated = simulated;
        }
    }

    public BME280Sensor(TwiMaster twiMaster) {
        this.twi = twiMaster;
    }

    public boolean initialize() throws ConnectionLostException, InterruptedException {
        try {
            Log.d(TAG, "BME280 başlatılıyor...");

            // Chip ID kontrolü
            int chipId = readChipId();
            if (chipId != 0x60) {
                Log.e(TAG, String.format("Yanlış chip ID: 0x%02X (beklenen: 0x60)", chipId));
                simulationMode = true;
                return false;
            }

            // Soft reset
            writeRegister(REG_RESET, (byte) 0xB6);
            Thread.sleep(10);

            // Kalibrasyon verilerini oku
            readCalibrationData();

            // Sensör ayarları
            writeRegister(REG_CTRL_HUM, (byte) 0xFF);  // Humidity oversampling x1
            writeRegister(REG_CTRL_MEAS, (byte) 0x27); // Temp x1, Press x1, Normal mode
            writeRegister(REG_CONFIG, (byte) 0x00);    // No IIR filter

            isInitialized = true;
            simulationMode = false;
            Log.d(TAG, "BME280 başarıyla başlatıldı");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "BME280 başlatma hatası", e);
            simulationMode = true;
            return false;
        }
    }

    public int readChipId() throws ConnectionLostException, InterruptedException {
        byte[] writeData = new byte[]{0x76};
        byte[] readData = new byte[1];

        boolean success = twi.writeRead(0x76, false, writeData, 1, readData, 1);

        if (success) {
            int chipId = readData[0] & 0xFF;
            Log.d(TAG, String.format("BME280 Chip ID: 0x%02X", chipId));
            return chipId;
        } else {
            Log.e(TAG, "BME280 chip ID okunamadı!");
            return -1;
        }
    }

    private void readCalibrationData() throws ConnectionLostException, InterruptedException {
        // Kalibrasyon verilerini oku (0x88-0xA1 ve 0xE1-0xE7)
        byte[] cal1 = new byte[26];
        byte[] cal2 = new byte[7];
        byte[] writeData1 = new byte[]{(byte)0x88};
        byte[] writeData2 = new byte[]{(byte)0xE1};

        twi.writeRead(BME280_ADDR, false, writeData1, 1, cal1, 26);
        twi.writeRead(BME280_ADDR, false, writeData2, 1, cal2, 7);

        // Sıcaklık kalibrasyonu
        dig_T1 = ((cal1[1] & 0xFF) << 8) | (cal1[0] & 0xFF);
        dig_T2 = ((cal1[3] & 0xFF) << 8) | (cal1[2] & 0xFF);
        dig_T3 = ((cal1[5] & 0xFF) << 8) | (cal1[4] & 0xFF);

        // Basınç kalibrasyonu
        dig_P1 = ((cal1[7] & 0xFF) << 8) | (cal1[6] & 0xFF);
        dig_P2 = ((cal1[9] & 0xFF) << 8) | (cal1[8] & 0xFF);
        dig_P3 = ((cal1[11] & 0xFF) << 8) | (cal1[10] & 0xFF);
        dig_P4 = ((cal1[13] & 0xFF) << 8) | (cal1[12] & 0xFF);
        dig_P5 = ((cal1[15] & 0xFF) << 8) | (cal1[14] & 0xFF);
        dig_P6 = ((cal1[17] & 0xFF) << 8) | (cal1[16] & 0xFF);
        dig_P7 = ((cal1[19] & 0xFF) << 8) | (cal1[18] & 0xFF);
        dig_P8 = ((cal1[21] & 0xFF) << 8) | (cal1[20] & 0xFF);
        dig_P9 = ((cal1[23] & 0xFF) << 8) | (cal1[22] & 0xFF);

        // Nem kalibrasyonu
        dig_H1 = cal1[25] & 0xFF;
        dig_H2 = ((cal2[1] & 0xFF) << 8) | (cal2[0] & 0xFF);
        dig_H3 = cal2[2] & 0xFF;
        dig_H4 = ((cal2[3] & 0xFF) << 4) | (cal2[4] & 0x0F);
        dig_H5 = ((cal2[5] & 0xFF) << 4) | ((cal2[4] & 0xF0) >> 4);
        dig_H6 = cal2[6];

        Log.d(TAG, "Kalibrasyon verileri okundu");
    }

    public SensorData readSensorData() throws ConnectionLostException, InterruptedException {
        if (simulationMode) {
            return simulateSensorData();
        }
        
        try {
            // Önce chip ID'yi kontrol et
            byte[] writeData = new byte[]{REG_ID};
            byte[] readData = new byte[1];
            
            boolean success = twi.writeRead(BME280_ADDR, false, writeData, 1, readData, 1);
            if (!success || (readData[0] & 0xFF) != 0x60) {
                Log.e(TAG, "BME280 chip ID hatası veya yanıt yok");
                return simulateSensorData();
            }
            
            // Sıcaklık, basınç ve nem verilerini oku
            writeData = new byte[]{REG_PRESS};
            readData = new byte[8];  // 0xF7 to 0xFE
            
            success = twi.writeRead(BME280_ADDR, false, writeData, 1, readData, 8);
            if (!success) {
                Log.e(TAG, "Sensör verisi okunamadı");
                return simulateSensorData();
            }
            
            // Ham değerleri hesapla
            int adc_P = ((readData[0] & 0xFF) << 12) | ((readData[1] & 0xFF) << 4) | ((readData[2] & 0xF0) >> 4);
            int adc_T = ((readData[3] & 0xFF) << 12) | ((readData[4] & 0xFF) << 4) | ((readData[5] & 0xF0) >> 4);
            int adc_H = ((readData[6] & 0xFF) << 8) | (readData[7] & 0xFF);
            
            // Kompanzasyon uygula
            float temperature = compensateTemperature(adc_T);
            float pressure = compensatePressure(adc_P) / 100.0f; // Pa -> hPa
            float humidity = compensateHumidity(adc_H);
            
            Log.d(TAG, String.format("Okunan değerler - T: %.2f°C, H: %.1f%%, P: %.1fhPa",
                temperature, humidity, pressure));
            
            return new SensorData(temperature, humidity, pressure, false);
            
        } catch (Exception e) {
            Log.e(TAG, "Sensör veri okuma hatası: " + e.getMessage(), e);
            return simulateSensorData();
        }
    }

    private void writeRegister(byte reg, byte value) throws ConnectionLostException, InterruptedException {
        byte[] writeData = new byte[]{reg, value};
        twi.writeRead(BME280_ADDR, false, writeData, 2, null, 0);
    }
    
    private float compensateTemperature(int adc_T) {
        double var1 = (((double)adc_T)/16384.0 - ((double)dig_T1)/1024.0) * ((double)dig_T2);
        double var2 = ((((double)adc_T)/131072.0 - ((double)dig_T1)/8192.0) *
                      (((double)adc_T)/131072.0 - ((double)dig_T1)/8192.0)) * ((double)dig_T3);
        t_fine = (long)(var1 + var2);
        return (float)((var1 + var2) / 5120.0);
    }
    
    private float compensatePressure(int adc_P) {
        double var1 = ((double)t_fine/2.0) - 64000.0;
        double var2 = var1 * var1 * ((double)dig_P6) / 32768.0;
        var2 = var2 + var1 * ((double)dig_P5) * 2.0;
        var2 = (var2/4.0)+(((double)dig_P4) * 65536.0);
        var1 = (((double)dig_P3) * var1 * var1 / 524288.0 + ((double)dig_P2) * var1) / 524288.0;
        var1 = (1.0 + var1 / 32768.0)*((double)dig_P1);
        
        if (var1 == 0.0) return 0;
        
        double p = 1048576.0 - (double)adc_P;
        p = (p - (var2 / 4096.0)) * 6250.0 / var1;
        var1 = ((double)dig_P9) * p * p / 2147483648.0;
        var2 = p * ((double)dig_P8) / 32768.0;
        p = p + (var1 + var2 + ((double)dig_P7)) / 16.0;
        
        return (float)p;
    }
    
    private float compensateHumidity(int adc_H) {
        double var_H = (((double)t_fine) - 76800.0);
        var_H = (adc_H - (((double)dig_H4) * 64.0 + ((double)dig_H5) / 16384.0 * var_H)) *
                (((double)dig_H2) / 65536.0 * (1.0 + ((double)dig_H6) / 67108864.0 * var_H *
                (1.0 + ((double)dig_H3) / 67108864.0 * var_H)));
        var_H = var_H * (1.0 - ((double)dig_H1) * var_H / 524288.0);
        
        if (var_H > 100.0) var_H = 100.0;
        else if (var_H < 0.0) var_H = 0.0;
        
        return (float)var_H;
    }
    
    private SensorData simulateSensorData() {
        float temp = 25.0f + (float)(Math.random() * 2.0 - 1.0);
        float hum = 50.0f + (float)(Math.random() * 4.0 - 2.0);
        float press = 1013.25f + (float)(Math.random() * 2.0 - 1.0);
        return new SensorData(temp, hum, press, true);
    }
    
    public boolean isConnected() {
        try {
            int chipId = readChipId();
            return chipId == 0x60;
        } catch (Exception e) {
            Log.e(TAG, "BME280 bağlantı hatası", e);
            return false;
        }
    }
    
    public boolean isSimulating() {
        return simulationMode;
    }
} 