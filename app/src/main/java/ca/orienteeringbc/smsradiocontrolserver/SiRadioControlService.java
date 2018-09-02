package ca.orienteeringbc.smsradiocontrolserver;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.telephony.SmsManager;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SiRadioControlService extends Service {
    private final static String TAG = "SiRadioControlService";

    // SportIdent Specific Codes
    public static final char START_TX = 0x2;
    public static final char CONTROL_EXTENDED = 0xd3;
    public static final char CONTROL_NORMAL = 0x53;
    public static final char END_TX = 0x3;

    private static UsbSerialPort sPort = null;

    SharedPreferences sharedPreferences;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private SerialInputOutputManager mSerialIoManager;

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

                @Override
                public void onRunError(Exception e) {
                    Log.d(TAG,  "Runner stopped.");
                    stopSelf();
                }

                @Override
                public void onNewData(final byte[] data) {
                    parseInput(byteArrayToUnsignedIntArray(data), 0);
                }
            };

    public SiRadioControlService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Find all available drivers from attached devices.
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (availableDrivers.isEmpty()) {
            Log.d(TAG, "No available drivers!");
            return START_NOT_STICKY;
        }

        // Open a connection to the first available driver.
        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
        if (connection == null) {
            // You probably need to call UsbManager.requestPermission(driver.getDevice(), ..)
            Log.d(TAG, "Connection is null!");
            return START_NOT_STICKY;
        }

        sPort = driver.getPorts().get(0);

        // Determine baud rate to use
        int baudRate = Integer.parseInt(sharedPreferences.getString("si_station_baud", "38400"));
        if (baudRate <= 0)
            baudRate = 38400;

        try {
            sPort.open(connection);
            sPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

        } catch (IOException e) {
            Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
            try {
                sPort.close();
            } catch (IOException e2) {
                // Ignore.
            }
            sPort = null;
            return START_NOT_STICKY;
        }

        onDeviceStateChange();

        return START_STICKY;
    }

    private void stopIoManager() {
        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    private void startIoManager() {
        if (sPort != null) {
            Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(sPort, mListener);
            mExecutor.submit(mSerialIoManager);
        }
    }

    private void onDeviceStateChange() {
        stopIoManager();
        startIoManager();
    }

    @Override
    public void onDestroy() {
        try {
            sPort.close();
        } catch (IOException e) {
            // Do nothing
        }
    }

    private int[] byteArrayToUnsignedIntArray(byte[] in) {
        int out[] = new int[in.length];
        for (int i = 0; i < in.length; i++)
            out[i] = (char) in[i] & 0xff;
        return out;
    }

    private void parseInput(int[] buf, int index) {
        for (; index < buf.length; index++) {
            if (buf[index] == START_TX)
                break;
        }

        if (index >= buf.length)
            return;

        Log.e(TAG, "buffer starts with " + buf[index] + " " + buf[index + 1] + " " + buf[index + 2] + " index is " + index);

        switch (buf[index + 1]) {
            case CONTROL_EXTENDED:
            {
                // Check and make sure index isn't greater than permitted
                if (buf.length - index < 11)
                    return;

                int controlNum = buf[index + 4] | buf[index + 3] << 8;
                int shortCard = buf[index + 8] | buf[index + 7] << 8;
                int series = buf[index + 6];
                int card = buf[index + 8] | buf[index + 7] << 8 | buf[index + 6] << 16 | buf[index + 5] << 24;

                if (series <= 4 && series >= 1)
                    card = shortCard + 100000 * series;

                int time = 0;
                if ((buf[index + 9] & 0x1) == 0x1)
                    time = 3600 * 12;
                time += buf[index + 11] | buf[index + 10] << 8;

                sendSms(card, time);

                index += 11;

                break;
            }
            case CONTROL_NORMAL:
            {
                // TODO
                Log.e(TAG, "Non-extended protocol isn't supported yet!");
                break;
            }
            default:
            {
                Log.e(TAG, "Read unknown byte " + (int) buf[index + 1]);
            }
        }

        // Find end of TX
        for (; index < buf.length; index++) {
            if (buf[index] == END_TX)
                break;
        }
        index++;

        parseInput(buf, index);
    }

    private void sendSms(int card, int time) {
        int control = Integer.parseInt(sharedPreferences.getString("si_control_number", "0"));
        String msg = "SMSRC " + control + " " + card + " " + time;

        Log.d(TAG, "Sending msg " + msg);

        SmsManager manager = SmsManager.getDefault();
        String destination = sharedPreferences.getString("remote_phone_number", null);
        if (destination != null) {
            manager.sendTextMessage(destination, null, msg, null, null);
        }
    }
}
