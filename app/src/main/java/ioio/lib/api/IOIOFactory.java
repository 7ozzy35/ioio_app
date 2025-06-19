/*
 * Copyright 2011 Ytai Ben-Tsvi. All rights reserved.
 *  
 * 
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 * 
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 * 
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL ARSHAN POURSOHI OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied.
 */
package ioio.lib.api;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import ioio.lib.impl.BluetoothIOIOConnection;
import ioio.lib.impl.IOIOImpl;
import ioio.lib.impl.SocketIOIOConnection;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Factory class for creating instances of the IOIO interface.
 * <p>
 * This class acts as the single entry-point to the IOIO API. It creates the
 * bootstrapping between a specific implementation of the IOIO interface and any
 * dependencies it might have, such as the underlying connection logic.
 * <p>
 * Typical usage:
 * 
 * <pre>
 * IOIO ioio = IOIOFactory.create();
 * try {
 *   ioio.waitForConnect();
 *   ...
 *   ioio.disconnect();
 * } catch (ConnectionLostException e) {
 * } finally {
 *   ioio.waitForDisconnect();
 * }
 * </pre>
 */
public class IOIOFactory {
	/** The TCP port used for communicating with the IOIO board. */
	private static final int IOIO_PORT = 4545;
	
	/** IOIO Bluetooth UUID for SPP connection */
	private static final UUID IOIO_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	
	private static final String TAG = "IOIOFactory";

	/**
	 * Create a IOIO instance. This tries Bluetooth first, then falls back to TCP.
	 * 
	 * @return The IOIO instance.
	 */
	public static IOIO create() {
		// İlk olarak Bluetooth bağlantısını dene
		IOIO bluetoothIOIO = createBluetoothIOIO();
		if (bluetoothIOIO != null) {
			Log.i(TAG, "Bluetooth IOIO bağlantısı oluşturuldu");
			return bluetoothIOIO;
		}
		
		// Bluetooth yoksa TCP'ye geri dön
		Log.i(TAG, "Bluetooth bulunamadı, TCP bağlantısı deneniyor");
		return new IOIOImpl(new SocketIOIOConnection(IOIO_PORT));
	}
	
	/**
	 * Bluetooth üzerinden IOIO bağlantısı oluştur
	 */
	private static IOIO createBluetoothIOIO() {
		BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		
		if (bluetoothAdapter == null) {
			Log.w(TAG, "Bluetooth adapter bulunamadı");
			return null;
		}
		
		if (!bluetoothAdapter.isEnabled()) {
			Log.w(TAG, "Bluetooth kapalı");
			return null;
		}
		
		// Eşlenmiş cihazları kontrol et
		Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
		BluetoothDevice ioioDevice = null;
		
		for (BluetoothDevice device : pairedDevices) {
			String deviceName = device.getName();
			if (deviceName != null && (deviceName.contains("IOIO") || deviceName.contains("ioio"))) {
				ioioDevice = device;
				Log.i(TAG, "IOIO cihazı bulundu: " + deviceName + " (" + device.getAddress() + ")");
				break;
			}
		}
		
		if (ioioDevice == null) {
			Log.w(TAG, "Eşlenmiş IOIO cihazı bulunamadı");
			return null;
		}
		
		try {
			BluetoothSocket socket = ioioDevice.createRfcommSocketToServiceRecord(IOIO_UUID);
			return new IOIOImpl(new BluetoothIOIOConnection(socket));
		} catch (IOException e) {
			Log.e(TAG, "Bluetooth socket oluşturulamadı", e);
			return null;
		}
	}
}


