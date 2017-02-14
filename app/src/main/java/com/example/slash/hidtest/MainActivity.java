package com.example.slash.hidtest;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.hardware.usb.UsbManager;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import java.util.HashMap;
import android.util.Log;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbEndpoint;
import android.view.View;
import android.widget.TextView;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import java.util.ArrayList;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbConstants;
import android.view.WindowManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import java.lang.Thread;
import java.nio.ByteBuffer;
import android.hardware.usb.UsbRequest;
import android.os.Handler;
import android.os.Message;


public class MainActivity extends AppCompatActivity implements Runnable {

    private static final int VID = 5840;
    private static final int PID = 2879;
    private final static String TAG = "hidtest";

    private TextView mCountTextView;

    private int param1;

    ArrayList<String> list = new ArrayList<>();

    private PendingIntent mPermissionIntent;
    private UsbManager mUsbManager;
    private UsbDevice mDevice;
    private UsbDeviceConnection mConnection;
    private UsbEndpoint mEndpointIntr;
    private boolean mRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mCountTextView = (TextView)findViewById(R.id.textview2);

        ListView lvMain = (ListView) findViewById(R.id.lvMain);

        // создаем адаптер
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, list);

        // присваиваем адаптер списку
        lvMain.setAdapter(adapter);

        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);

        Intent intent = getIntent();
        String action = intent.getAction();
        UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            setDevice(device);
        } else {
            searchForDevice();
        }

    }

    @Override
    public void onStart() {
        super.onStart();

        //Keep the screen on while using the application
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        //Register receiver for further events
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(mUsbReceiver, filter);
    }

    @Override
    protected void onStop() {
        super.onStop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        unregisterReceiver(mUsbReceiver);
        mRunning = false;
    }

    private static final String ACTION_USB_PERMISSION = "com.examples.usb.scalemonitor.USB_PERMISSION";

    private BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            //If our device is detached, disconnect
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                list.add("Device Detached");
                if (mDevice != null && mDevice.equals(device)) {
                    setDevice(null);
                }
            }
            //If a new device is attached, connect to it
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                list.add("Device Attached");
                mUsbManager.requestPermission(device, mPermissionIntent);
            }
            //If this is our permission request, check result
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        && device != null) {
                    list.add("Connect Device");
                    setDevice(device);
                } else {
                    list.add("permission denied for device " + device);
                    setDevice(null);
                }
            }
        }
    };

    private void searchForDevice()
    {
        //If we find our device already attached, connect to it
        HashMap<String, UsbDevice> devices = mUsbManager.getDeviceList();
        UsbDevice selected = null;
        for (UsbDevice device : devices.values()) {
            list.add(device.getVendorId()+" "+device.getProductId());
            if (device.getVendorId() == VID && device.getProductId() == PID) {
                selected = device;
                break;
            }
        }
        //Request a connection
        if (selected != null) {
            if (mUsbManager.hasPermission(selected)) {
                list.add("setDevice"+selected);
                setDevice(selected);
            } else {
                mUsbManager.requestPermission(selected, mPermissionIntent);
            }
        }
    }

    private void setDevice(UsbDevice device) {
        list.add("setDevice " + device);
        if (device == null) {
            //Cancel connections
            mConnection = null;
            mRunning = false;

            return;
        }

        //Verify the device has what we need
        if (device.getInterfaceCount() != 1) {
            list.add( "could not find interface");
            return;
        }

        UsbInterface intf = device.getInterface(0);
        // device should have one endpoint
        if (intf.getEndpointCount() != 2) {
            list.add( "could not find endpoint");
            return;
        }
        // endpoint should be of type interrupt
        UsbEndpoint ep = intf.getEndpoint(0);
        if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_INT
                || ep.getDirection() != UsbConstants.USB_DIR_IN) {
            list.add("endpoint is not Interrupt IN");
            return;
        }
        mDevice = device;
        mEndpointIntr = ep;

        UsbDeviceConnection connection = mUsbManager.openDevice(device);
        if (connection != null && connection.claimInterface(intf, true)) {
                mConnection = connection;

            list.add("HID_SetReport");
            HID_SetReport(mConnection);

            //Start the polling thread
            mRunning = true;

            Thread thread = new Thread(null, this, "ScaleMonitor");

            thread.start();
        } else {
            mConnection = null;
            mRunning = false;
        }
    }

    private void HID_SetReport(UsbDeviceConnection connection) {
        int requestType = 0x21; // 0010 0001b
        int request = 0x09; //HID SET_REPORT
        int value = 0x0302; //Feature report, ID = 2
        int index = 0; //Interface 0
        int length = 2;

        byte[] buffer = new byte[length];
        buffer[0] = 0x00; //Report ID = 2
        buffer[1] = 0x00; //Set the zero flag

        connection.controlTransfer(requestType, request, value, index, buffer, length, 2000);
    }

    public void FindBtnClick(View view) {
        searchForDevice();
    }


    @Override
    public void run() {
        ByteBuffer buffer=ByteBuffer.allocate(8);
        UsbRequest request=new UsbRequest();
        request.initialize(mConnection, mEndpointIntr);
        while (mRunning) {
            request.queue(buffer, 8);
            if(mConnection.requestWait()==request){
                byte[] raw=new byte[8];
                buffer.get(raw);
                buffer.clear();
                list.add("new data");
                postWeightData(raw);
            }

            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Log.w(TAG, "Read Interrupted");
            }
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for(byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }

        return sb.toString();
    }

    public void postWeightData(byte[] data) {
        mHandler.sendMessage(Message.obtain(mHandler, MSG_DATA, data));
    }

    private static final int MSG_DATA = 101;
    private Handler mHandler = new Handler() {

        public void handleMessage(Message msg) {
            byte[] data = (byte[]) msg.obj;
            //      ASD[t]=bytesToHex(data);

            list.add("Raw: "+bytesToHex(data));

            switch (msg.what) {
                case MSG_DATA:
                    break;
                default:
                    break;
            }
        }
    };


}
