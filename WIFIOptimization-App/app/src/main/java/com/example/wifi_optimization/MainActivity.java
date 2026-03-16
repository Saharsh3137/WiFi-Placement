package com.example.wifi_optimization;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ComponentActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class MainActivity extends ComponentActivity {

    private TextView statusText, rssiText, latencyText, packetLossText, qualityText;
    private Button connectButton, disconnectButton;

    // UI elements for Provisioning
    private EditText ssidInput, passInput;
    private Button deployButton;
    private LinearLayout provisioningLayout;

    private LineChart rssiChart, latencyChart, packetChart;
    private LineData rssiData, latencyData, packetData;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;

    private final String DEVICE_ADDRESS = "B0:CB:D8:C6:66:7E";
    private final UUID uuid =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private int rssiIndex = 0;
    private int latencyIndex = 0;
    private int packetIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        rssiText = findViewById(R.id.rssiText);
        latencyText = findViewById(R.id.latencyText);
        packetLossText = findViewById(R.id.packetLossText);
        qualityText = findViewById(R.id.qualityText);

        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);

        // Initialize Provisioning Elements
        provisioningLayout = findViewById(R.id.provisioningLayout);
        ssidInput = findViewById(R.id.ssidInput);
        passInput = findViewById(R.id.passInput);
        deployButton = findViewById(R.id.deployButton);

        rssiChart = findViewById(R.id.rssiChart);
        latencyChart = findViewById(R.id.latencyChart);
        packetChart = findViewById(R.id.packetChart);

        setupChart(rssiChart);
        setupChart(latencyChart);
        setupChart(packetChart);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                    }, 1);
        }

        connectButton.setOnClickListener(v -> {
            statusText.setText("Connecting...");
            statusText.setTextColor(Color.WHITE);
            connectBluetooth();
        });

        disconnectButton.setOnClickListener(v -> disconnectBluetooth());

        // Listener for sending the Wi-Fi credentials
        deployButton.setOnClickListener(v -> sendWiFiCredentials());
    }

    private void setupChart(LineChart chart) {
        LineData data = new LineData();
        chart.setData(data);
        chart.setBackgroundColor(Color.parseColor("#121826"));
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setDrawGridBackground(false);
        chart.setDrawBorders(false);
        chart.getAxisRight().setEnabled(false);
        chart.getXAxis().setDrawGridLines(false);
        chart.getAxisLeft().setDrawGridLines(false);
        chart.getXAxis().setTextColor(Color.GRAY);
        chart.getAxisLeft().setTextColor(Color.GRAY);
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(false);
    }

    private void connectBluetooth() {
        new Thread(() -> {
            try {
                if (ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() ->
                            statusText.setText("Permission Denied"));
                    return;
                }

                BluetoothDevice device =
                        bluetoothAdapter.getRemoteDevice(DEVICE_ADDRESS);

                bluetoothAdapter.cancelDiscovery();

                socket = device.createRfcommSocketToServiceRecord(uuid);
                socket.connect();

                // Open streams for sending and receiving data
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();

                runOnUiThread(() -> {
                    statusText.setText("Status: Connected");
                    statusText.setTextColor(Color.GREEN);
                    connectButton.setVisibility(View.GONE);
                    disconnectButton.setVisibility(View.VISIBLE);

                    // Show the Wi-Fi setup box
                    provisioningLayout.setVisibility(View.VISIBLE);
                });

                readData();

            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Connection Failed");
                    statusText.setTextColor(Color.RED);
                });
                e.printStackTrace();
            }
        }).start();
    }

    private void disconnectBluetooth() {
        try {
            if (socket != null) socket.close();

            statusText.setText("Status: Disconnected");
            statusText.setTextColor(Color.RED);

            connectButton.setVisibility(View.VISIBLE);
            disconnectButton.setVisibility(View.GONE);

            // Hide the Wi-Fi setup box
            provisioningLayout.setVisibility(View.GONE);

        } catch (Exception ignored) {}
    }

    private void sendWiFiCredentials() {
        if (socket == null || !socket.isConnected() || outputStream == null) {
            Toast.makeText(this, "Bluetooth not connected!", Toast.LENGTH_SHORT).show();
            return;
        }

        String ssid = ssidInput.getText().toString().trim();
        String pass = passInput.getText().toString().trim();

        if (ssid.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "SSID and Password cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }

        // The ESP32 expects: WIFI:NetworkName,Password\n
        String payload = "WIFI:" + ssid + "," + pass + "\n";

        new Thread(() -> {
            try {
                outputStream.write(payload.getBytes());
                outputStream.flush();

                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Credentials Sent!", Toast.LENGTH_LONG).show();
                    // Optional: Clear password after sending
                    passInput.setText("");
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed to send data", Toast.LENGTH_SHORT).show());
                e.printStackTrace();
            }
        }).start();
    }

    private void readData() {
        new Thread(() -> {
            byte[] buffer = new byte[1024];

            while (true) {
                try {
                    int bytes = inputStream.read(buffer);
                    String data = new String(buffer, 0, bytes);
                    processData(data);
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        statusText.setText("Disconnected");
                        statusText.setTextColor(Color.RED);
                        connectButton.setVisibility(View.VISIBLE);
                        disconnectButton.setVisibility(View.GONE);
                        provisioningLayout.setVisibility(View.GONE);
                    });
                    break;
                }
            }
        }).start();
    }

    private void processData(String data) {
        runOnUiThread(() -> {
            try {
                String[] parts = data.trim().split(",");
                if (parts.length < 7) return;

                float rssi = Float.parseFloat(parts[1]);
                float packetLoss = Float.parseFloat(parts[4]);
                float latency = Float.parseFloat(parts[6]);

                rssiText.setText("RSSI: " + rssi + " dBm");
                latencyText.setText("Latency: " + latency + " ms");
                packetLossText.setText("Packet Loss: " + packetLoss + "%");

                qualityText.setText("Signal Quality: " +
                        getSignalQuality((int) rssi));

                addEntry(rssiChart, rssi, rssiIndex++);
                addEntry(latencyChart, latency, latencyIndex++);
                addEntry(packetChart, packetLoss, packetIndex++);

            } catch (Exception ignored) {}
        });
    }

    private void addEntry(LineChart chart, float value, int index) {
        LineData data = chart.getData();
        LineDataSet dataSet;

        if (data.getDataSetCount() == 0) {
            dataSet = new LineDataSet(null, "Live Data");
            dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
            dataSet.setColor(Color.parseColor("#00E5FF"));
            dataSet.setLineWidth(2.5f);
            dataSet.setDrawCircles(false);
            dataSet.setDrawValues(false);
            dataSet.setDrawFilled(true);
            dataSet.setFillColor(Color.parseColor("#00E5FF"));
            dataSet.setFillAlpha(40);

            data.addDataSet(dataSet);
        } else {
            dataSet = (LineDataSet) data.getDataSetByIndex(0);
        }

        data.addEntry(new Entry(index, value), 0);
        data.notifyDataChanged();

        chart.notifyDataSetChanged();
        chart.setVisibleXRangeMaximum(40);
        chart.moveViewToX(data.getEntryCount());
    }

    private String getSignalQuality(int rssi) {
        if (rssi > -60) return "Excellent";
        if (rssi > -75) return "Good";
        if (rssi > -85) return "Weak";
        return "Poor";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
    }
}