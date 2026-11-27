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
 var page by remember{mutableStateOf(if(api.hasSession())"home" else "welcome")}
 val history=remember{mutableStateListOf<String>()}
 fun nav(p:String){if(p!=page){history.add(page);page=p}}
 fun back(){if(history.isNotEmpty())page=history.removeAt(history.lastIndex)}
 var email by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var name by remember{mutableStateOf("")};var phone by remember{mutableStateOf("")};var code by remember{mutableStateOf("")}
 var signup by remember{mutableStateOf(false)};var waiting by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
 var updateInfo by remember{mutableStateOf<AppUpdate?>(null)};var updateMessage by remember{mutableStateOf("")};var downloadedApk by remember{mutableStateOf<File?>(null)}
 var status by remember{mutableStateOf("not_started")};var balance by remember{mutableStateOf("0.00")};var legalName by remember{mutableStateOf("")};var docType by remember{mutableStateOf("pan")};var picked by remember{mutableStateOf<Uri?>(null)}
 var serviceKey by remember{mutableStateOf("")};var serviceLabel by remember{mutableStateOf("")};var serviceInput by remember{mutableStateOf("")};var serviceAmount by remember{mutableStateOf("")};var serviceOperator by remember{mutableStateOf("")};var operatorMenuOpen by remember{mutableStateOf(false)}
 var receipt by remember{mutableStateOf<JSONObject?>(null)}
 var notificationItems by remember{mutableStateOf(JSONArray())};var notificationCount by remember{mutableIntStateOf(0)}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){picked=it}
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
 fun runTask(block:suspend()->Unit){scope.launch{waiting=true;error="";try{block()}catch(e:Exception){error=e.message?:"Something went wrong"}finally{waiting=false}}}
 LaunchedEffect(Unit){runCatching{api.checkUpdate()}.onSuccess{updateInfo=it}}
 fun install(){val file=downloadedApk?:return;if(Build.VERSION.SDK_INT>=26&&!ctx.packageManager.canRequestPackageInstalls()){(ctx as? android.app.Activity)?.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+ctx.packageName)));updateMessage="Allow installation permission, then tap Install again.";return};val uri=FileProvider.getUriForFile(ctx,ctx.packageName+".fileprovider",file);ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))}
 LaunchedEffect(page){if(page=="home"){runTask{balance=api.wallet();val k=api.kyc();status=k.optString("status","draft");legalName=k.optString("legal_name","");notificationItems=api.notifications();notificationCount=(0 until notificationItems.length()).count{!notificationItems.optJSONObject(it).optBoolean("is_read",false)}}}}
 BackHandler(enabled=history.isNotEmpty()){back()}
 val ink=Color(0xFF172B4D);val muted=Color(0xFF6B7890);val blue=Color(0xFF245FE5);val pale=Color(0xFFF3F6FC);val line=Color(0xFFE5EAF2)
 MaterialTheme(colorScheme=lightColorScheme(primary=blue,onPrimary=Color.White,secondary=Color(0xFF16A6A0),background=pale,surface=Color.White,onSurface=ink,onBackground=ink,error=Color(0xFFCB3A45))){
  Scaffold(containerColor=pale,topBar={
   if(page !in listOf("welcome","login","signup","otp")) TopAppBar(
    title={Row(verticalAlignment=Alignment.CenterVertically){Surface(shape=RoundedCornerShape(12.dp),color=blue){Text("P",Modifier.padding(horizontal=12.dp,vertical=7.dp),color=Color.White,fontWeight=FontWeight.Black,fontSize=19.sp)};Spacer(Modifier.width(10.dp));Column{Text("PaySaathi",fontWeight=FontWeight.ExtraBold,fontSize=19.sp,color=ink);Text("Payments made simpler",fontSize=10.sp,color=muted)}}},
    navigationIcon={if(history.isNotEmpty())IconButton(onClick={back}){Icon(Icons.Default.ArrowBack,null,tint=ink)}},
    actions={if(page=="home"){BadgedBox(badge={if(notificationCount>0)Badge{Text(if(notificationCount>9)"9+" else notificationCount.toString())}}){IconButton(onClick={nav("notifications")}){Icon(Icons.Default.NotificationsNone,null,tint=ink)}};IconButton(onClick={runTask{api.signout();history.clear();page="welcome"}}){Icon(Icons.Default.Logout,null,tint=ink)}}},
    colors=TopAppBarDefaults.topAppBarColors(containerColor=Color.White)
   )
  },bottomBar={if(page=="home")NavigationBar(containerColor=Color.White){NavigationBarItem(selected=true,onClick={page="home";history.clear()},icon={Icon(Icons.Default.Home,null)},label={Text("Home")});NavigationBarItem(selected=false,onClick={nav("transactions")},icon={Icon(Icons.Default.ReceiptLong,null)},label={Text("Activity")});NavigationBarItem(selected=false,onClick={nav("profile")},icon={Icon(Icons.Default.PersonOutline,null)},label={Text("Profile")})}}){padding->
   Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
    if(updateInfo!=null){Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFEAF1FF)),shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(14.dp)){Text("Update available · v"+updateInfo!!.version,fontWeight=FontWeight.Bold,color=blue);Button(onClick={runTask{downloadedApk=api.downloadUpdate(updateInfo!!.apkUrl);updateMessage="Downloaded. Tap install to finish."}},enabled=!waiting){Text("Download update")};if(downloadedApk!=null)TextButton(onClick={install}){Text("Install update")};if(updateMessage.isNotBlank())Text(updateMessage,color=muted,fontSize=11.sp)}}}
    if(page=="welcome"){
     Spacer(Modifier.height(28.dp))
     Box(Modifier.fillMaxWidth().height(230.dp).background(Brush.linearGradient(listOf(Color(0xFF1D63EA),Color(0xFF13A7B4))),RoundedCornerShape(30.dp)),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Surface(shape=RoundedCornerShape(24.dp),color=Color.White.copy(.18f)){Icon(Icons.Default.AccountBalanceWallet,null,Modifier.padding(22.dp).size(54.dp),tint=Color.White)};Text("Money moves.\nMade simple.",fontSize=30.sp,lineHeight=36.sp,fontWeight=FontWeight.Black,color=Color.White,textAlign=androidx.compose.ui.text.style.TextAlign.Center);Text("One app for everyday payments",color=Color.White.copy(.9f),fontSize=13.sp)}}
     Text("Welcome to PaySaathi",fontSize=27.sp,fontWeight=FontWeight.ExtraBold,color=ink)
     Text("Recharge, pay household bills and keep your payment activity organised in one clean, easy-to-use place.",fontSize=14.sp,color=muted)
     listOf("Mobile, DTH and utility bill services" to Icons.Default.PhoneAndroid,"Clear transaction details and receipts" to Icons.Default.ReceiptLong,"Account security and KYC support" to Icons.Default.VerifiedUser).forEach{(label,ic)->Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(17.dp)){Row(Modifier.fillMaxWidth().padding(15.dp),verticalAlignment=Alignment.CenterVertically){Icon(ic,null,tint=blue,modifier=Modifier.size(25.dp));Spacer(Modifier.width(13.dp));Text(label,color=ink,fontWeight=FontWeight.SemiBold,fontSize=13.sp)}}}
     Button(onClick={nav("signup")},modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(15.dp)){Text("Get started",fontWeight=FontWeight.Bold)}
     OutlinedButton(onClick={nav("login")},modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(15.dp)){Text("I already have an account")}
     Text("By continuing, you agree to our terms and privacy policy.",fontSize=10.sp,color=muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth())
    }else if(page in listOf("login","signup","otp")){
     Text(if(page=="signup")"Create your account" else if(page=="otp")"Verify your email" else "Welcome back",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=ink)
     Text(if(page=="otp")"Enter the verification code sent to $email" else "Sign in securely to continue with PaySaathi.",fontSize=13.sp,color=muted)
     if(page=="signup"){Field("Full name",name,{name=it});Field("Mobile number",phone,{phone=it},KeyboardType.Phone)}
     Field("Email address",email,{email=it},KeyboardType.Email)
     if(page!="otp")Field("Password",password,{password=it},secret=true)else Field("6-digit verification code",code,{code=it},KeyboardType.Number)
     Button(onClick={runTask{when(page){"login"->{api.password(email,password);api.sendOtp(email);nav("otp")};"signup"->{if(name.isBlank()||phone.length!=10)throw IOException("Enter your name and a valid 10-digit mobile number.");api.signup(name,email,password,phone);signup=true;nav("otp")};else->{api.verify(email,code,if(signup)"signup" else "email");history.clear();page="home"}}}},enabled=!waiting,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(14.dp)){Text(if(waiting)"Please wait…" else when(page){"signup"->"Create account";"otp"->"Verify & continue";else->"Continue securely"})}
     TextButton(onClick={if(page=="otp"){code="";error="Check your inbox for the latest code."}else{signup=page=="login";nav(if(page=="login")"signup" else "login")},modifier=Modifier.fillMaxWidth()){Text(if(page=="login")"New to PaySaathi? Create account" else if(page=="signup")"Already registered? Sign in" else "Resend / check code",color=blue)}
     TextButton(onClick={history.clear();page="welcome"},modifier=Modifier.fillMaxWidth()){Text("Back to welcome",color=muted)}
    }else if(page=="home"){
     Text("Hello, welcome back 👋",fontSize=24.sp,fontWeight=FontWeight.ExtraBold,color=ink)
     Card(colors=CardDefaults.cardColors(containerColor=blue),shape=RoundedCornerShape(24.dp)){Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF245FE5),Color(0xFF11A8B0)))).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("PAYSAATHI WALLET",fontSize=10.sp,color=Color.White.copy(.82f),fontWeight=FontWeight.Bold);Text("₹ $balance",fontSize=32.sp,fontWeight=FontWeight.Black,color=Color.White);Text("Available balance",fontSize=11.sp,color=Color.White.copy(.85f))};Icon(Icons.Default.AccountBalanceWallet,null,tint=Color.White,modifier=Modifier.size(36.dp))};Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(onClick={error="Wallet top-up is in preview mode. Payment gateway is not connected."},colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White),border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(.65f))){Text("Add money")};OutlinedButton(onClick={nav("transactions")},colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White),border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(.65f))){Text("My activity")}}}}
     Text("What would you like to do?",fontSize=18.sp,fontWeight=FontWeight.Bold,color=ink)
     val services=listOf(Triple("Mobile recharge","mobile_recharge",Icons.Default.PhoneAndroid),Triple("DTH recharge","dth_recharge",Icons.Default.Tv),Triple("Electricity","electricity",Icons.Default.Bolt),Triple("Gas cylinder","gas",Icons.Default.LocalFireDepartment),Triple("Water bill","water",Icons.Default.WaterDrop),Triple("Broadband","broadband",Icons.Default.Wifi),Triple("Postpaid","postpaid",Icons.Default.Receipt),Triple("Money transfer","money_transfer",Icons.Default.Send),Triple("AEPS banking","aeps",Icons.Default.AccountBalance))
     services.chunked(3).forEach{group->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){group.forEach{(label,key,ic)->Card(Modifier.weight(1f).clickable{runTask{if(api.access(key)){serviceKey=key;serviceLabel=label;serviceInput="";serviceAmount="";serviceOperator="";nav("service")}else if(key in listOf("money_transfer","aeps")){error="KYC approval is required for this service.";nav("kyc")}else error="This service is temporarily unavailable."}},colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){Column(Modifier.fillMaxWidth().padding(vertical=17.dp,horizontal=5.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){Surface(shape=RoundedCornerShape(13.dp),color=Color(0xFFEAF1FF)){Icon(ic,null,Modifier.padding(11.dp).size(23.dp),tint=blue)};Text(label,fontSize=10.sp,fontWeight=FontWeight.Bold,color=ink,textAlign=androidx.compose.ui.text.style.TextAlign.Center,minLines=2)}}}}}
     Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){Row(Modifier.fillMaxWidth().clickable{nav("kyc")}.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.VerifiedUser,null,tint=blue);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text("Identity verification",fontWeight=FontWeight.Bold,color=ink);Text("KYC status: $status",fontSize=11.sp,color=muted)};Icon(Icons.Default.ChevronRight,null,tint=muted)}}
     Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){Row(Modifier.fillMaxWidth().clickable{nav("notifications")}.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.NotificationsNone,null,tint=blue);Spacer(Modifier.width(12.dp));Text("Notifications",Modifier.weight(1f),fontWeight=FontWeight.Bold,color=ink);if(notificationCount>0)Badge{Text(notificationCount.toString())};Icon(Icons.Default.ChevronRight,null,tint=muted)}}
     Text("Quick, simple and transparent. Services shown in preview mode do not process real payments.",fontSize=11.sp,color=muted)
    }else if(page=="service"){
     Text(serviceLabel,fontSize=25.sp,fontWeight=FontWeight.ExtraBold,color=ink)
     Text("Enter the details carefully and review before confirming.",fontSize=13.sp,color=muted)
     val options=when(serviceKey){"mobile_recharge","postpaid"->listOf("Jio","Airtel","Vi (Vodafone Idea)","BSNL","MTNL");"dth_recharge"->listOf("Tata Play","Airtel Digital TV","Dish TV","d2h","Sun Direct");"electricity"->listOf("APDCL (Assam)","BSES Rajdhani","BSES Yamuna","Tata Power","BESCOM","MSEDCL","TANGEDCO","WBSEDCL","UPPCL","PSPCL","DHBVN","UHBVN","Other / Search biller");"gas"->listOf("Indane","Bharatgas","HP Gas","Other / Search biller");"water"->listOf("Guwahati Jal Board","Delhi Jal Board","BWSSB","Other / Search biller");"broadband"->listOf("JioFiber","Airtel Xstream Fiber","BSNL Bharat Fiber","ACT Fibernet","Hathway","Other / Search biller");else->emptyList()}
     if(options.isNotEmpty()){Text("Operator / biller",fontSize=12.sp,fontWeight=FontWeight.Bold,color=ink);Box{OutlinedButton(onClick={operatorMenuOpen=true},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp)){Text(if(serviceOperator.isBlank())"Select operator / biller" else serviceOperator);Spacer(Modifier.weight(1f));Icon(Icons.Default.ExpandMore,null)};DropdownMenu(expanded=operatorMenuOpen,onDismissRequest={operatorMenuOpen=false}){options.forEach{DropdownMenuItem(text={Text(it)},onClick={serviceOperator=it;operatorMenuOpen=false})}}}}
     Field(when(serviceKey){"mobile_recharge"->"10-digit mobile number";"dth_recharge"->"DTH customer ID";"electricity"->"Consumer / account number";"gas"->"LPG / consumer number";"water"->"Consumer number";"broadband"->"Account / landline number";"postpaid"->"Postpaid mobile number";else->"Customer reference"},serviceInput,{serviceInput=it},if(serviceKey in listOf("mobile_recharge","postpaid"))KeyboardType.Phone else KeyboardType.Text)
     Field("Amount (₹)",serviceAmount,{serviceAmount=it},KeyboardType.Number)
     Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF7E6)),shape=RoundedCornerShape(14.dp)){Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Info,null,tint=Color(0xFF9A6800));Spacer(Modifier.width(9.dp));Column{Text("DEMO / TEST MODE",fontWeight=FontWeight.ExtraBold,color=Color(0xFF855900),fontSize=11.sp);Text("No money is collected and no real recharge is sent. Confirming creates a local demo receipt only.",fontSize=11.sp,color=Color(0xFF775F31))}}}
     Button(onClick={runTask{if(serviceInput.isBlank())throw IOException("Enter the customer or account number.");if(options.isNotEmpty()&&serviceOperator.isBlank())throw IOException("Select an operator or biller.");if(serviceKey in listOf("mobile_recharge","postpaid")&&!serviceInput.matches(Regex("[6-9][0-9]{9}")))throw IOException("Enter a valid 10-digit Indian mobile number.");val amount=serviceAmount.toDoubleOrNull()?:throw IOException("Enter a valid amount.");if(amount<=0||amount>100000)throw IOException("Enter an amount between ₹1 and ₹1,00,000.");if(!api.access(serviceKey)){error="KYC approval is required.";nav("kyc")}else{receipt=JSONObject().put("id","DEMO-"+UUID.randomUUID().toString().take(8).uppercase()).put("service",serviceLabel).put("operator",serviceOperator.ifBlank{"Demo service"}).put("customer",serviceInput).put("amount",String.format(java.util.Locale.ROOT,"%.2f",amount)).put("date",java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a",java.util.Locale.getDefault()).format(java.util.Date())).put("status","SUCCESS · DEMO");nav("receipt")}}},enabled=!waiting,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(14.dp)){Text(if(waiting)"Checking…" else "Continue to demo payment")}
     TextButton(onClick={back},modifier=Modifier.fillMaxWidth()){Text("Cancel",color=muted)}
    }else if(page=="receipt"){
     val t=receipt?:JSONObject()
     Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
      Box(Modifier.size(94.dp).background(Color(0xFFE2F8EE),RoundedCornerShape(50.dp)),contentAlignment=Alignment.Center){Icon(Icons.Default.CheckCircle,null,tint=Color(0xFF159B62),modifier=Modifier.size(60.dp))}
      Text("Payment successful!",fontSize=25.sp,fontWeight=FontWeight.ExtraBold,color=Color(0xFF147A50))
      Text("Demo transaction completed",fontSize=13.sp,color=muted)
      Text("₹ "+t.optString("amount","0.00"),fontSize=35.sp,fontWeight=FontWeight.Black,color=ink)
     }
     Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(20.dp)){Column(Modifier.fillMaxWidth().padding(17.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){Text("TRANSACTION RECEIPT",fontSize=11.sp,fontWeight=FontWeight.ExtraBold,color=muted);listOf("Service" to t.optString("service"),"Operator" to t.optString("operator"),"Customer reference" to t.optString("customer"),"Transaction ID" to t.optString("id"),"Date & time" to t.optString("date"),"Status" to t.optString("status")).forEach{(a,b)->Row(horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()){Text(a,fontSize=12.sp,color=muted);Text(b,fontSize=12.sp,color=ink,fontWeight=FontWeight.SemiBold)}}}}
     Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF7E6)),shape=RoundedCornerShape(14.dp)){Text("TEST RECEIPT ONLY · This demo does not represent a real bank, UPI, BBPS or operator confirmation. No money was charged.",Modifier.padding(13.dp),fontSize=11.sp,color=Color(0xFF775F31))}
     Button(onClick={page="home";history.clear();receipt=null},modifier=Modifier.fillMaxWidth().height(52.dp)){Text("Back to home")}
    }else if(page=="notifications"){
     Text("Notifications",fontSize=26.sp,fontWeight=FontWeight.ExtraBold,color=ink)
     Button(onClick={runTask{notificationItems=api.notifications();notificationCount=(0 until notificationItems.length()).count{!notificationItems.optJSONObject(it).optBoolean("is_read",false)}}},enabled=!waiting){Text("Refresh")}
     if(notificationItems.length()==0)Text("You're all caught up.",color=muted)else(0 until notificationItems.length()).forEach{i->val item=notificationItems.optJSONObject(i);Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(15.dp)){Column(Modifier.fillMaxWidth().clickable{runTask{api.markNotificationRead(item.optString("id"));notificationItems=api.notifications()}}.padding(15.dp)){Text(item.optString("title","PaySaathi update"),fontWeight=FontWeight.Bold,color=ink);Text(item.optString("message",""),fontSize=12.sp,color=muted);Text(if(item.optBoolean("is_read",false))"Read" else "Tap to mark read",fontSize=10.sp,color=blue)}}}
    }else if(page=="transactions"){
     Text("Your activity",fontSize=26.sp,fontWeight=FontWeight.ExtraBold,color=ink)
     if(receipt==null)Text("No transactions available yet. Demo receipts created during this app session will appear here.",color=muted,fontSize=13.sp)else Card(Modifier.fillMaxWidth().clickable{nav("receipt")},colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(16.dp)){Text(receipt!!.optString("service"),fontWeight=FontWeight.Bold,color=ink);Text("₹ "+receipt!!.optString("amount"),color=ink);Text(receipt!!.optString("status"),color=Color(0xFF147A50),fontSize=11.sp)}}
    }else if(page=="profile"){
     Text("Your account",fontSize=26.sp,fontWeight=FontWeight.ExtraBold,color=ink)
     Card(colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(16.dp)){Text(email,color=ink,fontWeight=FontWeight.Bold);Text("KYC status: $status",color=muted,fontSize=12.sp);TextButton(onClick={nav("kyc")}){Text("Manage KYC")};TextButton(onClick={runTask{api.signout();history.clear();page="welcome"}}){Text("Sign out",color=Color(0xFFCB3A45))}}}
    }else{
     Text("Identity verification",fontSize=25.sp,fontWeight=FontWeight.ExtraBold,color=ink)
     Text("Upload PAN and one accepted identity proof for review.",fontSize=13.sp,color=muted)
     Field("Full legal name",legalName,{legalName=it})
     Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=docType=="pan",onClick={docType="pan"},label={Text("PAN")});FilterChip(selected=docType=="identity_proof",onClick={docType="identity_proof"},label={Text("Identity proof")})}
     OutlinedButton(onClick={picker.launch(arrayOf("application/pdf","image/jpeg","image/png"))}){Icon(Icons.Default.UploadFile,null);Spacer(Modifier.width(8.dp));Text(if(picked==null)"Choose PDF / JPG / PNG" else "Document selected")}
     Button(onClick={runTask{val file=picked?:throw IOException("Choose a document first");api.upload(file,ctx,docType);picked=null}},enabled=!waiting,modifier=Modifier.fillMaxWidth()){Text("Upload securely")}
     Button(onClick={runTask{api.submitKyc(legalName);status="submitted";back()}},enabled=!waiting,modifier=Modifier.fillMaxWidth()){Text("Submit KYC")}
    }
    if(error.isNotBlank())Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFFFECEE)),shape=RoundedCornerShape(12.dp)){Text(error,Modifier.padding(12.dp),color=Color(0xFFB42332),fontSize=12.sp)}
    Spacer(Modifier.height(16.dp))
    Text("PaySaathi · Secure access · India",Modifier.fillMaxWidth(),fontSize=10.sp,color=muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
   }
  }
 }
}
@Composable fun Field(label:String,value:String,onChange:(String)->Unit,type:KeyboardType=KeyboardType.Text,secret:Boolean=false){Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(label,color=Color(0xFF34445D),fontSize=12.sp,fontWeight=FontWeight.Bold);OutlinedTextField(value,onChange,modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=type),visualTransformation=if(secret)PasswordVisualTransformation()else androidx.compose.ui.text.input.VisualTransformation.None,shape=RoundedCornerShape(13.dp),colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Color(0xFF172B4D),unfocusedTextColor=Color(0xFF172B4D),focusedBorderColor=Color(0xFF245FE5),unfocusedBorderColor=Color(0xFFD7DFEA),cursorColor=Color(0xFF245FE5),focusedContainerColor=Color.White,unfocusedContainerColor=Color.White))}}
