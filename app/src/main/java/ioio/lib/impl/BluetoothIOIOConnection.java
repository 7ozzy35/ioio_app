package ioio.lib.impl;

import android.bluetooth.BluetoothSocket;
import android.util.Log;

import ioio.lib.api.exception.ConnectionLostException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Bluetooth üzerinden IOIO bağlantısını yöneten sınıf
 */
public class BluetoothIOIOConnection implements IOIOConnection {
    private static final String TAG = "BluetoothIOIOConnection";
    
    private final BluetoothSocket socket_;
    private InputStream inputStream_;
    private OutputStream outputStream_;
    private boolean connected_ = false;

    public BluetoothIOIOConnection(BluetoothSocket socket) {
        socket_ = socket;
    }

    @Override
    public void waitForConnect() throws ConnectionLostException {
        Log.i(TAG, "Bluetooth bağlantısı kuruluyor...");
        
        try {
            socket_.connect();
            inputStream_ = socket_.getInputStream();
            outputStream_ = socket_.getOutputStream();
            connected_ = true;
            Log.i(TAG, "Bluetooth bağlantısı başarılı!");
        } catch (IOException e) {
            Log.e(TAG, "Bluetooth bağlantı hatası", e);
            throw new ConnectionLostException(e);
        }
    }

    @Override
    public void disconnect() {
        Log.i(TAG, "Bluetooth bağlantısı kesiliyor...");
        connected_ = false;
        
        try {
            if (inputStream_ != null) {
                inputStream_.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "InputStream kapatma hatası", e);
        }
        
        try {
            if (outputStream_ != null) {
                outputStream_.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "OutputStream kapatma hatası", e);
        }
        
        try {
            if (socket_ != null) {
                socket_.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Socket kapatma hatası", e);
        }
        
        Log.i(TAG, "Bluetooth bağlantısı kesildi");
    }

    @Override
    public InputStream getInputStream() throws ConnectionLostException {
        if (!connected_) {
            throw new ConnectionLostException();
        }
        return inputStream_;
    }

    @Override
    public OutputStream getOutputStream() throws ConnectionLostException {
        if (!connected_) {
            throw new ConnectionLostException();
        }
        return outputStream_;
    }

   // @Override
    public boolean canClose() {
        return connected_;
    }
} 