package `in`.paysaathi.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{PaySaathiApp(this)}}
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
   val notes=release.optString("body","A newer version of PaySaathi is available.")\n   AppUpdate(tag,notes,apk.optString("browser_download_url"),notes.contains("[MANDATORY]",ignoreCase=true))
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
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){picked=it}
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
 LaunchedEffect(page){if(page=="home"){runTask{balance=api.wallet();val k=api.kyc();status=k.optString("status","not_started");legalName=k.optString("legal_name","")}}}
 BackHandler(enabled=pageHistory.isNotEmpty()){goBack()}
 MaterialTheme(colorScheme=darkColorScheme(primary=Cyan,background=Navy,surface=Color(0xFF102E49),onSurface=Color.White)){
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Navy,Color(0xFF0A3558),Navy)))){
   Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(13.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){if(pageHistory.isNotEmpty())IconButton(onClick={goBack()}){Icon(Icons.Default.ArrowBack,null,tint=Color.White)};Surface(shape=RoundedCornerShape(14.dp),color=Blue){Text("P",Modifier.padding(horizontal=15.dp,vertical=9.dp),fontWeight=FontWeight.Black,color=Color.White,fontSize=22.sp)};Spacer(Modifier.width(10.dp));Column{Text("PaySaathi",fontSize=23.sp,fontWeight=FontWeight.Black,color=Color.White);Text("YOUR MONEY. YOUR SERVICES. ONE SAATHI.",fontSize=8.sp,color=Cyan,fontWeight=FontWeight.Bold)};Spacer(Modifier.weight(1f));if(page=="home")IconButton(onClick={runTask{api.signout();pageHistory.clear();page="login"}}){Icon(Icons.Default.Logout,null,tint=Color.White)}}
    if(updateInfo!=null){Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF174A68)),shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("PaySaathi update available · v"+updateInfo!!.version,color=Color.White,fontWeight=FontWeight.Bold);Text("Get the latest features and improvements directly here.",color=Muted,fontSize=12.sp);Button(onClick={runTask{downloadedApk=api.downloadUpdate(updateInfo!!.apkUrl);updateMessage="Update downloaded. Tap Install update to continue."}},enabled=!waiting){Text(if(waiting)"Downloading…" else "Download update")};if(downloadedApk!=null)Button(onClick={installDownloaded()}){Text("Install update")};if(updateMessage.isNotBlank())Text(updateMessage,color=Cyan,fontSize=11.sp)}}}
    if(updateInfo?.mandatory==true){\n     Text("Required PaySaathi update",fontSize=27.sp,fontWeight=FontWeight.ExtraBold,color=Color.White)\n     Text("This version must be updated before you can continue using PaySaathi.",color=Muted,fontSize=13.sp)\n     Button(onClick={runTask{downloadedApk=api.downloadUpdate(updateInfo!!.apkUrl);updateMessage="Update downloaded. Install it to continue."}},enabled=!waiting,modifier=Modifier.fillMaxWidth()){Text(if(waiting)"Downloading…" else "Download required update")}\n     if(downloadedApk!=null)Button(onClick={installDownloaded()},modifier=Modifier.fillMaxWidth()){Text("Install and continue")}\n     if(updateMessage.isNotBlank())Text(updateMessage,color=Cyan,fontSize=12.sp)\n    }else if(page=="login"||page=="signup"||page=="otp"){
     Text(if(page=="signup")"Create your account" else if(page=="otp")"Verify your email" else "Welcome back",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Color.White)
     Text(if(page=="otp")"Enter the six-digit code sent to "+email else "Secure access to your PaySaathi services",color=Muted,fontSize=12.sp)
     if(page=="signup"){Field("Full name",name,{name=it});Field("Mobile number",phone,{phone=it},KeyboardType.Phone)}
     Field("Email address",email,{email=it},KeyboardType.Email)
     if(page!="otp")Field("Password",password,{password=it},secret=true) else Field("6-digit OTP",code,{code=it},KeyboardType.Number)
     Button(onClick={runTask{when(page){"login"->{api.password(email,password);api.sendOtp(email);navigate("otp")};"signup"->{api.signup(name,email,password,phone);signup=true;navigate("otp")};else->{api.verify(email,code,if(signup)"signup" else "email");pageHistory.clear();page="home"}}}},enabled=!waiting,modifier=Modifier.fillMaxWidth()){Text(if(waiting)"Please wait…" else when(page){"otp"->"Verify & continue";"signup"->"Create account";else->"Continue securely"})}
     TextButton(onClick={val target=if(page=="login")"signup" else "login";signup=target=="signup";navigate(target)}){Text(if(page=="login")"New to PaySaathi? Create account" else "Already registered? Log in",color=Cyan)}
    }else if(page=="home"){
     Text("Good to see you",fontSize=25.sp,fontWeight=FontWeight.Bold,color=Color.White)
     Card(colors=CardDefaults.cardColors(containerColor=Blue),shape=RoundedCornerShape(22.dp)){Column(Modifier.padding(20.dp)){Text("WALLET BALANCE",fontSize=10.sp,color=Color.White.copy(.75f));Text("₹ "+balance,fontSize=30.sp,fontWeight=FontWeight.Black,color=Color.White);Text("Preview mode · Live transactions are not active",fontSize=10.sp,color=Color.White.copy(.8f))}}
     Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF123451)),shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(17.dp)){Text("Identity verification",fontWeight=FontWeight.Bold,color=Color.White);Text("KYC status: "+status,color=Cyan,fontSize=12.sp);TextButton(onClick={navigate("kyc")}){Text("Open KYC centre",color=Cyan)}}}
     Text("Your services",fontWeight=FontWeight.Bold,color=Color.White,fontSize=17.sp)
     listOf("Mobile Recharge","DTH Recharge","Electricity Bills","Money Transfer","AEPS & Assisted Banking","Payout").forEach{label->Card(Modifier.fillMaxWidth().clickable{val key=when(label){"Money Transfer"->"money_transfer";"AEPS & Assisted Banking"->"aeps";"Payout"->"payout";else->""};if(key.isEmpty())error="This service is in preview mode.";else runTask{if(api.access(key))error="KYC approved. Live provider connection is still pending." else navigate("kyc")}},colors=CardDefaults.cardColors(containerColor=Color(0xFF123451)),shape=RoundedCornerShape(18.dp)){Row(Modifier.padding(17.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.GridView,null,tint=Cyan);Spacer(Modifier.width(12.dp));Text(label,color=Color.White,fontWeight=FontWeight.SemiBold);Spacer(Modifier.weight(1f));Icon(Icons.Default.ChevronRight,null,tint=Cyan)}}}
     Text("Your account remains open while KYC is being reviewed.",fontSize=10.sp,color=Muted)
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
