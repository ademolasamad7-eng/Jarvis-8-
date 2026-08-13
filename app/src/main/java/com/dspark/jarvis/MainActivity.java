package com.dspark.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.webkit.*;
import android.widget.Toast;
import android.net.Uri;
import android.provider.Settings;
import android.os.PowerManager;

public class MainActivity extends Activity {
    WebView web;
    static MainActivity instance;

    // IMPORTANT: replace this with the HTTPS address of your own JARVIS server.
    // Do not put an OpenAI API key inside the Android app.
    // Optional cloud brain endpoint. Configure this in the app later; voice wake works offline.
    final String SERVER = "";

    static final int REQ_PERMISSIONS = 20;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        instance=this;

        web=new WebView(this);
        web.setBackgroundColor(0xff05070d);
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(),"AndroidJarvis");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        requestNeededPermissions();
    }

    void requestNeededPermissions(){
        if(Build.VERSION.SDK_INT>=23 &&
           checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMISSIONS);
        } else {
            startVoiceService();
            requestBatteryOptimizationExemption();
        }
        // POST_NOTIFICATIONS is optional for the voice feature. Request it separately
        // so denying notifications does not prevent microphone use.
        if(Build.VERSION.SDK_INT>=33 &&
           checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 21);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode==REQ_PERMISSIONS){
            if(grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED){
                startVoiceService();
                requestBatteryOptimizationExemption();
                if(web!=null) web.evaluateJavascript("window.jarvisPermissionGranted&&window.jarvisPermissionGranted()", null);
            } else {
                if(web!=null) web.evaluateJavascript("window.jarvisPermissionDenied&&window.jarvisPermissionDenied()", null);
                Toast.makeText(this,"JARVIS needs microphone permission for voice input.",Toast.LENGTH_LONG).show();
            }
        }
    }

    void requestBatteryOptimizationExemption(){
        if(Build.VERSION.SDK_INT>=23){
            try{
                PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
                if(!pm.isIgnoringBatteryOptimizations(getPackageName())){
                    Intent i=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:"+getPackageName()));
                    startActivity(i);
                }
            }catch(Exception ignored){}
        }
    }

    boolean hasMicPermission(){
        return Build.VERSION.SDK_INT<23 ||
               checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
    }

    void startVoiceService(){
        if(!hasMicPermission()) return;
        try{
            Intent i=new Intent(this,JarvisVoiceService.class);
            if(Build.VERSION.SDK_INT>=26) startForegroundService(i);
            else startService(i);
        }catch(Exception e){
            if(web!=null) web.evaluateJavascript(
                "window.jarvisNativeError&&window.jarvisNativeError("+JSONObjectQuote(e.getMessage()==null?"Voice service could not start":e.getMessage())+")", null);
        }
    }

    public void deliver(String text){
        if(web==null)return;
        runOnUiThread(()->web.evaluateJavascript(
            "window.jarvisNativeTranscript("+JSONObjectQuote(text)+")",null));
    }

    String JSONObjectQuote(String x){
        if(x==null)x="";
        return "\""+x.replace("\\","\\\\").replace("\"","\\\"")
                .replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";
    }

    @Override protected void onDestroy(){
        instance=null;
        super.onDestroy();
    }

    public class Bridge {
        @JavascriptInterface public void startListening(){
            if(!hasMicPermission()){
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMISSIONS);
                return;
            }
            JarvisVoiceService.requestListen=true;
            JarvisVoiceService.requestManual=true;
            JarvisVoiceService.wakeMode=true;
            startVoiceService();
        }

        @JavascriptInterface public void stopListening(){
            JarvisVoiceService.requestStop=true;
        }

        @JavascriptInterface public void speak(String text){
            JarvisVoiceService.speakText(text);
        }

        @JavascriptInterface public String getServer(){
            return SERVER;
        }

        @JavascriptInterface public boolean hasMicrophonePermission(){
            return hasMicPermission();
        }
    }
}
