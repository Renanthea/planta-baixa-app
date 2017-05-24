package com.plantabaixa.android.rawdata;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.motorola.mod.ModDevice;
import com.motorola.mod.ModManager;
import com.plantabaixa.android.sensor.SensorEvent;
import com.plantabaixa.android.sensor.SensorEventListener;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Created by gventura on 20/05/2017.
 */

public class UltrasonicSensor {
    private static final byte[] MODIST_RAW_COMMAND_SONAR = {0x02, 0x00};
    private static final long INTERVAL = 1000;
    RawPersonality personality;
    UltrasonicSensorListener listener;
    SensorEventListener sensorEvent;

    public UltrasonicSensor(Context context, UltrasonicSensorListener listener) {
        initPersonality(context);
        this.listener = listener;
    }

    public void setSensorEvent(SensorEventListener sensorEvent) {
        this.sensorEvent = sensorEvent;
    }

    public void start(long milliseconds) {
        long interval = milliseconds;
        byte intervalLow = (byte) (interval & 0x00FF);
        byte intervalHigh = (byte) (interval >> 8);
        byte[] cmd = {Constants.TEMP_RAW_COMMAND_ON, Constants.SENSOR_COMMAND_SIZE,
                intervalLow, intervalHigh};
        personality.getRaw().executeRaw(cmd);
    }

