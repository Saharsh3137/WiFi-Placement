package com.example.wifi_optimization;

import android.Manifest;
import android.bluetooth.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.*;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    // UI Elements
    TextView statusText, nodeStatusText, qualityText;
    TextView rssiText, latencyText, packetLossText;
    Button connectButton, disconnectButton, sendWifiBtn, toggleWifiBtn, resetStatsBtn;
    EditText ssidInput, passInput;
    View wifiCard, tabDashboard, tabAnalytics, tabPlacement;
    Button navDash, navAnalytics, navPlacement;
    Switch demoModeSwitch;
    LineChart rssiChart, latencyChart, packetChart;

    // Dynamic Mesh UI
    Button startCalibBtn;
    TextView calibStatusText, distanceReadoutText;
    FrameLayout heatmapContainer;
    HeatmapView heatmapView;

    // Bluetooth
    BluetoothAdapter bluetoothAdapter;
    BluetoothSocket socket;
    InputStream inputStream;
    OutputStream outputStream;
    String DEVICE_ADDRESS = "B0:CB:D8:C6:66:7E"; // Update this to your Master's MAC!
    UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    boolean isProvisioning = false;

    // Data Model
    class NodeData {
        float rssi, snr, voltage, loss, latency, jitter;
        long lastUpdate;
        double totalRssiSum = 0;
        long rssiCount = 0;
    }

    NodeData[] nodes = new NodeData[3];
    int graphIndex = 0;

    // Handlers
    Handler uiHandler = new Handler(Looper.getMainLooper());
    Runnable uiRefreshRunnable;
    Handler demoHandler = new Handler(Looper.getMainLooper());
    Runnable demoRunnable;
    Random random = new Random();

    float[] demoRssi = {-45f, -60f, -75f};
    float[] demoLatency = {12f, 25f, 45f};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        // Bind UI
        statusText = findViewById(R.id.statusText);
        nodeStatusText = findViewById(R.id.nodeStatusText);
        qualityText = findViewById(R.id.qualityText);
        rssiText = findViewById(R.id.rssiText);
        latencyText = findViewById(R.id.latencyText);
        packetLossText = findViewById(R.id.packetLossText);

        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        sendWifiBtn = findViewById(R.id.sendWifiBtn);
        toggleWifiBtn = findViewById(R.id.toggleWifiBtn);
        resetStatsBtn = findViewById(R.id.resetStatsBtn);

        ssidInput = findViewById(R.id.ssidInput);
        passInput = findViewById(R.id.passInput);
        wifiCard = findViewById(R.id.wifiCard);
        demoModeSwitch = findViewById(R.id.demoModeSwitch);

        tabDashboard = findViewById(R.id.tabDashboard);
        tabAnalytics = findViewById(R.id.tabAnalytics);
        tabPlacement = findViewById(R.id.tabPlacement);
        navDash = findViewById(R.id.navDash);
        navAnalytics = findViewById(R.id.navAnalytics);
        navPlacement = findViewById(R.id.navPlacement);

        rssiChart = findViewById(R.id.rssiChart);
        latencyChart = findViewById(R.id.latencyChart);
        packetChart = findViewById(R.id.packetChart);

        startCalibBtn = findViewById(R.id.startCalibBtn);
        calibStatusText = findViewById(R.id.calibStatusText);
        distanceReadoutText = findViewById(R.id.distanceReadoutText);
        heatmapContainer = findViewById(R.id.heatmapContainer);

        // Inject the custom Heatmap engine
        heatmapView = new HeatmapView(this);
        heatmapContainer.addView(heatmapView);

        for (int i = 0; i < 3; i++) nodes[i] = new NodeData();

        setupChart(rssiChart, -100f, -15f);
        initChartData(rssiChart);
        setupChart(latencyChart, 0f, 150f);
        initChartData(latencyChart);
        setupChart(packetChart, 0f, 20f);
        initChartData(packetChart);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, 1);
        }

        connectButton.setOnClickListener(v -> connectBT());
        disconnectButton.setOnClickListener(v -> disconnectBT());
        sendWifiBtn.setOnClickListener(v -> sendWiFiCredentials());

        toggleWifiBtn.setOnClickListener(v -> {
            boolean isConnected = (socket != null && socket.isConnected());
            if (!isConnected && !demoModeSwitch.isChecked()) {
                Toast.makeText(MainActivity.this, "Connect to hardware first!", Toast.LENGTH_SHORT).show();
                return;
            }
            wifiCard.setVisibility(wifiCard.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });

        resetStatsBtn.setOnClickListener(v -> resetSessionData());
        startCalibBtn.setOnClickListener(v -> triggerMeshSweep());

        setupNavigation();
        setupDemoMode();

        uiRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                updateUI();
                uiHandler.postDelayed(this, 400);
            }
        };
        uiHandler.post(uiRefreshRunnable);
    }

    private void triggerMeshSweep() {
        boolean isConnected = (socket != null && socket.isConnected());
        if (!isConnected && !demoModeSwitch.isChecked()) {
            Toast.makeText(this, "Connect Bluetooth First", Toast.LENGTH_SHORT).show();
            return;
        }

        startCalibBtn.setEnabled(false);
        startCalibBtn.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.GRAY));

        if (demoModeSwitch.isChecked()) {
            // ── DEMO: generate heatmap instantly, no countdown ──────────────
            calibStatusText.setText("Generating Heatmap...");
            calibStatusText.setTextColor(Color.parseColor("#FFD740"));
            uiHandler.postDelayed(() -> {
                process("MESH,-45.0,-55.0,-62.0,-40.5,-51.0,-65.0\n");
                startCalibBtn.setEnabled(true);
                startCalibBtn.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#00C6FF")));
            }, 400); // tiny delay so the status message is visible
            return;
        }

        // ── HARDWARE: real 18-second sweep with per-zone countdown ──────────
        new CountDownTimer(18000, 1000) {
            public void onTick(long millisUntilFinished) {
                int sec = (int) (millisUntilFinished / 1000);
                if (sec > 13) calibStatusText.setText("Scanning Zone 1... (" + sec + "s)");
                else if (sec > 8) calibStatusText.setText("Scanning Zone 2... (" + sec + "s)");
                else if (sec > 3) calibStatusText.setText("Scanning Zone 3... (" + sec + "s)");
                else calibStatusText.setText("Building Heatmap... (" + sec + "s)");
                calibStatusText.setTextColor(Color.parseColor("#FFD740"));
                if (sec == 17) {
                    try { outputStream.write("CALIBRATE\n".getBytes()); outputStream.flush(); }
                    catch (Exception ignored) {}
                }
            }
            public void onFinish() {
                startCalibBtn.setEnabled(true);
                startCalibBtn.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#00C6FF")));
            }
        }.start();
    }

    private void resetSessionData() {
        for (int i = 0; i < 3; i++) nodes[i] = new NodeData();
        graphIndex = 0;
        rssiChart.clear();
        initChartData(rssiChart);
        latencyChart.clear();
        initChartData(latencyChart);
        packetChart.clear();
        initChartData(packetChart);
        qualityText.setText("--/100");
        distanceReadoutText.setText("Awaiting mesh data...");
        Toast.makeText(this, "Session Data Cleared", Toast.LENGTH_SHORT).show();
        updateUI();
    }

    private void setupNavigation() {
        navDash.setOnClickListener(v -> switchTab(tabDashboard, navDash));
        navAnalytics.setOnClickListener(v -> switchTab(tabAnalytics, navAnalytics));
        navPlacement.setOnClickListener(v -> switchTab(tabPlacement, navPlacement));
    }

    private void switchTab(View activeTab, Button activeBtn) {
        tabDashboard.setVisibility(View.GONE);
        tabAnalytics.setVisibility(View.GONE);
        tabPlacement.setVisibility(View.GONE);
        navDash.setTextColor(Color.parseColor("#888888"));
        navAnalytics.setTextColor(Color.parseColor("#888888"));
        navPlacement.setTextColor(Color.parseColor("#888888"));

        activeTab.setVisibility(View.VISIBLE);
        activeBtn.setTextColor(Color.parseColor("#00E5FF"));
    }

    private void setupDemoMode() {
        demoModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                disconnectBT();
                statusText.setText("Demo Mode Active");
                statusText.setTextColor(Color.parseColor("#FFD740"));
                wifiCard.setVisibility(View.GONE);
                // Setup button hidden in demo mode — no real BT connection
                toggleWifiBtn.setVisibility(View.GONE);

                demoRunnable = new Runnable() {
                    @Override
                    public void run() {
                        for (int i = 0; i < 3; i++) {
                            demoRssi[i] += (random.nextFloat() - 0.5f) * 4f;
                            demoRssi[i] = Math.max(-95f, Math.min(-35f, demoRssi[i]));
                            demoLatency[i] += (random.nextFloat() - 0.5f) * 5f;
                            demoLatency[i] = Math.max(5f, Math.min(120f, demoLatency[i]));

                            int s = 25;
                            float v = 3.6f + (random.nextFloat() * 0.2f);
                            float l = random.nextFloat() < 0.05 ? 2.0f : 0.0f;
                            int j = random.nextInt(5);

                            String fakeData = String.format(Locale.US, "%d,%d,%d,%.2f,%.2f,%d,%d\n",
                                    i + 1, (int) demoRssi[i], s, v, l, j, (int) demoLatency[i]);
                            process(fakeData);
                        }
                        demoHandler.postDelayed(this, 1000);
                    }
                };
                demoHandler.post(demoRunnable);
            } else {
                demoHandler.removeCallbacks(demoRunnable);
                statusText.setText("Disconnected");
                statusText.setTextColor(Color.parseColor("#FF5252"));
                resetSessionData();
            }
        });
    }

    private void connectBT() {
        if (demoModeSwitch.isChecked()) return;
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
                    statusText.setText("Connected to Master");
                    statusText.setTextColor(Color.GREEN);
                    connectButton.setVisibility(View.GONE);
                    disconnectButton.setVisibility(View.VISIBLE);
                    // Show Setup only when truly connected (not in demo)
                    toggleWifiBtn.setVisibility(View.VISIBLE);
                });
                readData();
            } catch (Exception e) {
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
        } catch (Exception ignored) {
        }
        statusText.setText("Disconnected");
        statusText.setTextColor(Color.RED);
        connectButton.setVisibility(View.VISIBLE);
        disconnectButton.setVisibility(View.GONE);
        wifiCard.setVisibility(View.GONE);
        // Hide Setup button when not connected
        toggleWifiBtn.setVisibility(View.GONE);
    }

    private void sendWiFiCredentials() {
        if (socket == null || !socket.isConnected() || outputStream == null) {
            Toast.makeText(this, "Bluetooth not connected!", Toast.LENGTH_SHORT).show();
            return;
        }
        String ssid = ssidInput.getText().toString().trim();
        String pass = passInput.getText().toString().trim();

        if (ssid.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "SSID and Pass cannot be empty", Toast.LENGTH_SHORT).show();
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
                    wifiCard.setVisibility(View.GONE);
                    statusText.setText("Deploying...");
                    statusText.setTextColor(Color.CYAN);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void readData() {
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String line;
                while ((line = reader.readLine()) != null) {
                    process(line);
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isProvisioning) {
                        statusText.setText("Rebooting ESP32...");
                        statusText.setTextColor(Color.YELLOW);
                    } else {
                        disconnectBT();
                    }
                });
                if (isProvisioning) reconnectBluetooth();
            }
        }).start();
    }

    private void reconnectBluetooth() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            statusText.setText("Reconnecting...");
            statusText.setTextColor(Color.YELLOW);
            connectBT();
            isProvisioning = false;
        }, 4000);
    }

    private void process(String d) {
        try {
            String clean = d.replaceAll("[\\n\\r]", "").trim();

            if (clean.startsWith("MESH,")) {
                String[] parts = clean.split(",");
                if (parts.length >= 7) {
                    float rssi12 = Float.parseFloat(parts[1]);
                    float rssi13 = Float.parseFloat(parts[2]);
                    float rssi23 = Float.parseFloat(parts[3]);
                    float rssiR1 = Float.parseFloat(parts[4]);
                    float rssiR2 = Float.parseFloat(parts[5]);
                    float rssiR3 = Float.parseFloat(parts[6]);

                    double d12 = rssi12 <= -90 ? 15.0 : Math.pow(10, (-35.0 - rssi12) / 25.0);
                    double d13 = rssi13 <= -90 ? 15.0 : Math.pow(10, (-35.0 - rssi13) / 25.0);
                    double d23 = rssi23 <= -90 ? 15.0 : Math.pow(10, (-35.0 - rssi23) / 25.0);

                    double r1 = rssiR1 <= -90 ? 20.0 : Math.pow(10, (-46.5 - rssiR1) / 31.9);
                    double r2 = rssiR2 <= -90 ? 20.0 : Math.pow(10, (-46.5 - rssiR2) / 31.9);
                    double r3 = rssiR3 <= -90 ? 20.0 : Math.pow(10, (-46.5 - rssiR3) / 31.9);

                    heatmapView.updateMeshGeometry((float) d12, (float) d13, (float) d23, (float) r1, (float) r2, (float) r3);

                    runOnUiThread(() -> {
                        calibStatusText.setText("Heatmap Complete!");
                        calibStatusText.setTextColor(Color.parseColor("#00E5FF"));
                        // distanceReadoutText dynamically handled in updateUI()
                    });
                }
                return;
            } else if (clean.startsWith("MESH_FAIL")) {
                runOnUiThread(() -> {
                    calibStatusText.setText("Mesh Calibration Failed. Try again.");
                    calibStatusText.setTextColor(Color.RED);
                    startCalibBtn.setEnabled(true);
                    startCalibBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF")));
                });
                return;
            }

            String[] p = clean.split(",");
            if (p.length < 7) return;

            int id = Integer.parseInt(p[0]) - 1;
            if (id >= 0 && id < 3) {
                nodes[id].rssi = Float.parseFloat(p[1]);
                nodes[id].snr = Float.parseFloat(p[2]);
                nodes[id].voltage = Float.parseFloat(p[3]);
                nodes[id].loss = Float.parseFloat(p[4]);
                nodes[id].jitter = Float.parseFloat(p[5]);
                nodes[id].latency = Float.parseFloat(p[6]);
                nodes[id].lastUpdate = System.currentTimeMillis();

                nodes[id].totalRssiSum += nodes[id].rssi;
                nodes[id].rssiCount++;
            }
        } catch (Exception ignored) {
        }
    }

    private void updateUI() {
        NodeData avg = getAverages();
        rssiText.setText(String.format(Locale.US, "Avg RSSI: %.1f dBm", avg.rssi));
        latencyText.setText(String.format(Locale.US, "Avg Ping: %.1f ms", avg.latency));
        packetLossText.setText(String.format(Locale.US, "Avg Loss: %.1f %%", avg.loss));

        float finalScore = 0;
        if (avg.rssi != 0) {
            float rssiScore = Math.max(0, Math.min(100, (avg.rssi + 85) * (100f / 45f)));
            finalScore = rssiScore - (avg.latency > 30 ? 15 : 0) - (avg.loss * 2);
            finalScore = Math.max(0, finalScore);
            qualityText.setText(String.format(Locale.US, "%.0f/100", finalScore));
        }

        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            boolean isOnline = (System.currentTimeMillis() - nodes[i].lastUpdate < 5000) && nodes[i].lastUpdate != 0;
            // Node header on its own line
            s.append("Node ").append(i + 1).append(":\n");
            if (isOnline) {
                float runningAvgRssi = 0;
                if (nodes[i].rssiCount > 0) {
                    runningAvgRssi = (float) (nodes[i].totalRssiSum / nodes[i].rssiCount);
                }
                // Details indented on next line
                s.append("  🟢 ")
                        .append(String.format(Locale.US, "%.0f ms", nodes[i].latency))
                        .append("  🔋 ")
                        .append(String.format(Locale.US, "%.1f V", nodes[i].voltage))
                        .append("  ")
                        .append(String.format(Locale.US, "%.1f dBm", runningAvgRssi));
            } else {
                s.append("  🔴 Offline");
            }
            if (i < 2) s.append("\n");
        }
        nodeStatusText.setText(s.toString());

        // Update the dynamic distance text below the heatmap sweep status
        if (heatmapView.r1 > 0) {
            distanceReadoutText.setText(String.format(Locale.US,
                "N1: %.1f m  ·  N2: %.1f m  ·  N3: %.1f m\nTarget ↔ Router: %.1f m",
                heatmapView.r1, heatmapView.r2, heatmapView.r3, heatmapView.targetRouterDistM));
        } else {
            distanceReadoutText.setText("Awaiting mesh data…");
        }

        updateGraphs(avg);

        // Push live signals to engine
        heatmapView.updateLiveSignals(nodes[0].rssi, nodes[1].rssi, nodes[2].rssi);
    }

    private void updateGraphs(NodeData avg) {
        LineData rssiData = rssiChart.getData();
        LineData latencyData = latencyChart.getData();
        LineData packetData = packetChart.getData();
        int activeNodes = 0;

        for (int i = 0; i < 3; i++) {
            NodeData n = nodes[i];
            if (System.currentTimeMillis() - n.lastUpdate < 5000 && n.lastUpdate != 0) {
                rssiData.addEntry(new Entry(graphIndex, n.rssi), i);
                latencyData.addEntry(new Entry(graphIndex, n.latency), i);
                packetData.addEntry(new Entry(graphIndex, n.loss), i);
                activeNodes++;
            }
        }

        if (activeNodes > 0) {
            rssiData.addEntry(new Entry(graphIndex, avg.rssi), 3);
            latencyData.addEntry(new Entry(graphIndex, avg.latency), 3);
            packetData.addEntry(new Entry(graphIndex, avg.loss), 3);
            graphIndex++;

            rssiData.notifyDataChanged();
            latencyData.notifyDataChanged();
            packetData.notifyDataChanged();
            rssiChart.notifyDataSetChanged();
            latencyChart.notifyDataSetChanged();
            packetChart.notifyDataSetChanged();

            // Only auto-scroll to the newest data if the user hasn't manually panned back to look at history
            boolean autoScroll = rssiChart.getHighestVisibleX() >= (graphIndex - 3);

            if (autoScroll) {
                rssiChart.moveViewToX(graphIndex);
                latencyChart.moveViewToX(graphIndex);
                packetChart.moveViewToX(graphIndex);
            }

            rssiChart.invalidate();
            latencyChart.invalidate();
            packetChart.invalidate();
        }
    }

    private NodeData getAverages() {
        NodeData a = new NodeData();
        int c = 0;
        for (NodeData n : nodes) {
            if (System.currentTimeMillis() - n.lastUpdate < 5000 && n.lastUpdate != 0) {
                a.rssi += n.rssi;
                a.snr += n.snr;
                a.latency += n.latency;
                a.jitter += n.jitter;
                a.loss += n.loss;
                c++;
            }
        }
        if (c > 0) {
            a.rssi /= c;
            a.snr /= c;
            a.latency /= c;
            a.jitter /= c;
            a.loss /= c;
        }
        return a;
    }

    private void setupChart(LineChart chart, float minY, float maxY) {
        chart.setData(new LineData());
        chart.getLegend().setTextColor(Color.WHITE);
        chart.getLegend().setTextSize(11f);
        chart.getDescription().setEnabled(false);
        chart.setDrawGridBackground(false);
        chart.setDrawBorders(false);
        chart.getAxisRight().setEnabled(false);
        chart.getXAxis().setDrawGridLines(false);
        chart.getXAxis().setTextColor(Color.GRAY);
        chart.getAxisLeft().setDrawGridLines(true);
        chart.getAxisLeft().setGridColor(Color.parseColor("#2C3A5A"));
        chart.getAxisLeft().setTextColor(Color.GRAY);
        chart.getAxisLeft().setAxisMinimum(minY);
        chart.getAxisLeft().setAxisMaximum(maxY);
        // ── Zoom & pan ───────────────────────────────────────────────
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);           // finger drag to pan
        chart.setScaleXEnabled(true);         // allow zooming on time (X) axis
        chart.setScaleYEnabled(true);         // allow zooming vertically (Y) axis
        chart.setPinchZoom(false);            // separate X/Y zoom behaviour (allows independent horizontal/vertical stretch)
        chart.setDoubleTapToZoomEnabled(true);// double-tap to zoom in on time
        chart.setHighlightPerDragEnabled(true);
        chart.setBackgroundColor(Color.parseColor("#090F1C"));
        chart.setGridBackgroundColor(Color.parseColor("#141E30"));
    }

    private void initChartData(LineChart chart) {
        LineData data = new LineData();
        int[] colors = {Color.RED, Color.GREEN, Color.YELLOW};
        for (int i = 0; i < 3; i++) {
            LineDataSet set = new LineDataSet(new ArrayList<>(), "Node " + (i + 1));
            set.setColor(colors[i]);
            set.setDrawCircles(false);
            set.setLineWidth(2f);
            data.addDataSet(set);
        }
        LineDataSet avg = new LineDataSet(new ArrayList<>(), "Average");
        avg.setColor(Color.WHITE);
        avg.setDrawCircles(false);
        avg.setLineWidth(3f);
        avg.enableDashedLine(10f, 5f, 0f);
        data.addDataSet(avg);
        chart.setData(data);
    }

    // =========================================================================
    // GPU ACCELERATED MESH ENGINE (SCREEN BLENDING & INTERACTIVITY)
    // =========================================================================

