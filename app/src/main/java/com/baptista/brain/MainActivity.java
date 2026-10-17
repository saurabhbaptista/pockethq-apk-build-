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

public final class MainActivity extends Activity {
  private static final int EXPORT=101, IMPORT=102;
  private static final int MAX_BYTES=3000000;
  private WebView web;
  private String pendingExport;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(Color.rgb(8,15,29));
    getWindow().setNavigationBarColor(Color.rgb(8,15,29));
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
        Uri url=request.getUrl();
        return !"file".equals(url.getScheme());
      }
    });
    web.addJavascriptInterface(new BackupBridge(),"AndroidBridge");
    setContentView(web);
    web.loadUrl("file:///android_asset/index.html");
  }
  public final class BackupBridge {
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
    if(web!=null){web.removeJavascriptInterface("AndroidBridge");web.destroy();web=null;}
    super.onDestroy();
  }
}