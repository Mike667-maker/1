package com.mosaiccinema.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.media.*;
import android.net.Uri;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
  final Handler ui=new Handler(Looper.getMainLooper());
  TextureView screen; MediaPlayer player; Surface surface;
  LinearLayout panel; TextView status; EditText ip;
  boolean host=false,ready=false,playing=false,joined=false;
  int cols=2,rows=1,tile=0,version=0; float zoom=1,dx=0,dy=0;
  int videoW=1920,videoH=1080; String sourceUrl="",hostIp="";
  Uri movie; ServerSocket server; ExecutorService workers=Executors.newCachedThreadPool();
  volatile boolean active=true;
  long offset=0; int errors=0;
  ScaleGestureDetector gesture; float lastX,lastY;
  long hostBase=0,hostTime=System.currentTimeMillis();
  @Override public void onCreate(Bundle b){
    super.onCreate(b); getWindow().addFlags(128);
    FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(0xff060b14);
    screen=new TextureView(this);screen.setSurfaceTextureListener(this);frame.addView(screen,new FrameLayout.LayoutParams(-1,-1));
    gesture=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
      @Override public boolean onScale(ScaleGestureDetector d){zoom=Math.max(.3f,Math.min(5f,zoom*d.getScaleFactor()));transform();return true;}
    });
    screen.setOnTouchListener((v,e)->{
      gesture.onTouchEvent(e);
      if(e.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();return true;}
      if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&e.getPointerCount()==1&&!gesture.isInProgress()){dx+=e.getX()-lastX;dy+=e.getY()-lastY;transform();}
      lastX=e.getX();lastY=e.getY();return true;
    });
    panel=new LinearLayout(this);panel.setOrientation(1);panel.setPadding(8,8,8,8);panel.setBackgroundColor(0xe509162b);
    ScrollView scroll=new ScrollView(this);scroll.addView(panel);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);frame.addView(scroll,lp);
    status=label("MOSAIC CINEMA • 1 telefon = 1 deo slike");panel.addView(status);
    LinearLayout a=line();add(a,"HOST / IZABERI FILM",()->pick());
    ip=new EditText(this);ip.setSingleLine();ip.setHint("IP hosta");ip.setTextColor(-1);ip.setHintTextColor(0xffb0bdce);ip.setTextSize(13);a.addView(ip,new LinearLayout.LayoutParams(0,50,1));add(a,"JOIN",()->join());
    LinearLayout b1=line();add(b1,"Kolone −",()->grid(-1,0));add(b1,"Kolone +",()->grid(1,0));add(b1,"Redovi −",()->grid(0,-1));add(b1,"Redovi +",()->grid(0,1));
    LinearLayout b2=line();add(b2,"◀ DEO",()->{tile=Math.max(0,tile-1);transform();update();});add(b2,"DEO ▶",()->{tile=Math.min(cols*rows-1,tile+1);transform();update();});add(b2,"− ZOOM",()->{zoom/=1.05f;transform();});add(b2,"+ ZOOM",()->{zoom*=1.05f;transform();});
    LinearLayout b3=line();add(b3,"◀",()->{dx-=3;transform();});add(b3,"▲",()->{dy-=3;transform();});add(b3,"▼",()->{dy+=3;transform();});add(b3,"▶",()->{dx+=3;transform();});add(b3,"RESET",()->{zoom=1;dx=dy=0;transform();});
    LinearLayout b4=line();add(b4,"PLAY",()->control(true));add(b4,"PAUSE",()->control(false));add(b4,"−10s",()->seek(-10000));add(b4,"+10s",()->seek(10000));add(b4,"HIDE UI",()->scroll.setVisibility(View.GONE));
    Button restore=new Button(this);restore.setText("SHOW UI");FrameLayout.LayoutParams btn=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);frame.addView(restore,btn);restore.setOnClickListener(v->scroll.setVisibility(View.VISIBLE));
    setContentView(frame);ui.postDelayed(this::tick,350);
  }
  LinearLayout line(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);panel.addView(l);return l;}
  TextView label(String s){TextView t=new TextView(this);t.setText(s);t.setTextColor(-1);t.setTextSize(14);return t;}
  void add(LinearLayout row,String title,Runnable fn){Button b=new Button(this);b.setText(title);b.setTextSize(10);b.setAllCaps(false);row.addView(b,new LinearLayout.LayoutParams(0,46,1));b.setOnClickListener(v->fn.run());}
  void update(){status.setText((host?"HOST ":joined?"JOINED ":"MOSAIC CINEMA ")+" | "+cols+"×"+rows+" | deo "+(tile+1)+"/"+(cols*rows)+" | zoom "+String.format(java.util.Locale.US,"%.2f",zoom));}
  void grid(int x,int y){cols=Math.max(1,Math.min(6,cols+x));rows=Math.max(1,Math.min(6,rows+y));tile=Math.min(tile,cols*rows-1);transform();update();}
  void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("video/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,7);}
  @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(req!=7||res!=RESULT_OK||data==null)return;
    movie=data.getData();try{getContentResolver().takePersistableUriPermission(movie,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
    host=true;joined=false;hostTime=System.currentTimeMillis();hostBase=0;playing=false;version++;
    if(server==null)startServer();
    sourceUrl="http://127.0.0.1:8765/video";load(sourceUrl);
    update();String localIp=getIp();Toast.makeText(this,"HOST IP: "+localIp+" : 8765",Toast.LENGTH_LONG).show();
    status.setText("HOST "+localIp+" | drugi telefoni upisuju ovu IP adresu");
  }
  String getIp(){try{for(Enumeration<NetworkInterface> e=NetworkInterface.getNetworkInterfaces();e.hasMoreElements();){NetworkInterface n=e.nextElement();for(Enumeration<InetAddress> a=n.getInetAddresses();a.hasMoreElements();){InetAddress i=a.nextElement();if(i instanceof Inet4Address&&!i.isLoopbackAddress()&&i.isSiteLocalAddress())return i.getHostAddress();}}}catch(Exception ignored){}return "IP nije pronađen";}
  void startServer(){workers.execute(()->{try{server=new ServerSocket(8765);server.setReuseAddress(true);while(active){Socket s=server.accept();workers.execute(()->serve(s));}}catch(Exception ex){ui.post(()->status.setText("Server problem: "+ex.getMessage()));}});}
  void serve(Socket socket){try(Socket s=socket;BufferedInputStream in=new BufferedInputStream(s.getInputStream());OutputStream out=new BufferedOutputStream(s.getOutputStream())){s.setSoTimeout(12000);
    ByteArrayOutputStream header=new ByteArrayOutputStream();int c,last=0;while((c=in.read())!=-1&&header.size()<16384){header.write(c);last=((last<<8)|c)&0xffffffff;if(last==0x0d0a0d0a)break;}
    String request=header.toString("ISO-8859-1");String[] p=request.split(" ");String path=p.length>1?p[1]:"/";
    if(path.startsWith("/state")){JSONObject j=new JSONObject();j.put("playing",playing);j.put("base",hostBase);j.put("at",hostTime);j.put("now",System.currentTimeMillis());j.put("version",version);j.put("cols",cols);j.put("rows",rows);byte[] bytes=j.toString().getBytes("UTF-8");reply(out,200,"application/json",bytes.length,"");out.write(bytes);
    }else if(path.startsWith("/video")&&movie!=null){String range=null;for(String h:request.split("\r\n"))if(h.toLowerCase().startsWith("range:"))range=h.substring(6).trim();
      android.os.ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(movie,"r");if(fd==null)return;try{long size=fd.getStatSize();if(size<0)return;long start=0,end=size-1;boolean partial=range!=null&&range.startsWith("bytes=");if(partial){String[] t=range.substring(6).split("-",2);if(!t[0].isEmpty())start=Long.parseLong(t[0]);if(t.length>1&&!t[1].isEmpty())end=Math.min(end,Long.parseLong(t[1]));}if(start<0||start>=size||end<start)return;long length=end-start+1;
        reply(out,partial?206:200,"video/mp4",length,"Accept-Ranges: bytes\r\n"+(partial?"Content-Range: bytes "+start+"-"+end+"/"+size+"\r\n":""));
        if(!request.startsWith("HEAD"))try(FileInputStream f=new FileInputStream(fd.getFileDescriptor())){f.getChannel().position(start);byte[] buf=new byte[65536];long rem=length;while(rem>0){int n=f.read(buf,0,(int)Math.min(buf.length,rem));if(n<0)break;out.write(buf,0,n);rem-=n;}}
      }finally{fd.close();}
    }else{byte[] err="Not found".getBytes("UTF-8");reply(out,404,"text/plain",err.length,"");out.write(err);}out.flush();
  }catch(Exception ignored){}}
  void reply(OutputStream out,int code,String mime,long length,String extra)throws IOException{String s="HTTP/1.1 "+code+" "+(code==206?"Partial Content":code==200?"OK":"Not Found")+"\r\nContent-Type: "+mime+"\r\nContent-Length: "+length+"\r\nConnection: close\r\n"+extra+"\r\n";out.write(s.getBytes("US-ASCII"));}
  void join(){String a=ip.getText().toString().trim().replace("http://","").replace("https://","");if(a.isEmpty())return;a=a.split("/")[0];if(!a.contains(":"))a+=":8765";hostIp=a;sourceUrl="http://"+a+"/video";host=false;joined=true;load(sourceUrl);update();workers.execute(this::poll);}
  void poll(){while(active&&joined){try{long t0=System.currentTimeMillis();HttpURLConnection c=(HttpURLConnection)new URL("http://"+hostIp+"/state").openConnection();c.setConnectTimeout(2000);c.setReadTimeout(2000);JSONObject j;try(InputStream in=c.getInputStream()){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[1024];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);j=new JSONObject(b.toString("UTF-8"));}long t1=System.currentTimeMillis();offset=j.getLong("now")-(t0+t1)/2;errors=0;ui.post(()->sync(j));
      }catch(Exception e){if(++errors==3)ui.post(()->status.setText("Nema veze sa hostom: "+e.getMessage()));}
      try{Thread.sleep(700);}catch(InterruptedException ignored){return;}
    }}
  void sync(JSONObject j){try{int v=j.getInt("version");if(v!=version){version=v;load(sourceUrl);}
    cols=j.getInt("cols");rows=j.getInt("rows");tile=Math.min(tile,cols*rows-1);transform();
    boolean want=j.getBoolean("playing");long pos=j.getLong("base")+(want?Math.max(0,System.currentTimeMillis()+offset-j.getLong("at")):0);int target=(int)Math.max(0,pos);
    if(player!=null&&ready){if(Math.abs(player.getCurrentPosition()-target)>300)player.seekTo(target);if(want&&!player.isPlaying())player.start();if(!want&&player.isPlaying())player.pause();}
    update();
  }catch(Exception ignored){}}
  void load(String path){ready=false;try{if(player!=null){player.reset();player.release();}player=new MediaPlayer();player.setAudioStreamType(AudioManager.STREAM_MUSIC);if(surface!=null)player.setSurface(surface);player.setDataSource(this,Uri.parse(path));player.setOnVideoSizeChangedListener((mp,w,h)->{videoW=w;videoH=h;transform();});player.setOnPreparedListener(mp->{ready=true;transform();if(host&&playing)mp.start();});player.setOnErrorListener((mp,what,extra)->{status.setText("Video greška: "+what+" / "+extra);return true;});player.prepareAsync();}catch(Exception ex){status.setText("Učitavanje: "+ex.getMessage());}}
  void control(boolean play){if(!host||player==null||!ready)return;hostBase=player.getCurrentPosition();hostTime=System.currentTimeMillis();playing=play;if(play)player.start();else player.pause();}
  void seek(int delta){if(!host||player==null||!ready)return;hostBase=Math.max(0,player.getCurrentPosition()+delta);hostTime=System.currentTimeMillis();player.seekTo((int)hostBase);}
  void tick(){if(host&&player!=null&&ready&&playing){if(player.isPlaying()&&player.getDuration()>0&&player.getCurrentPosition()>=player.getDuration()-500){playing=false;hostBase=0;hostTime=System.currentTimeMillis();}}if(active)ui.postDelayed(this::tick,500);}
  void transform(){if(screen==null||screen.getWidth()==0)return;int w=screen.getWidth(),h=screen.getHeight();
    float vw=videoW>0?videoW:1920,vh=videoH>0?videoH:1080;
    float scale=Math.max(w*cols/vw,h*rows/vh)*zoom;float sw=vw*scale,sh=vh*scale;
    float targetX=(tile%cols+.5f)*sw/cols;float targetY=(tile/cols+.5f)*sh/rows;
    Matrix m=new Matrix();m.setScale(sw/w,sh/h); // TextureView maps its original display rectangle to scaled image
    // Source videos are letterboxed internally by the decoder; adjust using the uniform virtual canvas.
    m.reset();m.setScale(scale*vw/w,scale*vh/h);
    m.postTranslate(w/2-targetX+dx,h/2-targetY+dy);
    screen.setTransform(m);
  }
  @Override public void onSurfaceTextureAvailable(SurfaceTexture st,int w,int h){surface=new Surface(st);if(player!=null)player.setSurface(surface);transform();}
  @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st,int w,int h){transform();}
  @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st){if(player!=null)player.setSurface(null);if(surface!=null)surface.release();surface=null;return true;}
  @Override public void onSurfaceTextureUpdated(SurfaceTexture st){}
  @Override protected void onDestroy(){active=false;joined=false;try{if(server!=null)server.close();}catch(Exception ignored){}workers.shutdownNow();if(player!=null){player.reset();player.release();}super.onDestroy();}
}