// =========================================================================
    // DROP-IN REPLACEMENT: HeatmapView inner class
    // Replace your entire existing HeatmapView class with this one.
    // =========================================================================

    class HeatmapView extends View {

        // ── Heatmap bitmap grid ──────────────────────────────────────────────
        private android.graphics.Bitmap heatMapBitmap;
        private int[] heatPixels;
        // 100×100 grid → ~2.7× more pixels than the old 60×60, greatly smoother
        private static final int GRID_RES = 100;

        // ── Paints ────────────────────────────────────────────────────────────
        Paint textPaint, linePaint, contourPaint;
        Paint optimalRingPaint, optimalLinePaint;
        Paint legendBorderPaint, legendTextPaint, scalePaint;

        // ── Geometry ─────────────────────────────────────────────────────────
        public float d12 = 1f, d13 = 1f, d23 = 1f, r1 = 0f, r2 = 0f, r3 = 0f;
        public float targetRouterDistM = 0f;

        // ── Live RSSI ────────────────────────────────────────────────────────
        float liveRssi1 = -65f, liveRssi2 = -72f, liveRssi3 = -80f;

        // ── Iso-contour levels ───────────────────────────────────────────
        // dBm thresholds and display colours — exactly matching the palette stops below
        private static final float[] ISO_DBM    = {-60f, -67f, -74f, -80f};
        private static final int[]   ISO_COLORS = {0xFF00D050, 0xFFF5C000, 0xFFE86000, 0xFFCC1010};

        // ── Touch / Pan / Zoom ───────────────────────────────────────────────
        Matrix transformMatrix = new Matrix();
        ScaleGestureDetector scaleDetector;
        GestureDetector gestureDetector;
        boolean isInitialCenterDone = false;

        // ── Animation ticker ─────────────────────────────────────────────────
        private long animStartTime = System.currentTimeMillis();


        // ── Realistic indoor path loss (10 × n, n = 2.7 for 2.4 GHz) ────────
        private static final float PATH_LOSS_EXP = 27.0f;
        // Reference RSSI at 1 m (calibrated to match ESP32 firmware formula)
        private static final float TX_REF_DBM    = -35.0f;

        public HeatmapView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);

            linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            linePaint.setColor(Color.argb(180, 120, 200, 255));
            linePaint.setStrokeWidth(3f);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{18f, 10f}, 0f));

            contourPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            contourPaint.setStyle(Paint.Style.STROKE);

            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(40f);
            textPaint.setFakeBoldText(true);
            textPaint.setShadowLayer(8f, 0f, 2f, Color.BLACK);

            optimalRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            optimalRingPaint.setColor(Color.CYAN);
            optimalRingPaint.setStyle(Paint.Style.STROKE);
            optimalRingPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{12f, 12f}, 0f));

            optimalLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            optimalLinePaint.setColor(Color.argb(140, 0, 220, 255));
            optimalLinePaint.setStyle(Paint.Style.STROKE);
            optimalLinePaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8f, 10f}, 0f));

            legendBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            legendBorderPaint.setStyle(Paint.Style.STROKE);
            legendBorderPaint.setColor(Color.argb(140, 255, 255, 255));
            legendBorderPaint.setStrokeWidth(1.5f);

            legendTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            legendTextPaint.setColor(Color.WHITE);
            legendTextPaint.setTextSize(26f);
            legendTextPaint.setShadowLayer(4f, 0f, 1f, Color.BLACK);

            scalePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            scalePaint.setColor(Color.WHITE);
            scalePaint.setStrokeWidth(3f);
            scalePaint.setStyle(Paint.Style.STROKE);

            setupTouchListeners(context);
        }

        private void setupTouchListeners(Context context) {
            scaleDetector = new ScaleGestureDetector(context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            float sf = detector.getScaleFactor();
                            transformMatrix.postScale(sf, sf, detector.getFocusX(), detector.getFocusY());
                            invalidate();
                            return true;
                        }
                    });

            gestureDetector = new GestureDetector(context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dX, float dY) {
                            transformMatrix.postTranslate(-dX, -dY);
                            invalidate();
                            return true;
                        }
                    });
        }

        private float mRotationAngle = 0f;

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            boolean handled = scaleDetector.onTouchEvent(event);
            handled = gestureDetector.onTouchEvent(event) || handled;

            // Manual 2-finger rotation tracking
            if (event.getPointerCount() == 2) {
                float dx = event.getX(1) - event.getX(0);
                float dy = event.getY(1) - event.getY(0);
                float currentAngle = (float) Math.toDegrees(Math.atan2(dy, dx));

                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    mRotationAngle = currentAngle;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    float angleDiff = currentAngle - mRotationAngle;
                    // Prevent massive jumps if fingers cross axes
                    if (Math.abs(angleDiff) < 45f) {
                        float cx = (event.getX(0) + event.getX(1)) / 2f;
                        float cy = (event.getY(0) + event.getY(1)) / 2f;
                        transformMatrix.postRotate(angleDiff, cx, cy);
                        invalidate();
                    }
                    mRotationAngle = currentAngle;
                }
                handled = true;
            }

            return handled || super.onTouchEvent(event);
        }

        public void updateLiveSignals(float rssi1, float rssi2, float rssi3) {
            this.liveRssi1 = rssi1;
            this.liveRssi2 = rssi2;
            this.liveRssi3 = rssi3;
            invalidate();
        }

        public void updateMeshGeometry(float d12, float d13, float d23, float r1, float r2, float r3) {
            this.d12 = Math.max(0.1f, d12);
            this.d13 = Math.max(0.1f, d13);
            this.d23 = Math.max(0.1f, d23);

            // Physics enforcer – proportional stretch so triangle inequality always holds
            if (this.d12 > this.d13 + this.d23) {
                float s = this.d12 / (this.d13 + this.d23) * 1.01f;
                this.d13 *= s; this.d23 *= s;
            } else if (this.d13 > this.d12 + this.d23) {
                float s = this.d13 / (this.d12 + this.d23) * 1.01f;
                this.d12 *= s; this.d23 *= s;
            } else if (this.d23 > this.d12 + this.d13) {
                float s = this.d23 / (this.d12 + this.d13) * 1.01f;
                this.d12 *= s; this.d13 *= s;
            }

            this.r1 = r1;
            this.r2 = r2;
            this.r3 = r3;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth(), height = getHeight();
            if (width == 0 || height == 0 || (r1 == 0 && r2 == 0 && r3 == 0)) return;

            // Deep-space dark background
            canvas.drawColor(Color.parseColor("#0A0A1A"));

            final float PPM = 150f; // pixels per metre

            // ── 1. Triangle geometry ────────────────────────────────────────
            float sd12 = Math.max(0.1f, d12);
            float sd13 = Math.max(0.1f, d13);
            float sd23 = Math.max(0.1f, d23);

            if (sd12 > sd13 + sd23) { float s = sd12 / (sd13 + sd23) * 1.05f; sd13 *= s; sd23 *= s; }
            else if (sd13 > sd12 + sd23) { float s = sd13 / (sd12 + sd23) * 1.05f; sd12 *= s; sd23 *= s; }
            else if (sd23 > sd12 + sd13) { float s = sd23 / (sd12 + sd13) * 1.05f; sd12 *= s; sd13 *= s; }

            float m_n1x = 0f, m_n1y = 0f;
            float m_n2x = sd12, m_n2y = 0f;
            float m_lx3 = (sd12 * sd12 + sd13 * sd13 - sd23 * sd23) / (2 * sd12);
            float innerVal = sd13 * sd13 - m_lx3 * m_lx3;
            float m_n3y = innerVal > 0 ? (float) Math.sqrt(innerVal) : 0.1f;
            float m_n3x = m_lx3;

            float[] rPos = estimateRouterPosition(m_n1x, m_n1y, m_n2x, m_n2y, m_n3x, m_n3y);

            float n1x = m_n1x * PPM, n1y = m_n1y * PPM;
            float n2x = m_n2x * PPM, n2y = m_n2y * PPM;
            float n3x = m_n3x * PPM, n3y = m_n3y * PPM;
            float rx = rPos[0] * PPM, ry = rPos[1] * PPM;

            // Signal-strength weighted optimal placement point
            float sw1 = signalLinear(liveRssi1);
            float sw2 = signalLinear(liveRssi2);
            float sw3 = signalLinear(liveRssi3);
            float swTotal = sw1 + sw2 + sw3;
            float optX = (n1x * sw1 + n2x * sw2 + n3x * sw3) / swTotal;
            float optY = (n1y * sw1 + n2y * sw2 + n3y * sw3) / swTotal;

            // Expose target-to-router distance (metres) for UI display
            float dxPx = optX - rx, dyPx = optY - ry;
            targetRouterDistM = (float) Math.sqrt(dxPx * dxPx + dyPx * dyPx) / PPM;

            if (!isInitialCenterDone) {
                float cx = (n1x + n2x + n3x) / 3f;
                float cy = (n1y + n2y + n3y) / 3f;
                transformMatrix.postTranslate(width / 2f - cx, height / 2f - cy);
                isInitialCenterDone = true;
            }

            float[] vals = new float[9];
            transformMatrix.getValues(vals);
            float currentScale = vals[Matrix.MSCALE_X];
            if (currentScale < 0.01f) currentScale = 1f;

            double tSec = (System.currentTimeMillis() - animStartTime) / 1000.0;
            float pulse     = (float)(0.5 + 0.5 * Math.sin(tSec * Math.PI));
            float slowPulse = (float)(0.5 + 0.5 * Math.sin(tSec * Math.PI * 0.5));

            canvas.save();
            canvas.concat(transformMatrix);

            // ================================================================
            // 2. ACCURATE RF HEATMAP – Log-distance path loss + IDW warp
            // ================================================================
            float areaM   = 160f;
            float dStartX = -(areaM / 2f) * PPM;
            float dStartY = -(areaM / 2f) * PPM;
            float dWidth  = areaM * PPM;
            float dHeight = areaM * PPM;

            if (heatMapBitmap == null
                    || heatMapBitmap.getWidth() != GRID_RES
                    || heatMapBitmap.getHeight() != GRID_RES) {
                heatMapBitmap = Bitmap.createBitmap(GRID_RES, GRID_RES, Bitmap.Config.ARGB_8888);
                heatPixels = new int[GRID_RES * GRID_RES];
            }

            float stepX = dWidth  / GRID_RES;
            float stepY = dHeight / GRID_RES;

            // Expected RSSI at each sensor node (theoretical, from path-loss model)
            float expN1 = TX_REF_DBM - (PATH_LOSS_EXP * (float) Math.log10(Math.max(0.1f, r1)));
            float expN2 = TX_REF_DBM - (PATH_LOSS_EXP * (float) Math.log10(Math.max(0.1f, r2)));
            float expN3 = TX_REF_DBM - (PATH_LOSS_EXP * (float) Math.log10(Math.max(0.1f, r3)));

            // Environmental warp = actual measured − model prediction
            float warp1 = (liveRssi1 != 0f) ? liveRssi1 - expN1 : 0f;
            float warp2 = (liveRssi2 != 0f) ? liveRssi2 - expN2 : 0f;
            float warp3 = (liveRssi3 != 0f) ? liveRssi3 - expN3 : 0f;

            for (int i = 0; i < heatPixels.length; i++) {
                int gx = i % GRID_RES;
                int gy = i / GRID_RES;

                // Centre of cell in world-pixel coords
                float px = dStartX + (gx + 0.5f) * stepX;
                float py = dStartY + (gy + 0.5f) * stepY;

                // Distance from router in metres
                float distRouter = Math.max(0.5f,
                        (float) Math.sqrt((px - rx) * (px - rx) + (py - ry) * (py - ry)) / PPM);

                // Base RSSI from log-distance path-loss model
                float baseRssi = TX_REF_DBM - (PATH_LOSS_EXP * (float) Math.log10(distRouter));

                // IDW (power 3) environmental correction from measured nodes
                float dd1 = Math.max(0.1f, (float) Math.sqrt((px - n1x) * (px - n1x) + (py - n1y) * (py - n1y)) / PPM);
                float dd2 = Math.max(0.1f, (float) Math.sqrt((px - n2x) * (px - n2x) + (py - n2y) * (py - n2y)) / PPM);
                float dd3 = Math.max(0.1f, (float) Math.sqrt((px - n3x) * (px - n3x) + (py - n3y) * (py - n3y)) / PPM);

                float iw1 = 1f / (dd1 * dd1 * dd1);
                float iw2 = 1f / (dd2 * dd2 * dd2);
                float iw3 = 1f / (dd3 * dd3 * dd3);
                float wTotal = iw1 + iw2 + iw3;

                float envWarp = (warp1 * iw1 + warp2 * iw2 + warp3 * iw3) / wTotal;
                float finalRssi = baseRssi + envWarp;

                heatPixels[i] = getColorForRssi(finalRssi, 215);
            }

            heatMapBitmap.setPixels(heatPixels, 0, GRID_RES, 0, 0, GRID_RES, GRID_RES);

            Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            android.graphics.RectF heatRect =
                    new android.graphics.RectF(dStartX, dStartY, dStartX + dWidth, dStartY + dHeight);
            canvas.drawBitmap(heatMapBitmap, null, heatRect, bmpPaint);

            // (iso-contour dashed rings removed per user request)

            // ================================================================
            // 4. MESH TRIANGLE
            // ================================================================
            linePaint.setStrokeWidth(4f / currentScale);
            Path tri = new Path();
            tri.moveTo(n1x, n1y); tri.lineTo(n2x, n2y);
            tri.lineTo(n3x, n3y); tri.close();
            canvas.drawPath(tri, linePaint);

            // ================================================================
            // 5. ROUTER → OPTIMAL ZONE GUIDE LINE
            // ================================================================
            optimalLinePaint.setStrokeWidth(3f / currentScale);
            canvas.drawLine(rx, ry, optX, optY, optimalLinePaint);

            if (targetRouterDistM > 0.1f) {
                float midX = (rx + optX) / 2f;
                float midY = (ry + optY) / 2f;
                
                float angle = (float) Math.atan2(optY - ry, optX - rx);
                float offX = (float) -Math.sin(angle) * (15f / currentScale);
                float offY = (float) Math.cos(angle) * (15f / currentScale);
                
                Paint distTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                distTextPaint.setColor(Color.WHITE);
                distTextPaint.setTextSize(26f / currentScale);
                distTextPaint.setTextAlign(Paint.Align.CENTER);
                distTextPaint.setShadowLayer(4f / currentScale, 0, 2f / currentScale, Color.parseColor("#77000000"));
                
                String distStr = String.format(Locale.US, "%.1f m", targetRouterDistM);
                
                canvas.save();
                canvas.translate(midX + offX, midY + offY);
                float rotDeg = (float) Math.toDegrees(angle);
                if (rotDeg > 90 || rotDeg < -90) rotDeg += 180;
                canvas.rotate(rotDeg);
                canvas.drawText(distStr, 0, 0, distTextPaint);
                canvas.restore();
            }

            // ================================================================
            // 6. NODES, ROUTER & TARGET MARKER
            // ================================================================
            drawGlowingNode(canvas, n1x, n1y, liveRssi1, "N1", currentScale, pulse);
            drawGlowingNode(canvas, n2x, n2y, liveRssi2, "N2", currentScale, pulse);
            drawGlowingNode(canvas, n3x, n3y, liveRssi3, "N3", currentScale, pulse);
            drawRouter(canvas, rx, ry, currentScale, pulse);
            drawTarget(canvas, optX, optY, currentScale, slowPulse);

            canvas.restore();

            // ── Screen-space overlays (not affected by pan/zoom) ───────────
            drawLegend(canvas, width, height);
            drawScaleBar(canvas, width, height, PPM, currentScale);

            postInvalidateDelayed(32); // ~30 fps animation
        }

        // ── Drawing helpers ───────────────────────────────────────────────────

        private void drawGlowingNode(Canvas canvas, float cx, float cy, float rssi,
                                     String label, float scale, float pulse) {
            int peak = getColorForRssi(rssi, 255);
            int r = Color.red(peak), g = Color.green(peak), b = Color.blue(peak);

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);

            // Outer soft pulse glow
            p.setColor(Color.argb((int)(22 + pulse * 20), r, g, b));
            canvas.drawCircle(cx, cy, 72f / scale, p);
            // Mid glow
            p.setColor(Color.argb(60, r, g, b));
            canvas.drawCircle(cx, cy, 46f / scale, p);
            // Inner glow
            p.setColor(Color.argb(105, r, g, b));
            canvas.drawCircle(cx, cy, 28f / scale, p);
            // Solid white core
            p.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, 14f / scale, p);

            // Coloured signal ring
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setColor(peak);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(4.5f / scale);
            canvas.drawCircle(cx, cy, 23f / scale, ring);

            // Label above node
            textPaint.setTextSize(38f / scale);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(label, cx - 18f / scale, cy - 36f / scale, textPaint);

            // RSSI readout below node in signal colour
            textPaint.setTextSize(26f / scale);
            textPaint.setColor(peak);
            canvas.drawText(
                    String.format(Locale.US, "%.0fdBm", rssi),
                    cx - 38f / scale, cy + 54f / scale, textPaint);
        }

        private void drawRouter(Canvas canvas, float rx, float ry, float scale, float pulse) {
            final int GREEN = Color.parseColor("#00FF96");
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            canvas.drawCircle(rx, ry, 16f / scale, p);

            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setColor(GREEN);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(8f / scale);
            canvas.drawCircle(rx, ry, (26f + pulse * 10f) / scale, ring);

            textPaint.setTextSize(34f / scale);
            textPaint.setColor(GREEN);
            canvas.drawText("ROUTER", rx - 52f / scale, ry - 52f / scale, textPaint);
        }

        private void drawTarget(Canvas canvas, float optX, float optY, float scale, float slowPulse) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.CYAN);
            canvas.drawCircle(optX, optY, 8f / scale, p);

            // Pulsing outer ring
            optimalRingPaint.setStrokeWidth(4f / scale);
            optimalRingPaint.setColor(Color.argb((int)(160 + slowPulse * 80), 0, 230, 255));
            canvas.drawCircle(optX, optY, (28f + slowPulse * 14f) / scale, optimalRingPaint);

            textPaint.setTextSize(30f / scale);
            textPaint.setColor(Color.CYAN);
            canvas.drawText("TARGET", optX - 50f / scale, optY - 46f / scale, textPaint);
        }

        /** Draws a colour-gradient legend bar in the bottom-right corner (screen-space). */
        private void drawLegend(Canvas canvas, int width, int height) {
            float barW  = 22f, barH = 170f;
            float margin = 18f;
            float barX = width  - margin - barW - 68f;
            float barY = height - margin - barH;

            // Semi-transparent panel background
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(Color.argb(170, 8, 8, 24));
            android.graphics.RectF panel =
                    new android.graphics.RectF(barX - 10f, barY - 28f, width - margin + 10f, height - margin + 10f);
            canvas.drawRoundRect(panel, 14f, 14f, bg);
            canvas.drawRoundRect(panel, 14f, 14f, legendBorderPaint);

            // Gradient sampled at exact ISO-contour dBm levels (bottom = worst, top = best)
            int[] gradColors = {
                getColorForRssi(-90f, 255),  // bottom
                getColorForRssi(-80f, 255),
                getColorForRssi(-74f, 255),
                getColorForRssi(-67f, 255),
                getColorForRssi(-60f, 255),
                getColorForRssi(-50f, 255)   // top
            };
            android.graphics.LinearGradient grad = new android.graphics.LinearGradient(
                    barX, barY + barH, barX, barY,
                    gradColors, null, Shader.TileMode.CLAMP);
            Paint gradP = new Paint(Paint.ANTI_ALIAS_FLAG);
            gradP.setShader(grad);
            android.graphics.RectF barRect =
                    new android.graphics.RectF(barX, barY, barX + barW, barY + barH);
            canvas.drawRect(barRect, gradP);
            canvas.drawRect(barRect, legendBorderPaint);

            // Labels at positions proportional to the [-90,-50] dBm range
            // (barY = -50 top, barY+barH = -90 bottom, range = 40 dBm)
            String[] dbmLabels = {"-50", "-60", "-67", "-74", "-80", "-90"};
            float[]  dbmPos    = {0f, 0.25f, 0.425f, 0.60f, 0.75f, 1.0f};
            legendTextPaint.setTextSize(23f);
            for (int i = 0; i < dbmLabels.length; i++) {
                float ly = barY + dbmPos[i] * barH;
                legendTextPaint.setColor(Color.WHITE);
                canvas.drawText(dbmLabels[i], barX + barW + 6f, ly + 8f, legendTextPaint);
            }

            // Title "dBm" above bar
            legendTextPaint.setTextSize(20f);
            legendTextPaint.setColor(Color.argb(200, 200, 200, 255));
            canvas.drawText("dBm", barX, barY - 10f, legendTextPaint);
        }

        /** Draws a metric scale bar in the bottom-left corner (screen-space). */
        private void drawScaleBar(Canvas canvas, int width, int height, float PPM, float currentScale) {
            // Pick a nice round number of metres that fits the screen
            float[] niceMetres = {1f, 2f, 5f, 10f, 20f};
            float targetPx = width * 0.18f;
            float chosenM  = 5f;
            for (float nm : niceMetres) {
                float px = nm * PPM * currentScale;
                if (px <= targetPx) chosenM = nm; else break;
            }
            float barPx = chosenM * PPM * currentScale;

            float margin = 24f;
            float barY = height - margin - 14f;
            float barX = margin;

            // L-shaped scale ticks
            canvas.drawLine(barX, barY - 10f, barX, barY + 10f, scalePaint);
            canvas.drawLine(barX, barY, barX + barPx, barY, scalePaint);
            canvas.drawLine(barX + barPx, barY - 10f, barX + barPx, barY + 10f, scalePaint);

            // Label
            Paint sText = new Paint(Paint.ANTI_ALIAS_FLAG);
            sText.setColor(Color.WHITE);
            sText.setTextSize(26f);
            sText.setShadowLayer(4f, 0, 1f, Color.BLACK);
            String lbl = String.format(Locale.US, "%.0fm", chosenM);
            float tw = sText.measureText(lbl);
            canvas.drawText(lbl, barX + barPx / 2f - tw / 2f, barY - 14f, sText);
        }

        // ── Math utilities ────────────────────────────────────────────────────

        /** Linear signal power for IDW weighting (higher RSSI → more weight). */
        private float signalLinear(float rssi) {
            return Math.max(1e-6f, (float) Math.pow(10.0, rssi / 10.0));
        }

        /**
         * Iterative least-squares trilateration solver.
         * Returns estimated router position {x, y} in metres relative to node 1.
         */
        public float[] estimateRouterPosition(float n1x, float n1y,
                                              float n2x, float n2y,
                                              float n3x, float n3y) {
            // Distances from router using same formula as firmware text readout
            float rr1 = liveRssi1 <= -90f ? 20f : (float) Math.pow(10, (-46.5f - liveRssi1) / 31.9f);
            float rr2 = liveRssi2 <= -90f ? 20f : (float) Math.pow(10, (-46.5f - liveRssi2) / 31.9f);
            float rr3 = liveRssi3 <= -90f ? 20f : (float) Math.pow(10, (-46.5f - liveRssi3) / 31.9f);

            // Start at centroid
            float rx = (n1x + n2x + n3x) / 3f;
            float ry = (n1y + n2y + n3y) / 3f;

            // Gradient descent – 200 iterations with adaptive learning rate
            float lr = 0.06f;
            for (int i = 0; i < 200; i++) {
                float d1 = Math.max(0.01f, (float) Math.sqrt((rx - n1x) * (rx - n1x) + (ry - n1y) * (ry - n1y)));
                float d2 = Math.max(0.01f, (float) Math.sqrt((rx - n2x) * (rx - n2x) + (ry - n2y) * (ry - n2y)));
                float d3 = Math.max(0.01f, (float) Math.sqrt((rx - n3x) * (rx - n3x) + (ry - n3y) * (ry - n3y)));

                float e1 = d1 - rr1, e2 = d2 - rr2, e3 = d3 - rr3;

                float gx = (e1 / d1) * (rx - n1x) + (e2 / d2) * (rx - n2x) + (e3 / d3) * (rx - n3x);
                float gy = (e1 / d1) * (ry - n1y) + (e2 / d2) * (ry - n2y) + (e3 / d3) * (ry - n3y);

                rx -= lr * gx;
                ry -= lr * gy;

                // Reduce step size as we converge for precision
                if (i == 80)  lr = 0.03f;
                if (i == 140) lr = 0.01f;
            }

            return new float[]{rx, ry};
        }

        /**
         * ISO-contour-ALIGNED colormap.
         * Colour transitions occur at EXACTLY the dBm values of the dashed rings:
         *   -80 dBm → RED  |  -74 → ORANGE  |  -67 → AMBER  |  -60 → GREEN
         * This ensures the heatmap green blob fills precisely to the -60 dBm ring,
         * amber fills to the -67 ring, orange to -74, red to -80.
         */
        private int getColorForRssi(float rssi, int alpha) {
            // Non-uniform stops — placed at exact ISO-contour dBm values
            final float[] dBmStops = { -90f,      -80f,      -74f,      -67f,      -60f,      -50f      };
            final int[]   palette  = {
                Color.parseColor("#0A0114"),  // -90  deep void (no signal)
                Color.parseColor("#CC1010"),  // -80  pure red   ← -80 dBm iso ring
                Color.parseColor("#E86000"),  // -74  dark orange ← -74 dBm iso ring
                Color.parseColor("#F5C000"),  // -67  golden amber ← -67 dBm iso ring
                Color.parseColor("#00D050"),  // -60  GREEN ← -60 dBm iso ring (user request)
                Color.parseColor("#00FFBB"),  // -50+ bright cyan-green (excellent)
            };

            // Clamp to valid range
            rssi = Math.max(dBmStops[0], Math.min(rssi, dBmStops[dBmStops.length - 1]));

            // Find which segment this RSSI falls in and linearly interpolate
            for (int i = 0; i < dBmStops.length - 1; i++) {
                if (rssi <= dBmStops[i + 1]) {
                    float t = (rssi - dBmStops[i]) / (dBmStops[i + 1] - dBmStops[i]);
                    int ca = palette[i], cb = palette[i + 1];
                    int r = (int)(Color.red(ca)   + t * (Color.red(cb)   - Color.red(ca)));
                    int g = (int)(Color.green(ca) + t * (Color.green(cb) - Color.green(ca)));
                    int b = (int)(Color.blue(ca)  + t * (Color.blue(cb)  - Color.blue(ca)));
                    return Color.argb(alpha, r, g, b);
                }
            }
            int c = palette[palette.length - 1];
            return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c));
        }
    }
}

