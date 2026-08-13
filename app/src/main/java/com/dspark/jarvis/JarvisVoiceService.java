package com.dspark.jarvis;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.speech.tts.*;
import android.text.TextUtils;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.util.Locale;

/**
 * Persistent JARVIS voice service.
 *
 * Wake mode:
 *   continuously listens offline for "Hey JARVIS".
 *
 * Manual mode:
 *   the on-screen microphone button arms the same offline recognizer for
 *   one command, so the button remains useful even while wake listening is active.
 */
public class JarvisVoiceService extends Service {
    static volatile boolean requestListen=false, requestStop=false, requestManual=false;
    static volatile boolean wakeMode=true;
    static JarvisVoiceService self;

    SpeechService speechService;
    Model model;
    TextToSpeech tts;
    Handler h=new Handler(Looper.getMainLooper());
    boolean listening=false;
    boolean armed=false;
    boolean startupSpoken=false;
    long armedUntil=0L;
    String lastHandled="";
    long lastHandledAt=0L;
    final String CH="jarvis_voice";
    static final String WAKE="hey jarvis";
    static final String STARTUP="Good evening, sir. JARVIS is online and ready.";
    static final String SLEEP="Understood, sir. Entering standby mode.";

    @Override public void onCreate(){
        super.onCreate();
        self=this;
        createChannel();

        Notification n=new Notification.Builder(this,CH)
            .setContentTitle("JARVIS is listening for Hey JARVIS")
            .setContentText("Voice wake is active. Say “Hey JARVIS”.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build();

        if(Build.VERSION.SDK_INT>=29)
            startForeground(7,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else
            startForeground(7,n);

        tts=new TextToSpeech(this,status->{
            if(status==TextToSpeech.SUCCESS){
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(.98f);
            }
        });

        if(hasMicPermission()) initModel();
    }

    boolean hasMicPermission(){
        return Build.VERSION.SDK_INT<23 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
    }

    void initModel(){
        if(model!=null){ startWakeListening(); return; }
        notifyState("LOADING VOICE MODEL");
        // The current Vosk Android model artifact contains model-en-us as assets,
        // so no manual model download is required from the user.
        StorageService.unpack(this,"model-en-us","jarvis_model",
            m->{
                model=m;
                startWakeListening();
                if(!startupSpoken){
                    startupSpoken=true;
                    speakText(STARTUP);
                }
            },
            e->{
                notifyState("VOICE MODEL ERROR");
                speakText("I could not load my voice system, Sir.");
            });
    }

    void startWakeListening(){
        if(!hasMicPermission() || model==null || listening) return;
        try{
            Recognizer recognizer=new Recognizer(model,16000.0f);
            speechService=new SpeechService(recognizer,16000.0f);
            speechService.startListening(new RecognitionListener(){
                @Override public void onPartialResult(String hypothesis){}

                @Override public void onResult(String hypothesis){
                    handleText(extractText(hypothesis));
                }

                @Override public void onFinalResult(String hypothesis){
                    handleText(extractText(hypothesis));
                }

                @Override public void onError(Exception e){
                    listening=false;
                    notifyState("VOICE ERROR");
                    if(wakeMode) scheduleRestart(1200);
                }

                @Override public void onTimeout(){
                    listening=false;
                    if(wakeMode) scheduleRestart(250);
                }
            });
            listening=true;
            notifyState(armed ? "LISTENING FOR COMMAND" : "LISTENING FOR HEY JARVIS");
        }catch(Exception e){
            listening=false;
            notifyState("VOICE ERROR");
            scheduleRestart(2000);
        }
    }

    String extractText(String json){
        try{
            String t=new JSONObject(json==null?"{}":json).optString("text","");
            return t==null?"":t.trim().toLowerCase(Locale.US);
        }catch(Exception e){ return ""; }
    }

    void handleText(String text){
        if(TextUtils.isEmpty(text)) return;
        long now=System.currentTimeMillis();

        if(text.equals(lastHandled) && now-lastHandledAt<1200) return;
        lastHandled=text;
        lastHandledAt=now;

        // A tap on the microphone arms one command without requiring the user
        // to say the wake word.
        if(armed && System.currentTimeMillis()<armedUntil){
            armed=false;
            String command=stripWake(text);
            if(!command.isEmpty()) deliverCommand(command);
            else notifyState("LISTENING FOR COMMAND");
            return;
        }

        armed=false;
        String lower=text.toLowerCase(Locale.US);
        int pos=lower.indexOf(WAKE);

        if(pos>=0){
            String command=text.substring(pos+WAKE.length()).trim();
            if(command.isEmpty()){
                armed=true;
                armedUntil=System.currentTimeMillis()+9000;
                speakText("Yes, Sir?");
                notifyState("AWAITING COMMAND");
            }else{
                deliverCommand(command);
            }
        }
    }

    String stripWake(String text){
        String s=text.trim();
        String l=s.toLowerCase(Locale.US);
        if(l.startsWith(WAKE)) s=s.substring(WAKE.length()).trim();
        return s;
    }

    void deliverCommand(String command){
        notifyState("THINKING");
        if(MainActivity.instance!=null){
            MainActivity.instance.deliver(command);
        }else{
            String q=command.toLowerCase(Locale.US);

            if(q.contains("what time")){
                String t=android.text.format.DateFormat.format(
                    "h:mm a",System.currentTimeMillis()).toString();
                speakText("It is "+t+", Sir.");
            }else if(q.equals("stop listening")){
                wakeMode=false;
                armed=false;
                speakText(SLEEP);
                notifyState("SLEEPING");
            }else if(q.equals("go to sleep") || q.equals("standby")){
                // Standby still keeps the wake-word detector alive, so
                // "Hey JARVIS" can bring the assistant back.
                wakeMode=true;
                armed=false;
                speakText(SLEEP);
                notifyState("STANDBY — SAY HEY JARVIS");
            }else{
                speakText("I heard you, Sir. The JARVIS AI brain needs its secure server connection configured before I can answer that while the screen is closed.");
                notifyState("READY");
            }
        }
    }

    void scheduleRestart(long delay){
        h.removeCallbacksAndMessages(null);
        h.postDelayed(()->{
            if(wakeMode && hasMicPermission()){
                if(speechService!=null){
                    try{speechService.stop();}catch(Exception ignored){}
                }
                listening=false;
                startWakeListening();
            }
        },delay);
    }

    void notifyState(String state){
        if(MainActivity.instance!=null && MainActivity.instance.web!=null){
            MainActivity.instance.runOnUiThread(()->MainActivity.instance.web.evaluateJavascript(
                "window.jarvisNativeState&&window.jarvisNativeState("+quote(state)+")",null));
        }
    }

    String quote(String x){
        return "\""+x.replace("\\","\\\\").replace("\"","\\\"")+"\"";
    }

    static void speakText(String s){
        if(self==null || self.tts==null) return;
        self.h.post(()->{
            try{
                if(self.speechService!=null){
                    self.speechService.stop();
                    self.listening=false;
                }
                self.tts.speak(s,TextToSpeech.QUEUE_FLUSH,null,"jarvis");
                long duration=Math.max(1500,Math.min(7500,700L+s.length()*45L));
                if(self.wakeMode)
                    self.h.postDelayed(()->self.startWakeListening(),duration);
            }catch(Exception ignored){}
        });
    }

    void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager m=getSystemService(NotificationManager.class);
            m.createNotificationChannel(new NotificationChannel(
                CH,"JARVIS Voice",NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent i,int f,int id){
        if(requestStop){
            requestStop=false;
            requestManual=false;
            wakeMode=false;
            armed=false;
            if(speechService!=null){
                try{speechService.stop();}catch(Exception ignored){}
            }
            listening=false;
            notifyState("SLEEPING");
            return START_STICKY;
        }

        if(i!=null && i.getBooleanExtra("wake",false)) wakeMode=true;

        if(requestManual){
            requestManual=false;
            wakeMode=true;
            armed=true;
            armedUntil=System.currentTimeMillis()+12000;
            if(!listening && model!=null) startWakeListening();
            else notifyState("LISTENING FOR COMMAND");
        }

        if(requestListen){
            requestListen=false;
            wakeMode=true;
        }

        if(wakeMode && hasMicPermission() && model==null) initModel();
        else if(wakeMode && hasMicPermission() && !listening) startWakeListening();

        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent){
        if(wakeMode) scheduleRestart(1000);
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy(){
        h.removeCallbacksAndMessages(null);
        if(speechService!=null){
            try{speechService.stop();}catch(Exception ignored){}
        }
        if(model!=null){
            try{model.close();}catch(Exception ignored){}
        }
        if(tts!=null)tts.shutdown();
        self=null;
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent i){return null;}
}
