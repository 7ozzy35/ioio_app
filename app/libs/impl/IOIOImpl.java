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
package com.edupass.hugin.infrastructure.ioio.impl;

import android.util.Log;

import java.io.IOException;

import com.edupass.hugin.infrastructure.ioio.api.AnalogInput;
import com.edupass.hugin.infrastructure.ioio.api.DigitalInput;
import com.edupass.hugin.infrastructure.ioio.api.DigitalInput.Spec.Mode;
import com.edupass.hugin.infrastructure.ioio.api.DigitalOutput;
import com.edupass.hugin.infrastructure.ioio.api.IOIO;
import com.edupass.hugin.infrastructure.ioio.api.IcspMaster;
import com.edupass.hugin.infrastructure.ioio.api.PwmOutput;
import com.edupass.hugin.infrastructure.ioio.api.SpiMaster;
import com.edupass.hugin.infrastructure.ioio.api.TwiMaster;
import com.edupass.hugin.infrastructure.ioio.api.TwiMaster.Rate;
import com.edupass.hugin.infrastructure.ioio.api.Uart;
import com.edupass.hugin.infrastructure.ioio.api.exception.ConnectionLostException;
import com.edupass.hugin.infrastructure.ioio.api.exception.IncompatibilityException;
import com.edupass.hugin.infrastructure.ioio.impl.IncomingState.DisconnectListener;

public class IOIOImpl implements IOIO, DisconnectListener {
	enum State {
		INIT, CONNECTED, INCOMPATIBLE, DEAD
	}

	private static final byte[] REQUIRED_INTERFACE_ID = new byte[] { 'I', 'O',
			'I', 'O', '0', '0', '0', '2' };

	private final IOIOConnection connection_;
	private final IncomingState incomingState_ = new IncomingState();
	private final boolean openPins_[] = new boolean[Constants.NUM_PINS];
	private final boolean openTwi_[] = new boolean[Constants.NUM_TWI_MODULES];
	private boolean openIcsp_ = false;
	private final ModuleAllocator pwmAllocator_ = new ModuleAllocator(
			Constants.NUM_PWM_MODULES, "PWM");
	private final ModuleAllocator uartAllocator_ = new ModuleAllocator(
			Constants.NUM_UART_MODULES, "UART");
	private final ModuleAllocator spiAllocator_ = new ModuleAllocator(
			Constants.NUM_SPI_MODULES, "SPI");
	IOIOProtocol protocol_;
	private State state_ = State.INIT;

	public IOIOImpl(IOIOConnection con) {
		connection_ = con;
	}

	@Override
	synchronized public void waitForConnect() throws ConnectionLostException,
			IncompatibilityException {
		if (state_ == State.CONNECTED) {
			return;
		}
		if (state_ == State.DEAD) {
			throw new ConnectionLostException();
		}
		addDisconnectListener(this);
		Log.d("IOIOImpl", "Waiting for IOIO connection");
		try {
			try {
				Log.d("IOIOImpl", "Waiting for underlying connection");
				connection_.waitForConnect();
				protocol_ = new IOIOProtocol(connection_.getInputStream(),
						connection_.getOutputStream(), incomingState_);
			} catch (ConnectionLostException e) {
				incomingState_.handleConnectionLost();
				throw e;
			}
			Log.d("IOIOImpl", "Waiting for handshake");
			incomingState_.waitConnectionEstablished();
			Log.d("IOIOImpl", "Querying for required interface ID");
			checkInterfaceVersion();
			Log.d("IOIOImpl", "Required interface ID is supported");
			state_ = State.CONNECTED;
			Log.i("IOIOImpl", "IOIO connection established");
		} catch (ConnectionLostException e) {
			state_ = State.DEAD;
			throw e;
		} catch (InterruptedException e) {
			Log.e("IOIOImpl", "Unexpected exception", e);
		}
	}

	@Override
	public void disconnect() {
		connection_.disconnect();
	}

	@Override
	public void disconnected() {
		state_ = State.DEAD;
		disconnect();
	}

