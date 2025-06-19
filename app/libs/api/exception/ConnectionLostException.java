package com.edupass.hugin.infrastructure.ioio.api.exception;

public class ConnectionLostException extends Exception {
    private static final long serialVersionUID = 7422862446246046772L;

    public ConnectionLostException(Exception e) {
        super(e);
    }

    public ConnectionLostException() {
        super("Connection lost");
    }
}