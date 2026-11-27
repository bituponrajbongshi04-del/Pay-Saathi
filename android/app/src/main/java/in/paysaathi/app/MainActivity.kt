package `in`.paysaathi.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
private val Blue=Color(0xFF087FCA)
private val Cyan=Color(0xFF36C5ED)
private val Muted=Color(0xFFB9D4E7)

class MainActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{PaySaathiApp(this)}}
}
class Api(context:Context){
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
 var email by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var name by remember{mutableStateOf("")};var phone by remember{mutableStateOf("")};var code by remember{mutableStateOf("")}
 var signup by remember{mutableStateOf(false)};var waiting by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
 var status by remember{mutableStateOf("not_started")};var balance by remember{mutableStateOf("0.00")};var legalName by remember{mutableStateOf("")};var docType by remember{mutableStateOf("pan")};var picked by remember{mutableStateOf<Uri?>(null)}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){picked=it}
 fun runTask(block:suspend()->Unit){scope.launch{waiting=true;error="";try{block()}catch(e:Exception){error=e.message?: "Something went wrong"}finally{waiting=false}}}
 LaunchedEffect(page){if(page=="home"){runTask{balance=api.wallet();val k=api.kyc();status=k.optString("status","not_started");legalName=k.optString("legal_name","")}}}
 MaterialTheme(colorScheme=darkColorScheme(primary=Cyan,background=Navy,surface=Color(0xFF102E49),onSurface=Color.White)){
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Navy,Color(0xFF0A3558),Navy)))){
   Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(13.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){Surface(shape=RoundedCornerShape(14.dp),color=Blue){Text("P",Modifier.padding(horizontal=15.dp,vertical=9.dp),fontWeight=FontWeight.Black,color=Color.White,fontSize=22.sp)};Spacer(Modifier.width(10.dp));Column{Text("PaySaathi",fontSize=23.sp,fontWeight=FontWeight.Black,color=Color.White);Text("YOUR MONEY. YOUR SERVICES. ONE SAATHI.",fontSize=8.sp,color=Cyan,fontWeight=FontWeight.Bold)};Spacer(Modifier.weight(1f));if(page=="home")IconButton(onClick={runTask{api.signout();page="login"}}){Icon(Icons.Default.Logout,null,tint=Color.White)}}
    if(page=="login"||page=="signup"||page=="otp"){
     Text(if(page=="signup")"Create your account" else if(page=="otp")"Verify your email" else "Welcome back",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Color.White)
     Text(if(page=="otp")"Enter the six-digit code sent to "+email else "Secure access to your PaySaathi services",color=Muted,fontSize=12.sp)
     if(page=="signup"){Field("Full name",name,{name=it});Field("Mobile number",phone,{phone=it},KeyboardType.Phone)}
     Field("Email address",email,{email=it},KeyboardType.Email)
     if(page!="otp")Field("Password",password,{password=it},secret=true) else Field("6-digit OTP",code,{code=it},KeyboardType.Number)
     Button(onClick={runTask{when(page){"login"->{api.password(email,password);api.sendOtp(email);page="otp"};"signup"->{api.signup(name,email,password,phone);signup=true;page="otp"};else->{api.verify(email,code,if(signup)"signup" else "email");page="home"}}}},enabled=!waiting,modifier=Modifier.fillMaxWidth()){Text(if(waiting)"Please wait…" else when(page){"otp"->"Verify & continue";"signup"->"Create account";else->"Continue securely"})}
     TextButton(onClick={page=if(page=="login")"signup" else "login";signup=page=="signup"}){Text(if(page=="login")"New to PaySaathi? Create account" else "Already registered? Log in",color=Cyan)}
    }else if(page=="home"){
     Text("Good to see you",fontSize=25.sp,fontWeight=FontWeight.Bold,color=Color.White)
     Card(colors=CardDefaults.cardColors(containerColor=Blue),shape=RoundedCornerShape(22.dp)){Column(Modifier.padding(20.dp)){Text("WALLET BALANCE",fontSize=10.sp,color=Color.White.copy(.75f));Text("₹ "+balance,fontSize=30.sp,fontWeight=FontWeight.Black,color=Color.White);Text("Preview mode · Live transactions are not active",fontSize=10.sp,color=Color.White.copy(.8f))}}
     Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF153B5A)),shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(17.dp)){Text("Identity verification",fontWeight=FontWeight.Bold,color=Color.White);Text("KYC status: "+status,color=Cyan,fontSize=12.sp);TextButton(onClick={page="kyc"}){Text("Open KYC centre",color=Cyan)}}}
     Text("Your services",fontWeight=FontWeight.Bold,color=Color.White,fontSize=17.sp)
     listOf("Mobile Recharge","DTH Recharge","Electricity Bills","Money Transfer","AEPS & Assisted Banking","Payout").forEach{label->Card(Modifier.fillMaxWidth().clickable{val key=when(label){"Money Transfer"->"money_transfer";"AEPS & Assisted Banking"->"aeps";"Payout"->"payout";else->""};if(key.isEmpty())error="This service is in preview mode.";else runTask{if(api.access(key))error="KYC approved. Live provider connection is still pending." else page="kyc"}},colors=CardDefaults.cardColors(containerColor=Color(0xFF153B5A)),shape=RoundedCornerShape(15.dp)){Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.GridView,null,tint=Cyan);Spacer(Modifier.width(12.dp));Text(label,color=Color.White,fontWeight=FontWeight.SemiBold);Spacer(Modifier.weight(1f));Icon(Icons.Default.ChevronRight,null,tint=Cyan)}}}
     Text("Your account remains open while KYC is being reviewed.",fontSize=10.sp,color=Muted)
    }else{
     Text("Complete your KYC",fontSize=26.sp,fontWeight=FontWeight.Bold,color=Color.White);Text("Upload PAN and one accepted identity proof. Documents remain private until an authorised reviewer checks them.",fontSize=12.sp,color=Muted)
     Field("Full legal name",legalName,{legalName=it})
     Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=docType=="pan",onClick={docType="pan"},label={Text("PAN")});FilterChip(selected=docType=="identity_proof",onClick={docType="identity_proof"},label={Text("Identity proof")})}
     OutlinedButton(onClick={picker.launch(arrayOf("application/pdf","image/jpeg","image/png"))}){Icon(Icons.Default.UploadFile,null);Spacer(Modifier.width(8.dp));Text(if(picked==null)"Choose PDF / JPG / PNG" else "Document selected")}
     Button(onClick={runTask{val file=picked?:throw IOException("Choose a document first");api.upload(file,ctx,docType);picked=null}},enabled=!waiting,modifier=Modifier.fillMaxWidth()){Text("Upload securely")}
     Button(onClick={runTask{api.submitKyc(legalName);status="submitted";page="home"}},enabled=!waiting,modifier=Modifier.fillMaxWidth()){Text("Submit KYC for review")}
     TextButton(onClick={page="home"}){Text("Back to dashboard",color=Cyan)}
    }
    if(error.isNotBlank())Text(error,color=Color(0xFFFFB4AB),fontSize=11.sp)
    Spacer(Modifier.height(8.dp));Text("PaySaathi · Secure access · India",color=Color(0xFF8BAFC8),fontSize=10.sp)
   }
  }
 }
}
@Composable fun Field(label:String,value:String,onChange:(String)->Unit,type:KeyboardType=KeyboardType.Text,secret:Boolean=false){Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(label,color=Color(0xFFBBD8EA),fontSize=11.sp,fontWeight=FontWeight.Bold);OutlinedTextField(value,onChange,modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=type),visualTransformation=if(secret)PasswordVisualTransformation()else androidx.compose.ui.text.input.VisualTransformation.None,colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White,unfocusedTextColor=Color.White,focusedBorderColor=Cyan,unfocusedBorderColor=Color(0xFF44637B),cursorColor=Cyan))}}
