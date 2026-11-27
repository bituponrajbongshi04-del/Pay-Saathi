package `in`.paysaathi.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

private val Navy=Color(0xFF071D35)
private val Blue=Color(0xFF1769E8)
private val Cyan=Color(0xFF55D7E8)
private val Muted=Color(0xFFB9D4E7)

class MainActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);if(Build.VERSION.SDK_INT>=26){val manager=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;manager.createNotificationChannel(NotificationChannel("paysaathi_updates","PaySaathi updates",NotificationManager.IMPORTANCE_DEFAULT).apply{description="Recharge, account and service updates"})};setContent{PaySaathiApp(this)}}
}
data class AppUpdate(val version:String,val notes:String,val apkUrl:String,val mandatory:Boolean)

class Api(context:Context){
 private val appContext=context.applicationContext
 private val http=OkHttpClient.Builder().callTimeout(35,TimeUnit.SECONDS).build()
 private val prefs=EncryptedSharedPreferences.create(context,"paysaathi_secure_session",MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
 private suspend fun raw(path:String,method:String="GET",body:String?=null,token:String?=prefs.getString("access",null),mime:String="application/json"):String=withContext(Dispatchers.IO){
  fun send(auth:String?):okhttp3.Response{
   val rb=body?.toRequestBody(mime.toMediaType())
   val req=Request.Builder().url(BuildConfig.SUPABASE_URL+path).addHeader("apikey",BuildConfig.SUPABASE_KEY).addHeader("Accept","application/json").apply{if(auth!=null)addHeader("Authorization","Bearer "+auth);if(rb!=null)this.method(method,rb)else this.method(method,null)}.build()
   return http.newCall(req).execute()
  }
  var response=send(token)
  if(response.code==401 && token!=null){
   response.close()
   val refresh=prefs.getString("refresh",null)
   if(!refresh.isNullOrBlank()){
    val refreshBody=JSONObject().put("refresh_token",refresh).toString().toRequestBody("application/json".toMediaType())
    val refreshReq=Request.Builder().url(BuildConfig.SUPABASE_URL+"/auth/v1/token?grant_type=refresh_token").addHeader("apikey",BuildConfig.SUPABASE_KEY).post(refreshBody).build()
    http.newCall(refreshReq).execute().use{rr->
     if(rr.isSuccessful){val payload=JSONObject(rr.body?.string().orEmpty());val access=payload.optString("access_token");if(access.isNotBlank()){prefs.edit().putString("access",access).putString("refresh",payload.optString("refresh_token",refresh)).apply();response=send(access)}}
    }
   }
  }
  response.use{res->val text=res.body?.string().orEmpty();if(!res.isSuccessful){val j=runCatching{JSONObject(text)}.getOrDefault(JSONObject());throw IOException(j.optString("msg",j.optString("message","Request failed ("+res.code+")")))};text}
 }
 suspend fun checkUpdate():AppUpdate?=withContext(Dispatchers.IO){
  val request=Request.Builder().url("https://api.github.com/repos/bituponrajbongshi04-del/Pay-Saathi/releases/latest").header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28").build()
  http.newCall(request).execute().use{response->
   if(!response.isSuccessful)return@withContext null
   val release=JSONObject(response.body?.string().orEmpty())
   val tag=release.optString("tag_name").removePrefix("v")
   fun parts(v:String)=v.split(".","-").take(3).map{it.toIntOrNull()?:0}
   val remote=parts(tag);val local=parts(BuildConfig.VERSION_NAME)
   val newer=(0..2).firstNotNullOfOrNull{if(remote.getOrElse(it){0}!=local.getOrElse(it){0}) remote.getOrElse(it){0}>local.getOrElse(it){0} else null}?:false
   if(!newer)return@withContext null
   val assets=release.optJSONArray("assets")?:JSONArray()
   val apk=(0 until assets.length()).mapNotNull{assets.optJSONObject(it)}.firstOrNull{it.optString("name").endsWith(".apk",true)}?:return@withContext null
   val notes=release.optString("body","A newer version of PaySaathi is available.")
   AppUpdate(tag,notes,apk.optString("browser_download_url"),notes.contains("[MANDATORY]",ignoreCase=true))
  }
 }
 suspend fun downloadUpdate(url:String):File=withContext(Dispatchers.IO){
  val request=Request.Builder().url(url).header("Accept","application/octet-stream").build()
  http.newCall(request).execute().use{response->if(!response.isSuccessful)throw IOException("Update download failed ("+response.code+")");val bytes=response.body?.bytes()?:throw IOException("Empty update file");if(bytes.size<1024)throw IOException("Downloaded update is invalid");val dir=File(appContext.cacheDir,"updates").apply{mkdirs()};File(dir,"PaySaathi-update.apk").apply{writeBytes(bytes)}}
 }
 private suspend fun obj(path:String,method:String="GET",body:String?=null,token:String?=prefs.getString("access",null))=JSONObject(raw(path,method,body,token).ifBlank{"{}"})
 suspend fun password(email:String,pw:String){obj("/auth/v1/token?grant_type=password","POST",JSONObject().put("email",email).put("password",pw).toString(),null)}
 suspend fun sendOtp(email:String){obj("/auth/v1/otp","POST",JSONObject().put("email",email).put("should_create_user",false).toString(),null)}
 suspend fun signup(name:String,email:String,pw:String,phone:String){obj("/auth/v1/signup","POST",JSONObject().put("email",email).put("password",pw).put("data",JSONObject().put("full_name",name).put("phone",phone)).toString(),null)}
 suspend fun verify(email:String,code:String,type:String){val o=obj("/auth/v1/verify","POST",JSONObject().put("email",email).put("token",code).put("type",type).toString(),null);prefs.edit().putString("access",o.getString("access_token")).putString("refresh",o.optString("refresh_token")).putString("uid",o.getJSONObject("user").getString("id")).apply()}
 fun hasSession()=!prefs.getString("access",null).isNullOrBlank()
 private fun uid()=prefs.getString("uid","")?: ""
 suspend fun wallet():String{val a=JSONArray(raw("/rest/v1/ps_wallets?select=balance&user_id=eq."+uid()));return if(a.length()>0)a.optJSONObject(0)?.optString("balance","0")?:"0" else "0"}
 suspend fun kyc():JSONObject{val a=JSONArray(raw("/rest/v1/paysaathi_kyc_profiles?select=*&user_id=eq."+uid()));if(a.length()>0)return a.optJSONObject(0)?:JSONObject();val inserted=raw("/rest/v1/paysaathi_kyc_profiles","POST",JSONObject().put("user_id",uid()).put("status","draft").toString());return JSONObject().put("status","draft")}
 suspend fun upload(uri:Uri,ctx:Context,doc:String){
  val data=withContext(Dispatchers.IO){val bytes=ctx.contentResolver.openInputStream(uri)?.use{it.readBytes()}?:throw IOException("Could not read selected file");val mime=ctx.contentResolver.getType(uri)?:throw IOException("Unsupported file");Pair(bytes,mime)}
  val bytes=data.first;val mime=data.second
  if(bytes.size>10*1024*1024)throw IOException("File must be 10 MB or smaller")
  if(mime !in listOf("image/jpeg","image/png","application/pdf"))throw IOException("Only PDF, JPG and PNG are accepted")
  kyc()
  val ext=when(mime){"image/jpeg"->"jpg";"image/png"->"png";else->"pdf"};val path=uid()+"/"+UUID.randomUUID()+"."+ext
  withContext(Dispatchers.IO){val req=Request.Builder().url(BuildConfig.SUPABASE_URL+"/storage/v1/object/paysaathi-kyc/"+path).addHeader("apikey",BuildConfig.SUPABASE_KEY).addHeader("Authorization","Bearer "+prefs.getString("access",null)).addHeader("Content-Type",mime).post(bytes.toRequestBody(mime.toMediaType())).build();http.newCall(req).execute().use{if(!it.isSuccessful)throw IOException("Secure upload failed ("+it.code+")")}}
  raw("/rest/v1/paysaathi_kyc_documents","POST",JSONObject().put("user_id",uid()).put("document_type",doc).put("storage_path",path).put("original_file_name","Android upload."+ext).put("mime_type",mime).put("file_size_bytes",bytes.size).toString())
 }
 suspend fun documents():JSONArray=JSONArray(raw("/rest/v1/paysaathi_kyc_documents?select=document_type,status&user_id=eq."+uid()))
 suspend fun submitKyc(name:String){val list=documents();val pan=(0 until list.length()).any{list.optJSONObject(it)?.optString("document_type")=="pan"&&list.optJSONObject(it)?.optString("status")!="rejected"};val identity=(0 until list.length()).any{list.optJSONObject(it)?.optString("document_type")=="identity_proof"&&list.optJSONObject(it)?.optString("status")!="rejected"};if(!pan||!identity)throw IOException("Upload PAN and one identity proof before submitting.");raw("/rest/v1/paysaathi_kyc_profiles?user_id=eq."+uid(),"PATCH",JSONObject().put("status","submitted").put("legal_name",name).put("submitted_at",java.time.Instant.now().toString()).toString())}
 suspend fun access(service:String):Boolean{val o=obj("/rest/v1/rpc/paysaathi_kyc_access","POST",JSONObject().put("p_service_key",service).toString());return o.optBoolean("allowed",false)}
 suspend fun notifications():JSONArray=JSONArray(raw("/rest/v1/ps_notifications?select=id,title,message,is_read,created_at&or=(user_id.eq."+uid()+",user_id.is.null)&order=created_at.desc&limit=30"))
 suspend fun markNotificationRead(id:String){raw("/rest/v1/ps_notifications?id=eq."+id,"PATCH",JSONObject().put("is_read",true).toString())}
 suspend fun signout(){prefs.edit().clear().apply()}
}
@Composable fun PaySaathiApp(ctx:Context){
 val api=remember{Api(ctx)};val scope=rememberCoroutineScope()
 var page by remember{mutableStateOf(if(api.hasSession())"home" else "login")}
 val pageHistory=remember{mutableStateListOf<String>()}
 fun navigate(destination:String){if(destination!=page){pageHistory.add(page);page=destination}}
 fun goBack(){if(pageHistory.isNotEmpty())page=pageHistory.removeAt(pageHistory.lastIndex)}
 var email by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var name by remember{mutableStateOf("")};var phone by remember{mutableStateOf("")};var code by remember{mutableStateOf("")}
 var signup by remember{mutableStateOf(false)};var waiting by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
 var updateInfo by remember{mutableStateOf<AppUpdate?>(null)};var updateBusy by remember{mutableStateOf(false)};var updateMessage by remember{mutableStateOf("")};var downloadedApk by remember{mutableStateOf<File?>(null)}
 var status by remember{mutableStateOf("not_started")};var balance by remember{mutableStateOf("0.00")};var legalName by remember{mutableStateOf("")};var docType by remember{mutableStateOf("pan")};var picked by remember{mutableStateOf<Uri?>(null)}
 var serviceKey by remember{mutableStateOf("")};var serviceLabel by remember{mutableStateOf("")};var serviceInput by remember{mutableStateOf("")};var serviceAmount by remember{mutableStateOf("")};var serviceNotice by remember{mutableStateOf("")}
 var notificationItems by remember{mutableStateOf(JSONArray())};var notificationCount by remember{mutableIntStateOf(0)};var lastNotifiedId by remember{mutableStateOf("")};var permissionMessage by remember{mutableStateOf("")}
 val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->permissionMessage=if(granted)"Notifications enabled." else "Notifications are off. You can enable them in Android Settings."}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){picked=it}
 fun requestNotifications(){if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(ctx,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)else permissionMessage="Notifications are already enabled."}
 fun showDeviceNotification(title:String,message:String){if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(ctx,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;val n=NotificationCompat.Builder(ctx,"paysaathi_updates").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).build();(ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify((System.currentTimeMillis()%100000).toInt(),n)}
 fun runTask(block:suspend()->Unit){scope.launch{waiting=true;error="";try{block()}catch(e:Exception){error=e.message?: "Something went wrong"}finally{waiting=false}}}
 LaunchedEffect(Unit){runCatching{api.checkUpdate()}.onSuccess{updateInfo=it}}
 fun installDownloaded(){val file=downloadedApk?:return
  if(Build.VERSION.SDK_INT>=26 && !ctx.packageManager.canRequestPackageInstalls()){
   (ctx as? android.app.Activity)?.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+ctx.packageName)))
   updateMessage="Allow PaySaathi to install this update, then tap Install update again."
   return
  }
  val uri=FileProvider.getUriForFile(ctx,ctx.packageName+".fileprovider",file)
  ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
 }
 LaunchedEffect(page){if(page=="home"){runTask{balance=api.wallet();val k=api.kyc();status=k.optString("status","not_started");legalName=k.optString("legal_name","");notificationItems=api.notifications();notificationCount=(0 until notificationItems.length()).count{!notificationItems.optJSONObject(it).optBoolean("is_read",false)};if(notificationItems.length()>0){val latest=notificationItems.optJSONObject(0);val id=latest.optString("id");if(id.isNotBlank()&&id!=lastNotifiedId&&lastNotifiedId.isNotBlank()){showDeviceNotification(latest.optString("title","PaySaathi update"),latest.optString("message","You have a new update."))};if(id.isNotBlank())lastNotifiedId=id}}}}
 LaunchedEffect(page){if(page=="home"&&api.hasSession()){while(true){delay(60000);runCatching{val fresh=api.notifications();notificationItems=fresh;notificationCount=(0 until fresh.length()).count{!fresh.optJSONObject(it).optBoolean("is_read",false)};if(fresh.length()>0){val latest=fresh.optJSONObject(0);val id=latest.optString("id");if(id.isNotBlank()&&id!=lastNotifiedId){showDeviceNotification(latest.optString("title","PaySaathi update"),latest.optString("message","You have a new update."));lastNotifiedId=id}}}}}}
 BackHandler(enabled=pageHistory.isNotEmpty()){goBack()}
 MaterialTheme(colorScheme=darkColorScheme(primary=Cyan,background=Navy,surface=Color(0xFF102E49),onSurface=Color.White)){
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Navy,Color(0xFF0A3558),Navy)))){
   Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(13.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){if(pageHistory.isNotEmpty())IconButton(onClick={goBack()}){Icon(Icons.Default.ArrowBack,null,tint=Color.White)};Surface(shape=RoundedCornerShape(14.dp),color=Blue){Text("P",Modifier.padding(horizontal=15.dp,vertical=9.dp),fontWeight=FontWeight.Black,color=Color.White,fontSize=22.sp)};Spacer(Modifier.width(10.dp));Column{Text("PaySaathi",fontSize=23.sp,fontWeight=FontWeight.Black,color=Color.White);Text("YOUR MONEY. YOUR SERVICES. ONE SAATHI.",fontSize=8.sp,color=Cyan,fontWeight=FontWeight.Bold)};Spacer(Modifier.weight(1f));if(page=="home"){IconButton(onClick={navigate("notifications")}){BadgedBox(badge={if(notificationCount>0)Badge{Text(if(notificationCount>9)"9+" else notificationCount.toString())}}){Icon(Icons.Default.Notifications,null,tint=Color.White)}};IconButton(onClick={runTask{api.signout();pageHistory.clear();page="login"}}){Icon(Icons.Default.Logout,null,tint=Color.White)}}}
    if(updateInfo!=null){Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF174A68)),shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("PaySaathi update available · v"+updateInfo!!.version,color=Color.White,fontWeight=FontWeight.Bold);Text("Get the latest features and improvements directly here.",color=Muted,fontSize=12.sp);Button(onClick={runTask{downloadedApk=api.downloadUpdate(updateInfo!!.apkUrl);updateMessage="Update downloaded. Tap Install update to continue."}},enabled=!waiting){Text(if(waiting)"Downloading…" else "Download update")};if(downloadedApk!=null)Button(onClick={installDownloaded()}){Text("Install update")};if(updateMessage.isNotBlank())Text(updateMessage,color=Cyan,fontSize=11.sp)}}}
    if(updateInfo?.mandatory==true){
     Text("Required PaySaathi update",fontSize=27.sp,fontWeight=FontWeight.ExtraBold,color=Color.White)
     Text("This version must be updated before you can continue using PaySaathi.",color=Muted,fontSize=13.sp)
     Button(onClick={runTask{downloadedApk=api.downloadUpdate(updateInfo!!.apkUrl);updateMessage="Update downloaded. Install it to continue."}},enabled=!waiting,modifier=Modifier.fillMaxWidth()){Text(if(waiting)"Downloading…" else "Download required update")}
     if(downloadedApk!=null)Button(onClick={installDownloaded()},modifier=Modifier.fillMaxWidth()){Text("Install and continue")}
     if(updateMessage.isNotBlank())Text(updateMessage,color=Cyan,fontSize=12.sp)
    }else if(page=="login"||page=="signup"||page=="otp"){
     Text(if(page=="signup")"Create your account" else if(page=="otp")"Verify your email" else "Welcome back",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Color.White)
     Text(if(page=="otp")"Enter the six-digit code sent to "+email else "Secure access to your PaySaathi services",color=Muted,fontSize=12.sp)
     if(page=="signup"){Field("Full name",name,{name=it});Field("Mobile number",phone,{phone=it},KeyboardType.Phone)}
     Field("Email address",email,{email=it},KeyboardType.Email)
     if(page!="otp")Field("Password",password,{password=it},secret=true) else Field("6-digit OTP",code,{code=it},KeyboardType.Number)
     Button(onClick={runTask{when(page){"login"->{api.password(email,password);api.sendOtp(email);navigate("otp")};"signup"->{api.signup(name,email,password,phone);signup=true;navigate("otp")};else->{api.verify(email,code,if(signup)"signup" else "email");pageHistory.clear();page="home"}}}},enabled=!waiting,modifier=Modifier.fillMaxWidth()){Text(if(waiting)"Please wait…" else when(page){"otp"->"Verify & continue";"signup"->"Create account";else->"Continue securely"})}
     TextButton(onClick={val target=if(page=="login")"signup" else "login";signup=target=="signup";navigate(target)}){Text(if(page=="login")"New to PaySaathi? Create account" else "Already registered? Log in",color=Cyan)}
    }else if(page=="home"){
     Text("Good to see you",fontSize=25.sp,fontWeight=FontWeight.Bold,color=Color.White)
     Card(colors=CardDefaults.cardColors(containerColor=Blue),shape=RoundedCornerShape(22.dp)){Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF087FCA),Color(0xFF123A75)))).padding(20.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("PAYSAATHI WALLET",fontSize=10.sp,color=Color.White.copy(.78f),fontWeight=FontWeight.Bold);Text("₹ "+balance,fontSize=31.sp,fontWeight=FontWeight.Black,color=Color.White);Text("Available balance",fontSize=10.sp,color=Color.White.copy(.8f))};Surface(color=Color.White.copy(.15f),shape=RoundedCornerShape(16.dp)){Icon(Icons.Default.AccountBalanceWallet,null,Modifier.padding(15.dp),tint=Color.White)}};Spacer(Modifier.height(15.dp));Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){Button(onClick={error="Wallet funding is in preview mode. Payment gateway is not connected."},colors=ButtonDefaults.buttonColors(containerColor=Color.White,contentColor=Blue)){Text("Add money")};OutlinedButton(onClick={navigate("notifications")},colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)){Text("Notifications")}}}}
     Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF164568)),shape=RoundedCornerShape(18.dp)){Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("PAY BILLS. RECHARGE. STAY IN CONTROL.",fontSize=10.sp,fontWeight=FontWeight.ExtraBold,color=Cyan);Text("Everyday services, all in one place.",fontSize=13.sp,fontWeight=FontWeight.Bold,color=Color.White);Text("Simple, secure and convenient.",fontSize=10.sp,color=Muted)};Icon(Icons.Default.VerifiedUser,null,tint=Cyan,modifier=Modifier.size(34.dp))}}
     if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(ctx,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF193B58)),shape=RoundedCornerShape(14.dp)){Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Get important updates",color=Color.White,fontWeight=FontWeight.Bold,fontSize=12.sp);Text("Recharge, account and service notifications.",color=Muted,fontSize=10.sp)};TextButton(onClick={requestNotifications()}){Text("Enable",color=Cyan)}}}}
     Text("Recharge & bill payments",fontWeight=FontWeight.Bold,color=Color.White,fontSize=17.sp)
     listOf(listOf("Mobile Recharge" to "📱","DTH Recharge" to "📺","Electricity Bills" to "⚡"),listOf("Gas Bills" to "🔥","Water Bills" to "💧","Broadband" to "🌐"),listOf("Postpaid Bills" to "📞","Money Transfer" to "↗","AEPS Banking" to "🏦")).forEach{group->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)){group.forEach{(label,emoji)->Card(Modifier.weight(1f).clickable{val key=when(label){"Money Transfer"->"money_transfer";"AEPS Banking"->"aeps";"Mobile Recharge"->"mobile_recharge";"DTH Recharge"->"dth_recharge";"Electricity Bills"->"electricity";"Gas Bills"->"gas";"Water Bills"->"water";"Broadband"->"broadband";else->"postpaid"};runTask{if(api.access(key)){serviceKey=key;serviceLabel=label;serviceInput="";serviceAmount="";serviceNotice="";navigate("service")}else if(key in listOf("money_transfer","aeps")){error="KYC approval is required for "+label;navigate("kyc")}else error="This service is currently unavailable."}},colors=CardDefaults.cardColors(containerColor=Color(0xFF123451)),shape=RoundedCornerShape(15.dp)){Column(Modifier.fillMaxWidth().padding(vertical=14.dp,horizontal=5.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(emoji,fontSize=22.sp);Text(label,fontSize=9.sp,fontWeight=FontWeight.Bold,color=Color.White,textAlign=androidx.compose.ui.text.style.TextAlign.Center,minLines=2)}}}}}
     Text("Your account",fontWeight=FontWeight.Bold,color=Color.White,fontSize=17.sp)
     Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF123451)),shape=RoundedCornerShape(17.dp)){Row(Modifier.fillMaxWidth().clickable{navigate("kyc")}.padding(15.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.VerifiedUser,null,tint=Cyan);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text("Identity verification",fontWeight=FontWeight.Bold,color=Color.White);Text("KYC status: "+status,color=Cyan,fontSize=11.sp)};Icon(Icons.Default.ChevronRight,null,tint=Cyan)}}
     Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){OutlinedButton(onClick={navigate("notifications")},modifier=Modifier.weight(1f)){Text("Notifications")};OutlinedButton(onClick={error="Support centre is available on the PaySaathi website dashboard."},modifier=Modifier.weight(1f)){Text("Help & support")}}
     Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF123451)),shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(17.dp)){Text("Identity verification",fontWeight=FontWeight.Bold,color=Color.White);Text("KYC status: "+status,color=Cyan,fontSize=12.sp);TextButton(onClick={navigate("kyc")}){Text("Open KYC centre",color=Cyan)}}}
     Text("Your services",fontWeight=FontWeight.Bold,color=Color.White,fontSize=17.sp)
     Text("Available without KYC",fontSize=13.sp,fontWeight=FontWeight.Bold,color=Cyan)
     listOf("Mobile Recharge" to "mobile_recharge","DTH Recharge" to "dth_recharge","Electricity Bills" to "electricity","Gas Bills" to "gas","Water Bills" to "water","Broadband" to "broadband","Postpaid Bills" to "postpaid").forEach{(label,key)->ServiceCard(label,"No KYC required",true){runTask{if(api.access(key)){serviceKey=key;serviceLabel=label;serviceInput="";serviceAmount="";serviceNotice="";navigate("service")}else error="This service is temporarily unavailable. Please try again."}}}
     Text("KYC required services",fontSize=13.sp,fontWeight=FontWeight.Bold,color=Cyan)
     listOf("Money Transfer" to "money_transfer","AEPS & Assisted Banking" to "aeps","Payout" to "payout","Wallet Top-up" to "wallet_topup","UTI Services" to "uti").forEach{(label,key)->ServiceCard(label,"KYC approval required",false){runTask{if(api.access(key)){serviceKey=key;serviceLabel=label;serviceInput="";serviceAmount="";serviceNotice="";navigate("service")}else{error="Complete KYC and wait for approval to unlock "+label+".";navigate("kyc")}}}}
     Text("Your account remains open while KYC is being reviewed.",fontSize=10.sp,color=Muted)
    }else if(page=="notifications"){
     Text("Notifications",fontSize=26.sp,fontWeight=FontWeight.Bold,color=Color.White)
     Text("Account, service and important PaySaathi updates.",fontSize=12.sp,color=Muted)
     Button(onClick={runTask{notificationItems=api.notifications();notificationCount=(0 until notificationItems.length()).count{!notificationItems.optJSONObject(it).optBoolean("is_read",false)}}},enabled=!waiting){Text("Refresh notifications")}
     if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(ctx,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){OutlinedButton(onClick={requestNotifications()}){Text("Enable phone notifications")}}
     if(notificationItems.length()==0)Text("No notifications yet.",color=Muted,fontSize=12.sp) else (0 until notificationItems.length()).forEach{index->val item=notificationItems.optJSONObject(index);Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF123451)),shape=RoundedCornerShape(15.dp)){Column(Modifier.fillMaxWidth().clickable{val id=item.optString("id");runTask{if(id.isNotBlank())api.markNotificationRead(id);notificationItems=api.notifications();notificationCount=(0 until notificationItems.length()).count{!notificationItems.optJSONObject(it).optBoolean("is_read",false)}}}.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text(item.optString("title","PaySaathi update"),Modifier.weight(1f),fontWeight=FontWeight.Bold,color=Color.White);if(!item.optBoolean("is_read",false))Badge{Text("NEW")}};Text(item.optString("message",""),color=Muted,fontSize=12.sp);Text(if(item.optBoolean("is_read",false))"Read" else "Tap to mark as read",color=Cyan,fontSize=9.sp)}}}
     if(permissionMessage.isNotBlank())Text(permissionMessage,color=Cyan,fontSize=11.sp)
    }else if(page=="service"){
     Text(serviceLabel,fontSize=26.sp,fontWeight=FontWeight.Bold,color=Color.White)
     Text(if(serviceKey in listOf("mobile_recharge","dth_recharge","electricity","gas","water","broadband","postpaid"))"Available to all signed-in users · No KYC required" else "KYC-approved service",fontSize=11.sp,color=Cyan,fontWeight=FontWeight.Bold)
     Text("Enter the details below to test this service screen.",fontSize=12.sp,color=Muted)
     Field(when(serviceKey){"mobile_recharge"->"Mobile number";"dth_recharge"->"DTH Customer ID";"electricity"->"Consumer / account number";"gas"->"LPG / consumer number";"water"->"Consumer number";"broadband"->"Account / landline number";"postpaid"->"Postpaid mobile number";"money_transfer"->"Beneficiary account / mobile";"aeps"->"Customer mobile / Aadhaar last 4 digits";"payout"->"Beneficiary account";"wallet_topup"->"Wallet reference";else->"Customer reference"},serviceInput,{serviceInput=it},KeyboardType.Text)
     Field("Amount (₹)",serviceAmount,{serviceAmount=it},KeyboardType.Number)
     Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF123451)),shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(14.dp)){Text("TESTING MODE",fontWeight=FontWeight.ExtraBold,color=Cyan,fontSize=11.sp);Text("No payment will be collected and no real recharge or bill payment will happen. Provider integration is not connected yet.",fontSize=11.sp,color=Muted)}}
     Button(onClick={runTask{if(serviceInput.isBlank())throw IOException("Enter the customer or account number.");if(serviceAmount.isBlank()||serviceAmount.toDoubleOrNull()==null||serviceAmount.toDouble()<=0)throw IOException("Enter a valid amount.");if(!api.access(serviceKey)){serviceNotice="KYC approval is required for this service.";navigate("kyc")}else{serviceNotice="Screen test passed. No transaction was created and no money was charged."}}},enabled=!waiting,modifier=Modifier.fillMaxWidth()){Text(if(waiting)"Checking…" else "Continue test")}
     if(serviceNotice.isNotBlank())Text(serviceNotice,color=Cyan,fontSize=12.sp)
     TextButton(onClick={serviceNotice="";goBack()}){Text("Back to services",color=Cyan)}
    }else{
     Text("Complete your KYC",fontSize=26.sp,fontWeight=FontWeight.Bold,color=Color.White);Text("Upload PAN and one accepted identity proof. Documents remain private until an authorised reviewer checks them.",fontSize=12.sp,color=Muted)
     Field("Full legal name",legalName,{legalName=it})
     Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=docType=="pan",onClick={docType="pan"},label={Text("PAN")});FilterChip(selected=docType=="identity_proof",onClick={docType="identity_proof"},label={Text("Identity proof")})}
     OutlinedButton(onClick={picker.launch(arrayOf("application/pdf","image/jpeg","image/png"))}){Icon(Icons.Default.UploadFile,null);Spacer(Modifier.width(8.dp));Text(if(picked==null)"Choose PDF / JPG / PNG" else "Document selected")}
     Button(onClick={runTask{val file=picked?:throw IOException("Choose a document first");api.upload(file,ctx,docType);picked=null}},enabled=!waiting,modifier=Modifier.fillMaxWidth()){Text("Upload securely")}
     Button(onClick={runTask{api.submitKyc(legalName);status="submitted";goBack()}},enabled=!waiting,modifier=Modifier.fillMaxWidth()){Text("Submit KYC for review")}
     TextButton(onClick={goBack()}){Text("Back to dashboard",color=Cyan)}
    }
    if(error.isNotBlank())Text(error,color=Color(0xFFFFB4AB),fontSize=11.sp)
    Spacer(Modifier.height(8.dp));Text("PaySaathi · Secure access · India",color=Color(0xFF8BAFC8),fontSize=10.sp)
   }
  }
 }
}
@Composable fun Field(label:String,value:String,onChange:(String)->Unit,type:KeyboardType=KeyboardType.Text,secret:Boolean=false){Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(label,color=Color(0xFFBBD8EA),fontSize=11.sp,fontWeight=FontWeight.Bold);OutlinedTextField(value,onChange,modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=type),visualTransformation=if(secret)PasswordVisualTransformation()else androidx.compose.ui.text.input.VisualTransformation.None,colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White,unfocusedTextColor=Color.White,focusedBorderColor=Cyan,unfocusedBorderColor=Color(0xFF44637B),cursorColor=Cyan))}}

@Composable fun ServiceCard(label:String,subtitle:String,openAccess:Boolean,onClick:()->Unit){Card(Modifier.fillMaxWidth().clickable(onClick=onClick),colors=CardDefaults.cardColors(containerColor=Color(0xFF123451)),shape=RoundedCornerShape(17.dp)){Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically){Surface(shape=RoundedCornerShape(12.dp),color=if(openAccess)Color(0xFF164C5B)else Color(0xFF493B25)){Icon(if(openAccess)Icons.Default.ReceiptLong else Icons.Default.Lock,null,Modifier.padding(10.dp),tint=if(openAccess)Cyan else Color(0xFFFFD28A))};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(label,color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=13.sp);Text(subtitle,color=Muted,fontSize=10.sp)};Icon(Icons.Default.ChevronRight,null,tint=Cyan)}}}