	public void waitForDisconnect() throws InterruptedException {
		incomingState_.waitDisconnect();
	}

	private void checkInterfaceVersion() throws IncompatibilityException,
			ConnectionLostException, InterruptedException {
		try {
			protocol_.checkInterface(REQUIRED_INTERFACE_ID);
		} catch (IOException e) {
			throw new ConnectionLostException(e);
		}
		if (!incomingState_.waitForInterfaceSupport()) {
			state_ = State.INCOMPATIBLE;
			Log.e("IOIOImpl", "Required interface ID is not supported");
			throw new IncompatibilityException(
					"IOIO firmware does not support required firmware: "
							+ new String(REQUIRED_INTERFACE_ID));
		}
	}

	synchronized void removeDisconnectListener(DisconnectListener listener) {
		incomingState_.removeDisconnectListener(listener);
	}

	synchronized void addDisconnectListener(DisconnectListener listener)
			throws ConnectionLostException {
		incomingState_.addDisconnectListener(listener);
	}

	synchronized void closePin(int pin) {
		if (openPins_[pin]) {
			try {
				protocol_.setPinDigitalIn(pin, DigitalInput.Spec.Mode.FLOATING);
			} catch (IOException e) {
			}
			openPins_[pin] = false;
		}
	}

	synchronized void closePwm(int pwmNum) {
		pwmAllocator_.releaseModule(pwmNum);
		try {
			protocol_.setPwmPeriod(pwmNum, 0, false);
		} catch (IOException e) {
		}
	}

	synchronized void closeUart(int uartNum) {
		uartAllocator_.releaseModule(uartNum);
		try {
			protocol_.uartClose(uartNum);
		} catch (IOException e) {
		}
	}

	synchronized void closeTwi(int twiNum) {
		if (!openTwi_[twiNum]) {
			throw new IllegalStateException("TWI not open: " + twiNum);
		}
		openTwi_[twiNum] = false;
		openPins_[Constants.TWI_PINS[twiNum][0]] = false;
		openPins_[Constants.TWI_PINS[twiNum][1]] = false;
		try {
			protocol_.i2cClose(twiNum);
		} catch (IOException e) {
		}
	}

	synchronized void closeIcsp() {
		if (!openIcsp_) {
			throw new IllegalStateException("ICSP not open");
		}
		openIcsp_ = false;
		openPins_[Constants.ICSP_PINS[0]] = false;
		openPins_[Constants.ICSP_PINS[1]] = false;
		try {
			protocol_.icspClose();
		} catch (IOException e) {
		}
	}

	synchronized void closeSpi(int spiNum) {
		spiAllocator_.releaseModule(spiNum);
		try {
			protocol_.spiClose(spiNum);
		} catch (IOException e) {
		}
	}

	@Override
	synchronized public void softReset() throws ConnectionLostException {
		checkState();
		try {
			protocol_.softReset();
		} catch (IOException e) {
			throw new ConnectionLostException(e);
		}
	}

	@Override
	synchronized public void hardReset() throws ConnectionLostException {
		checkState();
		try {
			protocol_.hardReset();
		} catch (IOException e) {
			throw new ConnectionLostException(e);
		}
	}

	@Override
	public String getImplVersion(VersionType v) throws ConnectionLostException {
		checkState();
		switch (v) {
		case HARDWARE_VER:
			return incomingState_.hardwareId_;
		case BOOTLOADER_VER:
			return incomingState_.bootloaderId_;
		case APP_FIRMWARE_VER:
			return incomingState_.firmwareId_;
		case IOIOLIB_VER:
			return "IOIO0100";
		}
		return null;
	}

	@Override
	public DigitalInput openDigitalInput(int pin)
			throws ConnectionLostException {
		return openDigitalInput(new DigitalInput.Spec(pin));
	}

	@Override
	public DigitalInput openDigitalInput(int pin, Mode mode)
			throws ConnectionLostException {
		return openDigitalInput(new DigitalInput.Spec(pin, mode));
	}

