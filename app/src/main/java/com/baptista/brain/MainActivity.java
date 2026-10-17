package com.baptista.brain;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.net.URL;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.Iterator;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.SharedPreferences;
import java.util.regex.Pattern;
import org.json.JSONException;


public final class MainActivity extends Activity {
  private static final int EXPORT=101, IMPORT=102;
  private static final int MAX_BYTES=3000000;
  private WebView web;
  private String pendingExport;
  private FirebaseLive familyLive;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(Color.rgb(8,15,29));
    getWindow().setNavigationBarColor(Color.rgb(8,15,29));
    familyLive=new FirebaseLive();
    web=new WebView(this);
    web.setBackgroundColor(Color.rgb(8,15,29));
    WebSettings settings=web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(true);
    settings.setAllowContentAccess(false);
    settings.setAllowFileAccessFromFileURLs(false);
    settings.setAllowUniversalAccessFromFileURLs(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setMediaPlaybackRequiresUserGesture(true);
    web.setWebChromeClient(new WebChromeClient());
    web.setWebViewClient(new WebViewClient(){
      @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
        return !"file:///android_asset/index.html".equals(request.getUrl().toString());
      }
    });
    web.addJavascriptInterface(new BackupBridge(),"AndroidBridge");
    setContentView(web);
    web.loadUrl("file:///android_asset/index.html");
  }
  public final class BackupBridge {
    @JavascriptInterface public String getLegacySave(){return "";} // The file:// storage origin is retained across all versions.
    @JavascriptInterface public void configureLive(String config){familyLive.configure(config);}
    @JavascriptInterface public void uploadFamilyEvents(String payload){familyLive.upload(payload);}
    @JavascriptInterface public void shareText(String content){
      if(content==null||content.length()>1500)return;
      runOnUiThread(()->{Intent send=new Intent(Intent.ACTION_SEND);send.setType("text/plain");send.putExtra(Intent.EXTRA_TEXT,content);startActivity(Intent.createChooser(send,"Share family invitation"));});
    }
    @JavascriptInterface public void exportJson(String json){
      if(json==null||json.length()>MAX_BYTES)return;
      runOnUiThread(()->{
        pendingExport=json;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE,"BaptistaBrain_Family_Backup.json");
        startActivityForResult(i,EXPORT);
      });
    }
    @JavascriptInterface public void pickJson(){
      runOnUiThread(()->{
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,IMPORT);
      });
    }
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(result!=RESULT_OK||data==null||data.getData()==null)return;
    try{
      if(request==EXPORT&&pendingExport!=null){
        try(OutputStream out=getContentResolver().openOutputStream(data.getData())){
          if(out==null)throw new Exception("No writable output");
          out.write(pendingExport.getBytes(StandardCharsets.UTF_8));
        }
        pendingExport=null;
      }else if(request==IMPORT){
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=getContentResolver().openInputStream(data.getData())){
          if(in==null)throw new Exception("No readable input");
          byte[] chunk=new byte[8192];int count;
          while((count=in.read(chunk))!=-1){
            if(out.size()+count>MAX_BYTES)throw new Exception("Backup exceeds 3MB");
            out.write(chunk,0,count);
          }
        }
        String json=new String(out.toByteArray(),StandardCharsets.UTF_8);
        web.evaluateJavascript("window.receiveImportedBackup("+JSONObject.quote(json)+")",null);
      }
    }catch(Exception e){
      web.evaluateJavascript("window.receiveImportedBackup('{}')",null);
    }
  }
  @Override public void onBackPressed(){
    web.evaluateJavascript("(function(){var a=document.getElementById('game');if(a&&!a.classList.contains('hidden'))document.getElementById('backHome').click();else document.getElementById('goHome').click();})()",null);
  }
  @Override protected void onDestroy(){
    familyLive.stop();
    if(web!=null){web.removeJavascriptInterface("AndroidBridge");web.destroy();web=null;}
    super.onDestroy();
  }

  /** Bounded Firebase REST/SSE transport. Requires project+rules provided by the family. */
  private final class FirebaseLive {
    private final ExecutorService streamWorker=Executors.newSingleThreadExecutor();
    private final ExecutorService writeWorker=Executors.newSingleThreadExecutor();
    private final HashSet<String> uploaded=new HashSet<>();
    private volatile String apiKey="",database="",room="",idToken="",refreshToken="";
    private volatile long expiryMs=0;
    private volatile boolean stopped=false;
    private volatile int generation=0;
    private volatile HttpURLConnection streamConnection;
    private final SharedPreferences prefs=getSharedPreferences("baptista_brain_family_live_auth",MODE_PRIVATE);
    private final Pattern roomPattern=Pattern.compile("^[A-Za-z0-9_-]{30,80}$");
    private final Pattern eventPattern=Pattern.compile("^[A-Za-z0-9_-]{6,120}$");

    void configure(String config){
      if(config==null||config.length()>2000)return;
      try{
        JSONObject obj=new JSONObject(config);
        String key=obj.optString("apiKey").trim(),db=obj.optString("databaseURL").trim(),r=obj.optString("roomId").trim();
        if(!key.matches("[A-Za-z0-9_-]{15,180}")||!roomPattern.matcher(r).matches())throw new IOException("Invalid key or room code");
        URL parsed=new URL(db);
        String host=parsed.getHost().toLowerCase(java.util.Locale.ROOT);
        if(!"https".equals(parsed.getProtocol())||parsed.getPort()!=-1||!parsed.getPath().matches("/?")||parsed.getQuery()!=null||parsed.getRef()!=null||
           !(host.matches("[a-z0-9-]+\\.firebaseio\\.com")||host.matches("[a-z0-9-]+\\.firebasedatabase\\.app")))throw new IOException("Invalid Firebase database URL");
        boolean changed=!key.equals(apiKey)||!db.equals(database)||!r.equals(room);
        if(changed){generation++;uploaded.clear();HttpURLConnection c=streamConnection;if(c!=null)c.disconnect();
          String scope=key+"|"+db;
          if(!scope.equals(prefs.getString("authScope",""))){prefs.edit().remove("refresh").putString("authScope",scope).apply();}
          refreshToken=prefs.getString("refresh","");idToken="";expiryMs=0;
        }
        apiKey=key;database=db.replaceAll("/+$","");room=r;stopped=false;
        final int gen=generation;
        notifyState("connecting","");
        streamWorker.execute(()->streamLoop(gen));
      }catch(Exception e){notifyState("error","Check Firebase API key and database URL");}
    }
    void stop(){stopped=true;generation++;HttpURLConnection c=streamConnection;if(c!=null)c.disconnect();streamWorker.shutdownNow();writeWorker.shutdownNow();}
    void notifyState(String state,String reason){evaluate("window.BaptistaSync&&window.BaptistaSync.nativeStatus("+JSONObject.quote(state)+","+JSONObject.quote(reason)+")");}
    void evaluate(String js){runOnUiThread(()->{if(web!=null)web.evaluateJavascript(js,null);});}
    boolean alive(int gen){return !stopped&&gen==generation;}
    String authUrl(String path)throws Exception{return database+"/families/"+room+(path.isEmpty()?"":"/"+path)+".json?auth="+URLEncoder.encode(idToken,"UTF-8");}
    synchronized void ensureToken()throws Exception{
      if(idToken.length()>100&&expiryMs>System.currentTimeMillis()+90000)return;
      if(!refreshToken.isEmpty()){
        try{
          JSONObject x=requestJson("https://securetoken.googleapis.com/v1/token?key="+apiKey,
            "grant_type=refresh_token&refresh_token="+URLEncoder.encode(refreshToken,"UTF-8"),"application/x-www-form-urlencoded");
          idToken=x.getString("id_token");refreshToken=x.getString("refresh_token");
          expiryMs=System.currentTimeMillis()+Long.parseLong(x.optString("expires_in","3600"))*1000L;
          prefs.edit().putString("refresh",refreshToken).apply();return;
        }catch(Exception e){refreshToken="";prefs.edit().remove("refresh").apply();}
      }
      JSONObject x=requestJson("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key="+apiKey,"{\"returnSecureToken\":true}","application/json");
      idToken=x.getString("idToken");refreshToken=x.getString("refreshToken");expiryMs=System.currentTimeMillis()+3600*1000L;
      prefs.edit().putString("refresh",refreshToken).apply();
    }
    JSONObject requestJson(String endpoint,String body,String contentType)throws Exception{
      HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();try{
        c.setConnectTimeout(12000);c.setReadTimeout(16000);c.setRequestMethod("POST");c.setRequestProperty("Content-Type",contentType);c.setDoOutput(true);
        try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}
        int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("Firebase authentication HTTP "+code);
        try(InputStream in=c.getInputStream()){return new JSONObject(readLimit(in,50000));}
      }finally{c.disconnect();}
    }
    String readLimit(InputStream in,int limit)throws IOException{
      ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
      while((n=in.read(buf))!=-1){if(out.size()+n>limit)throw new IOException("Response size limit");out.write(buf,0,n);}return out.toString("UTF-8");
    }
    void streamLoop(int gen){
      while(alive(gen)){
        HttpURLConnection c=null;
        try{
          ensureToken();if(!alive(gen))return;
          c=(HttpURLConnection)new URL(authUrl("")).openConnection();streamConnection=c;
          c.setConnectTimeout(12000);c.setReadTimeout(85000);c.setInstanceFollowRedirects(true);
          c.setRequestProperty("Accept","text/event-stream");c.setRequestProperty("Cache-Control","no-cache");
          int code=c.getResponseCode();if(code!=200)throw new IOException("Realtime Database HTTP "+code);
          notifyState("live","");
          try(BufferedReader in=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
            String line,type="",data="";
            while(alive(gen)&&(line=in.readLine())!=null){
              if(line.startsWith("event:"))type=line.substring(6).trim();
              else if(line.startsWith("data:")){if(data.length()<2000000)data+=line.substring(5).trim();}
              else if(line.isEmpty()){
                if(("put".equals(type)||"patch".equals(type))&&data.length()<2000000&&data.startsWith("{")){
                  final String json=data;evaluate("window.BaptistaSync&&window.BaptistaSync.nativeEvent("+JSONObject.quote(json)+")");
                }else if("auth_revoked".equals(type)){idToken="";expiryMs=0;}
                type="";data="";
              }
            }
          }
        }catch(Exception e){if(alive(gen))notifyState("error",e.getMessage()==null?"Firebase unreachable":e.getMessage());}
        finally{if(c!=null)c.disconnect();streamConnection=null;}
        if(!alive(gen))return;
        try{Thread.sleep(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
      }
    }
    void upload(String json){
      if(json==null||json.length()>2700000||database.isEmpty()||room.isEmpty())return;
      final int gen=generation;
      writeWorker.execute(()->{
        try{
          ensureToken();if(!alive(gen))return;
          JSONObject obj=new JSONObject(json);
          for(String section:new String[]{"events","matches"}){
            JSONObject records=obj.optJSONObject(section);if(records==null)continue;
            Iterator<String> keys=records.keys();
            while(keys.hasNext()&&alive(gen)){
              String k=keys.next(),identity=section+":"+k;
              if(!eventPattern.matcher(k).matches()||uploaded.contains(identity))continue;
              JSONObject record=records.optJSONObject(k);if(record==null)continue;
              byte[] bytes=record.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>7000)continue;
              HttpURLConnection c=(HttpURLConnection)new URL(authUrl(section+"/"+k)).openConnection();
              try{
                c.setConnectTimeout(12000);c.setReadTimeout(16000);c.setRequestMethod("PUT");
                c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("If-Match","null_etag");c.setDoOutput(true);
                try(OutputStream out=c.getOutputStream()){out.write(bytes);}
                int status=c.getResponseCode();if(status!=200&&status!=201&&status!=204&&status!=412)throw new IOException("Event sync HTTP "+status);
                uploaded.add(identity);
              }finally{c.disconnect();}
            }
          }
        }catch(Exception e){if(alive(gen))notifyState("error",e.getMessage()==null?"Upload failed":e.getMessage());}
      });
    }
  }
}
