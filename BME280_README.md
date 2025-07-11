# BME-280 Sensör Entegrasyonu - IOIO Android Uygulaması

## 🌡️ Genel Bakış
Bu uygulama BME-280 sensörü ile IOIO kartı arasında I2C protokolü kullanarak haberleşir ve sıcaklık, nem ve basınç değerlerini gerçek zamanlı olarak okur.

## 🔧 Donanım Bağlantısı

### BME-280 Sensör Pinleri:
- **VCC**: 3.3V (IOIO kartı 3.3V çıkışına)
- **GND**: Ground (IOIO kartı GND'ye)  
- **SDA**: Pin 4 (IOIO kartı)
- **SCL**: Pin 5 (IOIO kartı)

### I2C Adresi:
- **Varsayılan**: 0x77
- **Alternatif**: 0x76 (SDO pin durumuna göre)

## 📱 Uygulama Özellikleri

### ✅ Gerçek Zamanlı Veri Okuma
- Sıcaklık: -40°C ile +85°C arası
- Nem: %0 ile %100 arası  
- Basınç: 300-1100 hPa arası

### 🎨 Modern UI
- Renk kodlu değer gösterimi:
  - 🟢 Normal değerler
  - 🔴 Yüksek değerler
  - 🔵 Düşük değerler
- Anlık min/max değer takibi
- Simülasyon modu göstergesi

### 🔄 Otomatik Bağlantı Yönetimi
- IOIO kartı Bluetooth bağlantısı
- BME-280 sensör durumu takibi
- Bağlantı hatalarında simülasyon modu

## 🚀 Kurulum ve Kullanım

### 1. Donanım Hazırlığı
```
BME-280 → IOIO
VCC     → 3.3V
GND     → GND  
SDA     → Pin 4
SCL     → Pin 5
```

### 2. Uygulama Kurulumu
1. Android Studio'da projeyi açın
2. IOIO kartınızı Bluetooth ile eşleştirin
3. Uygulamayı cihazınıza yükleyin

### 3. Çalıştırma
1. IOIO kartını açın
2. Bluetooth bağlantısını etkinleştirin
3. Uygulamayı başlatın
4. Bağlantı durumunu kontrol edin

## 🔍 Teknik Detaylar

### I2C Konfigürasyonu
- **Hız**: 100 kHz
- **Veri Formatı**: 8-bit
- **Adres Çözünürlüğü**: 7-bit

### BME280 Sensor Ayarları
- **Sıcaklık Oversampling**: 1x
- **Nem Oversampling**: 1x  
- **Basınç Oversampling**: 1x
- **Güç Modu**: Normal
- **Standby Süresi**: 1000ms

### Kalibrasyon
- Sensör fabrika kalibrasyonu kullanılır
- Trim parametreleri otomatik okunur
- Kompanzasyon hesaplamaları yapılır

## 🛠️ Sorun Giderme

### Sensör Bulunamıyor
```java
// Chip ID kontrolü başarısız
// Simülasyon moduna geçer
// Bağlantıları kontrol edin
```

### I2C Haberleşme Hatası
```java
// TwiMaster.writeRead() exception
// Pin bağlantılarını kontrol edin
// Pull-up dirençleri ekleyin (4.7kΩ)
```

### Bluetooth Bağlantı Sorunu
```java
// IOIO kartı eşleştirmesi
// Bluetooth servisini yeniden başlatın
// IOIO sürücülerini güncelleyin
```

## 📊 Veri Formatları

### Sıcaklık
```java
float temperature = sensorData.temperature; // °C
String display = String.format("%.1f°C", temperature);
```

### Nem
```java
float humidity = sensorData.humidity; // %
String display = String.format("%.1f%%", humidity);
```

### Basınç
```java
float pressure = sensorData.pressure; // hPa
String display = String.format("%.1f hPa", pressure);
```

## 🎯 Simülasyon Modu

Sensör bağlı değilken otomatik olarak aktif olur:
- Gerçekçi değer aralıkları
- Rastgele değişimler
- UI'da "(Sim)" etiketi

## 📞 Destek

### Geliştirici Notları
- Android API 21+ destekli
- IOIO kütüphanesi v5.0+
- Modern Material Design

### Test Edilen Donanım
- IOIO-OTG kartı
- BME-280 modülü (GY-BME280)
- Android 5.0+ cihazlar

## 🔄 Güncellemeler

### v1.0 - İlk Sürüm
- ✅ Temel BME-280 entegrasyonu
- ✅ I2C haberleşmesi  
- ✅ Simülasyon modu
- ✅ Modern UI tasarımı
- ✅ Gerçek zamanlı veri gösterimi

---
**Not**: Sensör henüz fiziksel olarak test edilmediği için simülasyon modu varsayılan olarak aktiftir. Gerçek sensör bağlandığında otomatik olarak gerçek veri moduna geçecektir. 