	@Override
	synchronized public DigitalInput openDigitalInput(DigitalInput.Spec spec)
			throws ConnectionLostException {
		checkState();
		PinFunctionMap.checkValidPin(spec.pin);
		checkPinFree(spec.pin);
		DigitalInputImpl result = new DigitalInputImpl(this, spec.pin);
		addDisconnectListener(result);
		openPins_[spec.pin] = true;
		incomingState_.addInputPinListener(spec.pin, result);
		try {
			protocol_.setPinDigitalIn(spec.pin, spec.mode);
			protocol_.setChangeNotify(spec.pin, true);
		} catch (IOException e) {
			result.close();
			throw new ConnectionLostException(e);
		}
		return result;
	}

	@Override
	public DigitalOutput openDigitalOutput(int pin,
			com.edupass.hugin.infrastructure.ioio.api.DigitalOutput.Spec.Mode mode, boolean startValue)
			throws ConnectionLostException {
		return openDigitalOutput(new DigitalOutput.Spec(pin, mode), startValue);
	}

	@Override
	synchronized public DigitalOutput openDigitalOutput(
			DigitalOutput.Spec spec, boolean startValue)
			throws ConnectionLostException {
		checkState();
		PinFunctionMap.checkValidPin(spec.pin);
		checkPinFree(spec.pin);
		DigitalOutputImpl result = new DigitalOutputImpl(this, spec.pin);
		addDisconnectListener(result);
		openPins_[spec.pin] = true;
		try {
			protocol_.setPinDigitalOut(spec.pin, startValue, spec.mode);
		} catch (IOException e) {
			result.close();
			throw new ConnectionLostException(e);
		}
		return result;
	}

	@Override
	public DigitalOutput openDigitalOutput(int pin, boolean startValue)
			throws ConnectionLostException {
		return openDigitalOutput(new DigitalOutput.Spec(pin), startValue);
	}

	@Override
	public DigitalOutput openDigitalOutput(int pin)
			throws ConnectionLostException {
		return openDigitalOutput(new DigitalOutput.Spec(pin), false);
	}

	@Override
	synchronized public AnalogInput openAnalogInput(int pin)
			throws ConnectionLostException {
		checkState();
		PinFunctionMap.checkSupportsAnalogInput(pin);
		checkPinFree(pin);
		AnalogInputImpl result = new AnalogInputImpl(this, pin);
		addDisconnectListener(result);
		openPins_[pin] = true;
		incomingState_.addInputPinListener(pin, result);
		try {
			protocol_.setPinAnalogIn(pin);
			protocol_.setAnalogInSampling(pin, true);
		} catch (IOException e) {
			result.close();
			throw new ConnectionLostException(e);
		}
		return result;
	}

	@Override
	public PwmOutput openPwmOutput(int pin, int freqHz)
			throws ConnectionLostException {
		return openPwmOutput(new DigitalOutput.Spec(pin), freqHz);
	}

	@Override
	synchronized public PwmOutput openPwmOutput(DigitalOutput.Spec spec,
			int freqHz) throws ConnectionLostException {
		checkState();
		PinFunctionMap.checkSupportsPeripheralOutput(spec.pin);
		checkPinFree(spec.pin);
		int pwmNum = pwmAllocator_.allocateModule();
		int period = 16000000 / freqHz - 1;
		boolean scale256 = false;
		int effectivePeriodMicroSec;
		if (period > 65535) {
			period = 16000000 / freqHz / 256 - 1;
			scale256 = true;
			effectivePeriodMicroSec = (period + 1) * 16;
		} else {
			effectivePeriodMicroSec = (period + 1) / 16;
		}
		PwmImpl pwm = new PwmImpl(this, spec.pin, pwmNum, period,
				effectivePeriodMicroSec);
		addDisconnectListener(pwm);
		openPins_[spec.pin] = true;
		try {
			protocol_.setPinDigitalOut(spec.pin, false, spec.mode);
			protocol_.setPinPwm(spec.pin, pwmNum, true);
			protocol_.setPwmPeriod(pwmNum, period, scale256);
		} catch (IOException e) {
			pwm.close();
			throw new ConnectionLostException(e);
		}
		return pwm;
	}

