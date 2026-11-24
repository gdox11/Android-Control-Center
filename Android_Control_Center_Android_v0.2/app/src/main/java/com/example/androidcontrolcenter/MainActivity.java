package com.example.androidcontrolcenter;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.BatteryManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PORT = 8988;
    private static final String SERVICE_NAME = "AndroidControlCenter";
    private static final UUID APP_UUID = UUID.fromString("8b7a2e10-7c32-4d89-a2e1-2c5d0f6a9b31");
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private EditText ipField, codeField, macField;
    private TextView status;
    private String pairingCode;
    private volatile boolean running = true;
    private BluetoothAdapter bluetooth;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bluetooth = BluetoothAdapter.getDefaultAdapter();
        buildUi();
        requestBluetoothPermissionIfNeeded();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 36, 28, 28);
        root.setBackgroundColor(0xff0c162a);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("ANDROID CONTROL CENTER 0.2");
        title.setTextColor(0xff46dcd2);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView intro = new TextView(this);
        intro.setText("\\nهمین برنامه را روی هر دو گوشی نصب کن. گوشی میزبان باید خودش اتصال را شروع کند و کد جفت‌سازی را به گوشی کنترل‌کننده بدهد. این نسخه فقط اطلاعات پایه دستگاه و باتری را می‌خواند.");
        intro.setTextColor(0xffffffff);
        intro.setTextSize(15);
        root.addView(intro);

        addHeader(root, "۱) گوشی میزبان — شروع اتصال");
        Button wifiHost = button("شروع سرور Wi‑Fi / هات‌اسپات");
        root.addView(wifiHost);
        wifiHost.setOnClickListener(v -> startWifiServer());

        Button btHost = button("شروع سرور Bluetooth");
        root.addView(btHost);
        btHost.setOnClickListener(v -> startBluetoothServer());

        addHeader(root, "۲) گوشی کنترل‌کننده — اتصال");
        ipField = edit("IP گوشی میزبان (مثلاً 192.168.43.1)");
        root.addView(ipField);
        codeField = edit("کد جفت‌سازی ۶ رقمی");
        root.addView(codeField);
        Button wifiConnect = button("اتصال از طریق Wi‑Fi");
        root.addView(wifiConnect);
        wifiConnect.setOnClickListener(v -> connectWifi());

        macField = edit("آدرس Bluetooth گوشی میزبان (MAC)");
        root.addView(macField);
        Button btConnect = button("اتصال از طریق Bluetooth");
        root.addView(btConnect);
        btConnect.setOnClickListener(v -> connectBluetooth());

        addHeader(root, "وضعیت");
        status = new TextView(this);
        status.setText("آماده");
        status.setTextColor(0xffb8c7d9);
        status.setTextSize(14);
        root.addView(status);

        TextView note = new TextView(this);
        note.setText("\\nنکته: برای Wi‑Fi هر دو گوشی باید روی یک شبکه یا هات‌اسپات باشند. برای Bluetooth ابتدا گوشی‌ها را از تنظیمات اندروید Pair کن. این نسخه دوربین، میکروفون، پیامک یا کنترل مخفیانه ارائه نمی‌کند.");
        note.setTextColor(0xffaab7c8);
        note.setTextSize(12);
        root.addView(note);
        setContentView(scroll);
    }

    private void addHeader(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText("\\n" + text);
        t.setTextColor(0xffffd166);
        t.setTextSize(17);
        t.setPadding(0, 16, 0, 8);
        parent.addView(t);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setHintTextColor(0xff8b9bb0);
        e.setTextColor(0xffffffff);
        e.setPadding(12, 8, 12, 8);
        return e;
    }

    private void setStatus(String text) {
        runOnUiThread(() -> status.setText(text));
    }

    private void requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 71);
        }
    }

    private String newCode() {
        return String.format(Locale.US, "%06d", new Random().nextInt(1000000));
    }

    private String localIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            return Formatter.formatIpAddress(ip);
        } catch (Exception e) { return "IP نامشخص"; }
    }

    private void startWifiServer() {
        pairingCode = newCode();
        setStatus("در انتظار اتصال Wi‑Fi\\nIP: " + localIp() + ":" + PORT + "\\nکد جفت‌سازی: " + pairingCode);
        worker.execute(() -> {
            try (ServerSocket server = new ServerSocket(PORT)) {
                while (running) {
                    Socket socket = server.accept();
                    worker.execute(() -> handleClient(socket, pairingCode));
                }
            } catch (Exception e) { setStatus("خطای سرور Wi‑Fi: " + e.getMessage()); }
        });
    }

    private void startBluetoothServer() {
        if (bluetooth == null) { setStatus("این گوشی Bluetooth ندارد."); return; }
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestBluetoothPermissionIfNeeded();
            setStatus("مجوز Bluetooth را بده و دوباره تلاش کن.");
            return;
        }
        pairingCode = newCode();
        setStatus("در انتظار اتصال Bluetooth\\nگوشی‌ها را ابتدا Pair کن.\\nکد جفت‌سازی: " + pairingCode);
        worker.execute(() -> {
            try (BluetoothServerSocket server = bluetooth.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)) {
                while (running) {
                    BluetoothSocket socket = server.accept();
                    worker.execute(() -> handleBluetoothClient(socket, pairingCode));
                }
            } catch (SecurityException e) { setStatus("مجوز Bluetooth لازم است."); }
            catch (Exception e) { setStatus("خطای سرور Bluetooth: " + e.getMessage()); }
        });
    }

    private void handleClient(Socket socket, String expectedCode) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true)) {
            String code = in.readLine();
            if (expectedCode == null || !expectedCode.equals(code)) { out.println("DENIED"); return; }
            out.println("OK");
            String command = in.readLine();
            out.println(responseFor(command));
        } catch (Exception e) { setStatus("ارتباط Wi‑Fi قطع شد: " + e.getMessage()); }
    }

    private void handleBluetoothClient(BluetoothSocket socket, String expectedCode) {
        try (BluetoothSocket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true)) {
            String code = in.readLine();
            if (expectedCode == null || !expectedCode.equals(code)) { out.println("DENIED"); return; }
            out.println("OK");
            String command = in.readLine();
            out.println(responseFor(command));
        } catch (Exception e) { setStatus("ارتباط Bluetooth قطع شد: " + e.getMessage()); }
    }

    private String responseFor(String command) {
        if ("INFO".equals(command)) {
            return "INFO|" + clean(Build.MANUFACTURER) + "|" + clean(Build.MODEL) + "|" + clean(Build.VERSION.RELEASE);
        }
        if ("BATTERY".equals(command)) {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return "BATTERY|" + level;
        }
        return "ERROR|فرمان پشتیبانی نمی‌شود";
    }

    private String clean(String s) { return s == null ? "" : s.replace("|", " ").replace("\\n", " "); }

    private void connectWifi() {
        final String host = ipField.getText().toString().trim();
        final String code = codeField.getText().toString().trim();
        if (host.isEmpty() || code.length() != 6) { setStatus("IP و کد ۶ رقمی را وارد کن."); return; }
        worker.execute(() -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, PORT), 7000);
                String result = exchange(s, code, "INFO");
                setStatus("پاسخ گوشی میزبان:\\n" + result);
            } catch (Exception e) { setStatus("اتصال Wi‑Fi ناموفق: " + e.getMessage()); }
        });
    }

    private void connectBluetooth() {
        if (bluetooth == null) { setStatus("این گوشی Bluetooth ندارد."); return; }
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestBluetoothPermissionIfNeeded();
            setStatus("مجوز Bluetooth را بده و دوباره تلاش کن.");
            return;
        }
        final String mac = macField.getText().toString().trim();
        final String code = codeField.getText().toString().trim();
        if (mac.isEmpty() || code.length() != 6) { setStatus("آدرس MAC و کد ۶ رقمی را وارد کن."); return; }
        worker.execute(() -> {
            try {
                BluetoothDevice device = bluetooth.getRemoteDevice(mac);
                try (BluetoothSocket socket = device.createRfcommSocketToServiceRecord(APP_UUID)) {
                    socket.connect();
                    String result = exchange(socket, code, "INFO");
                    setStatus("پاسخ گوشی میزبان از Bluetooth:\\n" + result);
                }
            } catch (SecurityException e) { setStatus("مجوز Bluetooth را فعال کن."); }
            catch (Exception e) { setStatus("اتصال Bluetooth ناموفق: " + e.getMessage()); }
        });
    }

    private String exchange(Socket socket, String code, String command) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        out.println(code);
        String auth = in.readLine();
        if (!"OK".equals(auth)) throw new IOException("کد جفت‌سازی پذیرفته نشد.");
        out.println(command);
        return in.readLine();
    }

    private String exchange(BluetoothSocket socket, String code, String command) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        out.println(code);
        String auth = in.readLine();
        if (!"OK".equals(auth)) throw new IOException("کد جفت‌سازی پذیرفته نشد.");
        out.println(command);
        return in.readLine();
    }

    @Override protected void onDestroy() {
        running = false;
        worker.shutdownNow();
        super.onDestroy();
    }
}