    private void schedule(long interval) {
        /**
         *  Personality has the RAW interface, query the information data via RAW command, the data
         *  will send back from MDK with flag TEMP_RAW_COMMAND_INFO and TEMP_RAW_COMMAND_CHALLENGE.
         */
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                personality.getRaw().executeRaw(MODIST_RAW_COMMAND_SONAR);
            }
        }, interval);
    }

    public void resume() {
        personality.getRaw().checkRawInterface();
    }

    public void stop() {
        personality.getRaw().executeRaw(Constants.RAW_CMD_STOP);
    }

    public void release() {
        /** Clean up MDK Personality interface */
        if (null != personality) {
            personality.getRaw().executeRaw(Constants.RAW_CMD_STOP);
            personality.onDestroy();
            personality = null;
        }
    }

    /**
     * Initial MDK Personality interface
     */
    private void initPersonality(Context context) {
        if (null == personality) {
            personality = new RawPersonality(context, Constants.VID_MDK, Constants.PID_TEMPERATURE);
            personality.registerListener(handler);
        }
    }

    /**
     * Handler for events from mod device
     */
    private Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case Personality.MSG_MOD_DEVICE:
                    /** Mod attach/detach */
                    ModDevice device = personality.getModDevice();
                    onModDevice(device);
                    break;
                case Personality.MSG_RAW_DATA:
                    /** Mod raw data */
                    byte[] buff = (byte[]) msg.obj;
                    int length = msg.arg1;
                    onRawData(buff, length);
                    break;
                case Personality.MSG_RAW_IO_READY:
                    /** Mod RAW I/O ready to use */
                    onRawInterfaceReady();
                    break;
                case Personality.MSG_RAW_IO_EXCEPTION:
                    /** Mod RAW I/O exception */
                    onIOException();
                    break;
                case Personality.MSG_RAW_REQUEST_PERMISSION:
                    /** Request grant RAW_PROTOCOL permission */
                    onRequestRawPermission();
                default:
                    Log.i(Constants.TAG, "MainActivity - Un-handle events: " + msg.what);
                    break;
            }
        }
    };

    private void onModDevice(ModDevice device) {
        Log.i(Constants.TAG, "onModDevice");
        if (listener != null)
            listener.onModDeviceAttachmentChanged(device);
    }

    /**
     * Got data from mod device RAW I/O
     */
    private void onRawData(byte[] buffer, int length) {
        /** Parse raw data to header and payload */
        int cmd = buffer[Constants.CMD_OFFSET] & ~Constants.TEMP_RAW_COMMAND_RESP_MASK & 0xFF;
        if (cmd == 0) {
            cmd = Constants.TEMP_RAW_COMMAND_DATA;
        }
        int payloadLength = buffer[Constants.SIZE_OFFSET];

        /** Checking the size of buffer we got to ensure sufficient bytes */
        if (cmd != Constants.TEMP_RAW_COMMAND_DATA && payloadLength + Constants.CMD_LENGTH + Constants.SIZE_LENGTH != length) {
            return;
        }

        payloadLength = 4;

        /** Parser payload data */
        byte[] payload = new byte[payloadLength];
        System.arraycopy(buffer, 0, payload, 0, payloadLength);
        parseResponse(cmd, payloadLength, payload);
    }

    /**
     * RAW I/O of attached mod device is ready to use
     */
    private void onRawInterfaceReady() {
        schedule(INTERVAL);
    }

    /**
     * Handle the IO issue when write / read
     */
    private void onIOException() {
    }

    /**
     * Parse the data from mod device
     */
    private void parseResponse(int cmd, int size, byte[] payload) {
        if (cmd == Constants.TEMP_RAW_COMMAND_INFO) {
            /** Got information data from personality board */

            /**
             * Checking the size of payload before parse it to ensure sufficient bytes.
             * Payload array shall at least include the command head data, and exactly
             * same as expected size.
             */
            if (payload == null
                    || payload.length != size
                    || payload.length < Constants.CMD_INFO_HEAD_SIZE) {
                return;
            }

            int version = payload[Constants.CMD_INFO_VERSION_OFFSET];
            int reserved = payload[Constants.CMD_INFO_RESERVED_OFFSET];
            int latencyLow = payload[Constants.CMD_INFO_LATENCYLOW_OFFSET] & 0xFF;
            int latencyHigh = payload[Constants.CMD_INFO_LATENCYHIGH_OFFSET] & 0xFF;
            int max_latency = latencyHigh << 8 | latencyLow;

            StringBuilder name = new StringBuilder();
            for (int i = Constants.CMD_INFO_NAME_OFFSET; i < size - Constants.CMD_INFO_HEAD_SIZE; i++) {
                if (payload[i] != 0) {
                    name.append((char) payload[i]);
                } else {
                    break;
                }
            }
            Log.i(Constants.TAG, "command: " + cmd
                    + " size: " + size
                    + " version: " + version
                    + " reserved: " + reserved
                    + " name: " + name.toString()
                    + " latency: " + max_latency);
        } else if (cmd == Constants.TEMP_RAW_COMMAND_DATA) {
            /** Got sensor data from personality board */

            /** Checking the size of payload before parse it to ensure sufficient bytes. */
            if (payload == null
                    || payload.length != size
                    || payload.length != Constants.CMD_DATA_SIZE) {
                return;
            }

            /** The raw temperature sensor data */
            int microseconds = ByteBuffer.wrap(payload).getInt();
            double cm = (microseconds / 2) / 29.1;
            double inches = (microseconds / 2) / 74;

            Log.i(Constants.TAG, "onDistanceUpdate. Data.: " + microseconds);
            if (listener != null) {
                listener.onDistanceUpdate(microseconds, inches, cm);
            }
            if (sensorEvent != null) {
                sensorEvent.onSensorChanged(new SensorEvent(new float[] { microseconds, (float) inches, (float) cm}));
            }
            schedule(INTERVAL);
        } else if (cmd == Constants.TEMP_RAW_COMMAND_CHALLENGE) {
            /** Got CHALLENGE command from personality board */

            /** Checking the size of payload before parse it to ensure sufficient bytes. */
            if (payload == null
                    || payload.length != size
                    || payload.length != Constants.CMD_CHALLENGE_SIZE) {
                return;
            }

            /** Got decoded CHALLENGE payload */
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            buffer.order(ByteOrder.LITTLE_ENDIAN); // lsb -> msb
            long littleLong = buffer.getLong();
            littleLong += Constants.CHALLENGE_ADDATION;

            ByteBuffer buf = ByteBuffer.allocate(Long.SIZE / Byte.SIZE).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(littleLong);
            byte[] respData = buf.array();

            /** Send challenge response back to mod device */
            byte[] challenge = new byte[respData.length + 2];
            challenge[0] = Constants.TEMP_RAW_COMMAND_CHLGE_RESP;
            challenge[1] = (byte) respData.length;
            System.arraycopy(respData, 0, challenge, 2, respData.length);
            personality.getRaw().executeRaw(challenge);
        } else if (cmd == Constants.TEMP_RAW_COMMAND_CHLGE_RESP) {
            /** Get challenge command response */

            /** Checking the size of payload before parse it to ensure sufficient bytes. */
            if (payload == null
                    || payload.length != size
                    || payload.length != Constants.CMD_CHLGE_RESP_SIZE) {
                return;
            }

            /**
             * Check first byte, response from MDK Sensor Card shall be 0
             * if challenge passed
             */
            boolean challengePassed = payload[Constants.CMD_CHLGE_RESP_OFFSET] == 0;
            onFirstResponse(challengePassed);
        }
    }

    private void onFirstResponse(boolean challengePassed) {
        Log.i(Constants.TAG, "onFirstResponse. challengePassed: " + challengePassed);
        if (listener != null)
            listener.onFirstResponse(challengePassed);
    }

    /*
     * Beginning in Android 6.0 (API level 23), users grant permissions to apps while
     * the app is running, not when they install the app. App need check on and request
     * permission every time perform an operation.
    */
    private void onRequestRawPermission() {
        Log.i(Constants.TAG, "onRequestRawPermission");
        if (listener != null)
            listener.onRequestRawPermission();

    }

    public ModManager getModManager() {
        return personality != null ? personality.getModManager() : null;
    }

    public ModDevice getModDevice() {
        return personality != null ? personality.getModDevice() : null;
    }
}