	@Override
	public Uart openUart(int rx, int tx, int baud, Uart.Parity parity,
			Uart.StopBits stopbits) throws ConnectionLostException {
		return openUart(rx == INVALID_PIN ? null : new DigitalInput.Spec(rx),
				tx == INVALID_PIN ? null : new DigitalOutput.Spec(tx), baud,
				parity, stopbits);
	}

	@Override
	synchronized public Uart openUart(DigitalInput.Spec rx,
			DigitalOutput.Spec tx, int baud, Uart.Parity parity,
			Uart.StopBits stopbits) throws ConnectionLostException {
		checkState();
		if (rx != null) {
			PinFunctionMap.checkSupportsPeripheralInput(rx.pin);
			checkPinFree(rx.pin);
		}
		if (tx != null) {
			PinFunctionMap.checkSupportsPeripheralOutput(tx.pin);
			checkPinFree(tx.pin);
		}
		int rxPin = rx != null ? rx.pin : INVALID_PIN;
		int txPin = tx != null ? tx.pin : INVALID_PIN;
		int uartNum = uartAllocator_.allocateModule();
		UartImpl uart = new UartImpl(this, txPin, rxPin, uartNum);
		addDisconnectListener(uart);
		incomingState_.addUartListener(uartNum, uart);
		try {
			if (rx != null) {
				openPins_[rx.pin] = true;
				protocol_.setPinDigitalIn(rx.pin, rx.mode);
				protocol_.setPinUart(rx.pin, uartNum, false, true);
			}
			if (tx != null) {
				openPins_[tx.pin] = true;
				protocol_.setPinDigitalOut(tx.pin, true, tx.mode);
				protocol_.setPinUart(tx.pin, uartNum, true, true);
			}
			boolean speed4x = true;
			int rate = Math.round(4000000.0f / baud) - 1;
			if (rate > 65535) {
				speed4x = false;
				rate = Math.round(1000000.0f / baud) - 1;
			}
			protocol_.uartConfigure(uartNum, rate, speed4x, stopbits, parity);
		} catch (IOException e) {
			uart.close();
			throw new ConnectionLostException(e);
		}
		return uart;
	}

	@Override
	synchronized public TwiMaster openTwiMaster(int twiNum, Rate rate,
			boolean smbus) throws ConnectionLostException {
		checkState();
		checkTwiFree(twiNum);
		checkPinFree(Constants.TWI_PINS[twiNum][0]);
		checkPinFree(Constants.TWI_PINS[twiNum][1]);
		openPins_[Constants.TWI_PINS[twiNum][0]] = true;
		openPins_[Constants.TWI_PINS[twiNum][1]] = true;
		openTwi_[twiNum] = true;
		TwiMasterImpl twi = new TwiMasterImpl(this, twiNum);
		addDisconnectListener(twi);
		incomingState_.addTwiListener(twiNum, twi);
		try {
			protocol_.i2cConfigureMaster(twiNum, rate, smbus);
		} catch (IOException e) {
			twi.close();
			throw new ConnectionLostException(e);
		}
		return twi;
	}

	@Override
	synchronized public IcspMaster openIcspMaster()
			throws ConnectionLostException {
		checkState();
		checkIcspFree();
		checkPinFree(Constants.ICSP_PINS[0]);
		checkPinFree(Constants.ICSP_PINS[1]);
		checkPinFree(Constants.ICSP_PINS[2]);
		openPins_[Constants.ICSP_PINS[0]] = true;
		openPins_[Constants.ICSP_PINS[1]] = true;
		openPins_[Constants.ICSP_PINS[2]] = true;
		openIcsp_ = true;
		IcspMasterImpl icsp = new IcspMasterImpl(this);
		addDisconnectListener(icsp);
		incomingState_.addIcspListener(icsp);
		try {
			protocol_.icspOpen();
		} catch (IOException e) {
			icsp.close();
			throw new ConnectionLostException(e);
		}
		return icsp;
	}

