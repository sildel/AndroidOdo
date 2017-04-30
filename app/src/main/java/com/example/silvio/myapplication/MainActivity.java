package com.example.silvio.myapplication;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    public static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    public static final int VENDOR_ID = 0x04D8;
    public static final double KM_FACTOR = 2.0 * Math.PI * 24.5 / 28.0 / 100.0 / 1000.0;
    public static final double DELTA_FACTOR = 2.0 * Math.PI * 24.5 / 28.0 / 100.0 / 1000.0 * 2.0 * 3600.0;
    private UsbManager usbManager;
    private UsbDeviceConnection connection = null;
    private UsbDevice device;
    private UsbSerialPort port;
    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(mUsbReceiver, filter);
    }

    private boolean running;
    private Thread odoThread;
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Toast.makeText(MainActivity.this, R.string.deviceReadyMessage, Toast.LENGTH_LONG).show();
                            connection = usbManager.openDevice(device);
                            UsbSerialDriver driver = new CdcAcmSerialDriver(device);
                            port = driver.getPorts().get(0);
                            try {
                                port.open(connection);
                                port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                                running = true;

                                odoThread = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        while (running) {
                                            try {
                                                port.write(new byte[]{'e'}, 1000);
                                                final byte buffer[] = new byte[16];
                                                int numBytesRead = port.read(buffer, 1000);
                                                if (numBytesRead == 6) {
                                                    handler.post(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            unpackAndShow(buffer);
                                                        }
                                                    });
                                                } else {
                                                    Toast.makeText(MainActivity.this, R.string.badResponseMessage, Toast.LENGTH_LONG).show();
                                                }
                                                Thread.sleep(500);
                                            } catch (IOException e) {
                                                Toast.makeText(MainActivity.this, R.string.deviceErrorMessage, Toast.LENGTH_LONG).show();
                                            } catch (InterruptedException e) {
                                                e.printStackTrace();
                                            }
                                        }
                                    }
                                });
                                odoThread.start();
                            } catch (IOException e) {
                                Toast.makeText(MainActivity.this, R.string.deviceErrorMessage, Toast.LENGTH_LONG).show();
                            }
                        }
                    } else {
                        Toast.makeText(MainActivity.this, R.string.permissionDeniedMessage, Toast.LENGTH_LONG).show();
                    }
                }
            } else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                Toast.makeText(MainActivity.this, R.string.deviceAttached, Toast.LENGTH_LONG).show();
            } else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                Toast.makeText(MainActivity.this, R.string.deviceDetachedMessage, Toast.LENGTH_LONG).show();
            }
        }
    };

    @Override
    public void onBackPressed() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setMessage(R.string.connectionCloseMessage)
                .setTitle(R.string.onBackMessage)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        finishApp();
                    }
                })
                .setNegativeButton(R.string.cancelText, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {

                    }
                });
        builder.create().show();
    }

    private void finishApp() {
        running = false;
        try {
            if (odoThread != null)
                odoThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        try {
            if (port != null)
                port.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        super.onBackPressed();
    }

    private void unpackAndShow(byte[] buffer) {
        long km = (buffer[3] << 24) + (buffer[2] << 16) + (buffer[1] << 8) + buffer[0];
        long delta = (buffer[5] << 8) + buffer[4];

        double kmToShow = km * KM_FACTOR;
        double deltaToShow = delta * DELTA_FACTOR;

        TextView disTextView = (TextView) findViewById(R.id.distanceTextView);
        TextView speedTextView = (TextView) findViewById(R.id.speedTextView);
        disTextView.setText(String.format("%6.2f", kmToShow) + getString(R.string.kmSuffix));
        speedTextView.setText(String.format("%4.2f", deltaToShow) + getString(R.string.kmPerHourSuffix));
    }

    public void onClickStart(View view) {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (!deviceList.isEmpty()) {
            for (Map.Entry<String, UsbDevice> entry : deviceList.entrySet()) {
                UsbDevice device = entry.getValue();
                if (device.getVendorId() == VENDOR_ID) {
                    Toast.makeText(this, R.string.odoFoundMessage, Toast.LENGTH_LONG).show();
                    PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                            new Intent(ACTION_USB_PERMISSION), 0);
                    usbManager.requestPermission(device, pi);
                }
            }
        }
    }
}
