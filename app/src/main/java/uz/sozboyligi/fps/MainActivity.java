package uz.sozboyligi.fps;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import java.net.*;
import java.util.*;

public class MainActivity extends Activity {

    private WebView webView;
    private WsServer wsServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Start WebSocket server
        wsServer = new WsServer();
        wsServer.setDaemon(true);
        wsServer.start();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);

        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/game.html");
    }

    class Bridge {
        @JavascriptInterface
        public String getLocalIp() {
            try {
                Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
                while (ifaces.hasMoreElements()) {
                    NetworkInterface ni = ifaces.nextElement();
                    if (ni.isLoopback() || !ni.isUp()) continue;
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        if (a instanceof Inet4Address) return a.getHostAddress();
                    }
                }
            } catch (Exception e) {}
            return "192.168.43.1";
        }

        @JavascriptInterface
        public int getServerPort() { return 8765; }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (wsServer != null) wsServer.stopServer();
        webView.destroy();
        super.onDestroy();
    }
}