	@Override
	public SpiMaster openSpiMaster(int miso, int mosi, int clk,
			int slaveSelect, SpiMaster.Rate rate)
			throws ConnectionLostException {
		return openSpiMaster(miso, mosi, clk, new int[] { slaveSelect }, rate);
	}

	@Override
	public SpiMaster openSpiMaster(int miso, int mosi, int clk,
			int[] slaveSelect, SpiMaster.Rate rate)
			throws ConnectionLostException {
		DigitalOutput.Spec[] slaveSpecs = new DigitalOutput.Spec[slaveSelect.length];
		for (int i = 0; i < slaveSelect.length; ++i) {
			slaveSpecs[i] = new DigitalOutput.Spec(slaveSelect[i]);
		}
		return openSpiMaster(new DigitalInput.Spec(miso, Mode.PULL_UP),
				new DigitalOutput.Spec(mosi), new DigitalOutput.Spec(clk),
				slaveSpecs, new SpiMaster.Config(rate));
	}

	@Override
	synchronized public SpiMaster openSpiMaster(DigitalInput.Spec miso,
			DigitalOutput.Spec mosi, DigitalOutput.Spec clk,
			DigitalOutput.Spec[] slaveSelect, SpiMaster.Config config)
			throws ConnectionLostException {
		checkState();
		int ssPins[] = new int[slaveSelect.length];
		checkPinFree(miso.pin);
		PinFunctionMap.checkSupportsPeripheralInput(miso.pin);
		checkPinFree(mosi.pin);
		PinFunctionMap.checkSupportsPeripheralOutput(mosi.pin);
		checkPinFree(clk.pin);
		PinFunctionMap.checkSupportsPeripheralOutput(clk.pin);
		for (int i = 0; i < slaveSelect.length; ++i) {
			checkPinFree(slaveSelect[i].pin);
			ssPins[i] = slaveSelect[i].pin;
		}
		int spiNum = spiAllocator_.allocateModule();
		SpiMasterImpl spi = new SpiMasterImpl(this, spiNum, mosi.pin, miso.pin,
				clk.pin, ssPins);
		addDisconnectListener(spi);
		incomingState_.addSpiListener(spiNum, spi);
		try {
			protocol_.setPinDigitalIn(miso.pin, miso.mode);
			protocol_.setPinSpi(miso.pin, 1, true, spiNum);
			protocol_.setPinDigitalOut(mosi.pin, true, mosi.mode);
			protocol_.setPinSpi(mosi.pin, 0, true, spiNum);
			protocol_.setPinDigitalOut(clk.pin, config.invertClk, clk.mode);
			protocol_.setPinSpi(clk.pin, 2, true, spiNum);
			for (DigitalOutput.Spec spec : slaveSelect) {
				protocol_.setPinDigitalOut(spec.pin, true, spec.mode);
			}
			protocol_.spiConfigureMaster(spiNum, config);
		} catch (IOException e) {
			spi.close();
			throw new ConnectionLostException(e);
		}
		return spi;
	}

	private void checkPinFree(int pin) {
		if (openPins_[pin]) {
			throw new IllegalArgumentException("Pin already open: " + pin);
		}
	}

	private void checkTwiFree(int twi) {
		if (openTwi_[twi]) {
			throw new IllegalArgumentException("TWI already open: " + twi);
		}
	}

	private void checkIcspFree() {
		if (openIcsp_) {
			throw new IllegalArgumentException("ICSP already open");
		}
	}

	private void checkState() throws ConnectionLostException {
		if (state_ == State.DEAD) {
			throw new ConnectionLostException();
		}
		if (state_ == State.INCOMPATIBLE) {
			throw new IllegalStateException(
					"Incompatibility has been reported - IOIO cannot be used");
		}
		if (state_ != State.CONNECTED) {
			throw new IllegalStateException(
					"Connection has not yet been established");
		}
	}
}
