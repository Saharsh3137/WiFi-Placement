package com.example.wifi_optimization;

import android.Manifest;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.View;
import android.widget.*;

import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class MainActivity extends ComponentActivity {

    TextView statusText, nodeStatusText, rssiText, latencyText, packetLossText, qualityText;
    Button connectButton, disconnectButton, sendWifiBtn;
    EditText ssidInput, passInput;
    LinearLayout wifiLayout;
    boolean isProvisioning = false;
    boolean wifiAlreadySent = false;
    LineChart rssiChart, latencyChart, packetChart;

    BluetoothAdapter bluetoothAdapter;
    BluetoothSocket socket;
    InputStream inputStream;
    OutputStream outputStream;

    String DEVICE_ADDRESS = "B0:CB:D8:C6:66:7E";
    UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    class NodeData {
        float rssi, latency, loss;
        long lastUpdate;
    }

    NodeData[] nodes = new NodeData[3];

    int i1 = 0, i2 = 0, i3 = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        nodeStatusText = findViewById(R.id.nodeStatusText);
        rssiText = findViewById(R.id.rssiText);
        latencyText = findViewById(R.id.latencyText);
        packetLossText = findViewById(R.id.packetLossText);
        qualityText = findViewById(R.id.qualityText);

        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);

        wifiLayout = findViewById(R.id.wifiLayout);
        ssidInput = findViewById(R.id.ssidInput);
        passInput = findViewById(R.id.passInput);
        sendWifiBtn = findViewById(R.id.sendWifiBtn);

        rssiChart = findViewById(R.id.rssiChart);
        latencyChart = findViewById(R.id.latencyChart);
        packetChart = findViewById(R.id.packetChart);

        setupChart(rssiChart);
        initChartData(rssiChart);

        setupChart(latencyChart);
        initChartData(latencyChart);

        setupChart(packetChart);
        initChartData(packetChart);

        for (int i = 0; i < 3; i++) nodes[i] = new NodeData();

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                    }, 1);
        }

        connectButton.setOnClickListener(v -> connectBT());
        disconnectButton.setOnClickListener(v -> disconnectBT());
        sendWifiBtn.setOnClickListener(v -> sendWiFiCredentials());

    }

    // ---------------- BLUETOOTH ----------------

    private void connectBT() {
        statusText.setText("Connecting...");
        statusText.setTextColor(Color.YELLOW);

        new Thread(() -> {
            try {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(DEVICE_ADDRESS);

                bluetoothAdapter.cancelDiscovery();

                socket = device.createRfcommSocketToServiceRecord(uuid);
                socket.connect();

                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();

                runOnUiThread(() -> {
                    statusText.setText("Connected");
                    statusText.setTextColor(Color.GREEN);

                    connectButton.setVisibility(View.GONE);
                    disconnectButton.setVisibility(View.VISIBLE);

                    if (!wifiAlreadySent) {
                        wifiLayout.setVisibility(View.VISIBLE);
                    } else {
                        wifiLayout.setVisibility(View.GONE);
                    }
                });

                readData();

            } catch (Exception e) {
                e.printStackTrace();

                runOnUiThread(() -> {
                    statusText.setText("Connection Failed");
                    statusText.setTextColor(Color.RED);
                });
            }
        }).start();
    }

    private void disconnectBT() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}

        statusText.setText("Disconnected");
        statusText.setTextColor(Color.RED);

        connectButton.setVisibility(View.VISIBLE);
        disconnectButton.setVisibility(View.GONE);
        wifiLayout.setVisibility(View.GONE);
    }

    // ---------------- YOUR ORIGINAL WORKING METHOD ----------------

    private void sendWiFiCredentials() {
        if (socket == null || !socket.isConnected() || outputStream == null) {
            Toast.makeText(this, "Bluetooth not connected!", Toast.LENGTH_SHORT).show();
            return;
        }
        wifiAlreadySent = true;
        String ssid = ssidInput.getText().toString().trim();
        String pass = passInput.getText().toString().trim();

        if (ssid.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "SSID and Password cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }

        String payload = "WIFI:" + ssid + "," + pass + "\n";

        new Thread(() -> {
            try {
                isProvisioning = true;

                outputStream.write(payload.getBytes());
                outputStream.flush();

                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Deploying WiFi...", Toast.LENGTH_SHORT).show();

                    // UI updates
                    wifiLayout.setVisibility(View.GONE);
                    statusText.setText("Deploying...");
                    statusText.setTextColor(Color.CYAN);
                });

            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Failed to send", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ---------------- DATA ----------------

    private void readData() {
        new Thread(() -> {
            byte[] buf = new byte[1024];

            while (true) {
                try {
                    int n = inputStream.read(buf);
                    process(new String(buf, 0, n));
                } catch (Exception e) {

                    runOnUiThread(() -> {
                        if (isProvisioning) {
                            statusText.setText("Rebooting ESP32...");
                            statusText.setTextColor(Color.YELLOW);
                        } else {
                            statusText.setText("Disconnected");
                            statusText.setTextColor(Color.RED);
                        }

                        connectButton.setVisibility(View.VISIBLE);
                        disconnectButton.setVisibility(View.GONE);
                    });

                    if (isProvisioning) {
                        reconnectBluetooth();
                    }

                    break;
                }
            }
        }).start();
    }
    private void reconnectBluetooth() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {

            statusText.setText("Reconnecting...");
            statusText.setTextColor(Color.YELLOW);

            connectBT();

            // reset flag after reconnect attempt
            isProvisioning = false;

        }, 4000); // wait for ESP32 reboot
    }
    private void process(String d) {
        runOnUiThread(() -> {
            try {
                String[] p = d.trim().split(",");
                if (p.length < 7) return;

                int id = Integer.parseInt(p[0]) - 1;

                nodes[id].rssi = Float.parseFloat(p[1]);
                nodes[id].loss = Float.parseFloat(p[4]);
                nodes[id].latency = Float.parseFloat(p[6]);
                nodes[id].lastUpdate = System.currentTimeMillis();

                updateUI();

            } catch (Exception ignored) {}
        });
    }

    private void updateUI() {

        NodeData avg = avg();
        rssiText.setText("Avg RSSI: " + avg.rssi);
        latencyText.setText("Avg Latency: " + avg.latency);
        packetLossText.setText("Avg Loss: " + avg.loss);

        updateStatus();
        updateGraphs();
    }

    private void updateGraphs() {

        LineData rssiData = rssiChart.getData();
        LineData latencyData = latencyChart.getData();
        LineData packetData = packetChart.getData();

        NodeData avg = avg();

        for (int i = 0; i < 3; i++) {

            NodeData n = nodes[i];

            if (System.currentTimeMillis() - n.lastUpdate < 5000) {

                rssiData.addEntry(new Entry(i1, n.rssi), i);
                latencyData.addEntry(new Entry(i2, n.latency), i);
                packetData.addEntry(new Entry(i3, n.loss), i);
            }
        }

        // ⭐ Average line (dataset index 3)
        rssiData.addEntry(new Entry(i1, avg.rssi), 3);
        latencyData.addEntry(new Entry(i2, avg.latency), 3);
        packetData.addEntry(new Entry(i3, avg.loss), 3);

        rssiData.notifyDataChanged();
        latencyData.notifyDataChanged();
        packetData.notifyDataChanged();

        rssiChart.notifyDataSetChanged();
        latencyChart.notifyDataSetChanged();
        packetChart.notifyDataSetChanged();

        rssiChart.moveViewToX(i1);
        latencyChart.moveViewToX(i2);
        packetChart.moveViewToX(i3);

        i1++;
        i2++;
        i3++;
    }

    private NodeData avg() {
        NodeData a = new NodeData();
        int c = 0;

        for (NodeData n : nodes) {
            if (System.currentTimeMillis() - n.lastUpdate < 5000) {
                a.rssi += n.rssi;
                a.latency += n.latency;
                a.loss += n.loss;
                c++;
            }
        }

        if (c > 0) {
            a.rssi /= c;
            a.latency /= c;
            a.loss /= c;
        }

        return a;
    }

    private void updateStatus() {
        String s = "";
        for (int i = 0; i < 3; i++) {
            s += "N" + (i + 1) + ":" +
                    (System.currentTimeMillis() - nodes[i].lastUpdate < 5000 ? "ON " : "OFF ");
        }
        nodeStatusText.setText(s);
    }

    private void setupChart(LineChart chart) {

        chart.setData(new LineData());
        chart.getLegend().setTextColor(Color.WHITE);

        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(true);

        chart.setDrawGridBackground(false);
        chart.setDrawBorders(false);

        chart.getAxisRight().setEnabled(false);

        // ❌ REMOVE GRID LINES
        chart.getXAxis().setDrawGridLines(false);
        chart.getAxisLeft().setDrawGridLines(false);

        chart.getXAxis().setDrawAxisLine(false);
        chart.getAxisLeft().setDrawAxisLine(false);

        chart.getXAxis().setTextColor(Color.GRAY);
        chart.getAxisLeft().setTextColor(Color.GRAY);

        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(false);

        chart.setBackgroundColor(Color.parseColor("#0B0F1A"));
    }

    private void initChartData(LineChart chart) {

        LineData data = new LineData();

        // Node 1
        LineDataSet n1 = new LineDataSet(null, "Node 1");
        n1.setColor(Color.RED);
        n1.setDrawCircles(false);
        n1.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        n1.setLineWidth(2f);

        // Node 2
        LineDataSet n2 = new LineDataSet(null, "Node 2");
        n2.setColor(Color.GREEN);
        n2.setDrawCircles(false);
        n2.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        n2.setLineWidth(2f);

        // Node 3
        LineDataSet n3 = new LineDataSet(null, "Node 3");
        n3.setColor(Color.YELLOW);
        n3.setDrawCircles(false);
        n3.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        n3.setLineWidth(2f);

        // Average ⭐
        LineDataSet avg = new LineDataSet(null, "Average");
        avg.setColor(Color.WHITE);
        avg.setDrawCircles(false);
        avg.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        avg.setLineWidth(3f);
        avg.enableDashedLine(10f, 5f, 0f);

        data.addDataSet(n1);
        data.addDataSet(n2);
        data.addDataSet(n3);
        data.addDataSet(avg);

        chart.setData(data);
    }
}