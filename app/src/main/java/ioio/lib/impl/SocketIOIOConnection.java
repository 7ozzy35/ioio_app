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
package ioio.lib.impl;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

import ioio.lib.api.exception.ConnectionLostException;

public class SocketIOIOConnection implements IOIOConnection {
	private final int port_;
	private ServerSocket server_ = null;
	private Socket socket_ = null;
	private boolean disconnect_ = false;
	private boolean server_owned_by_connect_ = true;
	private boolean socket_owned_by_connect_ = true;
	
	public SocketIOIOConnection(int port) {
		port_ = port;
	}

	@Override
	public void waitForConnect() throws ConnectionLostException {
		try {
			synchronized (this) {
				if (disconnect_) {
					throw new ConnectionLostException();
				}
				Log.d("SocketIOIOConnection", "Creating server socket");
				server_ = new ServerSocket(port_);
				server_owned_by_connect_ = false;
			}
			Log.d("SocketIOIOConnection", "Waiting for TCP connection");
			socket_ = server_.accept();
			Log.d("SocketIOIOConnection", "TCP connected");
			synchronized (this) {
				if (disconnect_) {
					socket_.close();
					throw new ConnectionLostException();
				}
				socket_owned_by_connect_ = false;
			}
		} catch (IOException e) {
			synchronized (this) {
				disconnect_ = true;
				if (server_owned_by_connect_ && server_ != null) {
					try {
						server_.close();
					} catch (IOException e1) {
						Log.e("SocketIOIOConnection", "Unexpected exception", e1);
					}
				}
				if (socket_owned_by_connect_ && socket_ != null) {
					try {
						socket_.close();
					} catch (IOException e1) {
						Log.e("SocketIOIOConnection", "Unexpected exception", e1);
					}
				}
				if (e instanceof SocketException && e.getMessage().equals("Permission denied")) {
					Log.e("SocketIOIOConnection", "Did you forget to declare uses-permission of android.permission.INTERNET?");
				}
				throw new ConnectionLostException(e);
			}
		}
	}

	@Override
	synchronized public void disconnect() {
		if (disconnect_) {
			return;
		}
		Log.d("SocketIOIOConnection", "Client initiated disconnect");
		disconnect_ = true;
		if (!server_owned_by_connect_) {
			try {
				server_.close();
			} catch (IOException e1) {
				Log.e("SocketIOIOConnection", "Unexpected exception", e1);
			}
		}
		if (!socket_owned_by_connect_) {
			try {
				socket_.close();
			} catch (IOException e1) {
				Log.e("SocketIOIOConnection", "Unexpected exception", e1);
			}
		}
	}

	@Override
	public InputStream getInputStream() throws ConnectionLostException {
		try {
			return socket_.getInputStream();
		} catch (IOException e) {
			throw new ConnectionLostException(e);
		}
	}

	@Override
	public OutputStream getOutputStream() throws ConnectionLostException {
		try {
			return socket_.getOutputStream();
		} catch (IOException e) {
			throw new ConnectionLostException(e);
		}
	}

}


