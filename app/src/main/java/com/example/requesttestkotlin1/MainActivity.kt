package com.example.requesttestkotlin1

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant


class MainActivity : Activity() {

    private var serverIP = "54.208.56.133:5000" // 기본
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var gpsJob: Job? = null // GPS 작업을 관리할 Job

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startBtn: Button = findViewById(R.id.startBtn)
        val endBtn: Button = findViewById(R.id.endBtn)
        val handshakeBtn: Button = findViewById(R.id.handshakeBtn)
        val serverSetBtn: Button = findViewById(R.id.serverSetBtn)

        val editText: EditText = findViewById(R.id.editText)
        val serverIpText: TextView = findViewById(R.id.serverIpText)

        val temp = "Now: $serverIP"
        serverIpText.text = temp

        serverSetBtn.setOnClickListener {
            serverIP = editText.text.toString()
            val temp2 = "Now: $serverIP"
            serverIpText.text = temp2
        }

        var route_id = "route_id"

        handshakeBtn.setOnClickListener {
            route_id = startHandshaking().toString()
        }

        startBtn.setOnClickListener {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startGPS(route_id)
            } else {
                // 요청 권한
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            }
        }

        endBtn.setOnClickListener {
            stopGPS(route_id)
        }
    }

    private fun startHandshaking() = runBlocking {
        return@runBlocking sendStartToServer().await()
    }

    private fun sendStartToServer(): Deferred<String?> {
        val client = OkHttpClient()

        val request = Request.Builder()
            .url("http://$serverIP/data/start") // 서버 엔드포인트 URL
            .post("".toRequestBody(null))
            .build()

        // 비동기 요청
        return CoroutineScope(Dispatchers.IO).async {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()

                    try {
                        val jsonObject = responseBody?.let { JSONObject(it) }
                        val routeId = jsonObject?.getString("route_id")
                        return@async routeId // 값 반환
                    } catch (e: Exception) {
                        println("JSON 파싱 오류: ${e.message}")
                        return@async null
                    }
                } else {
                    println("서버 응답 실패: ${response.code}")
                    return@async null
                }
            } catch (e: Exception) {
                println("오류 발생: ${e.message}")
                return@async null
            }
        }
    }

    private fun startGPS(route_id: String?) {
        if (gpsJob?.isActive == true) {
            gpsJob?.cancel()
        }

        // 새 작업 시작
        gpsJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                sendLastLocation(route_id)
                delay(1000)
            }
        }
    }

    private fun stopGPS(route_id: String?): Deferred<String?> {
        gpsJob?.cancel() // 코루틴 취소
        gpsJob = null

        // 비동기 요청
        val client = OkHttpClient()
        val json = JSONObject().apply {
            put("route_id", route_id)
        }

        val body = FormBody.Builder()
            .add("route_id", json.getString("route_id"))
            .build()

        val request = Request.Builder()
            .url("http://$serverIP/data/end") // 서버 엔드포인트 URL
            .post(body)
            .build()

        return CoroutineScope(Dispatchers.IO).async {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    println(response.body?.string())
                    return@async response.body?.string()
                } else {
                    println("서버 응답 실패: ${response.code}")
                    return@async "false"
                }
            } catch (e: Exception) {
                println("오류 발생: ${e.message}")
                return@async "false"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gpsJob?.cancel() // Activity 종료 시 작업 취소
    }

    private fun sendLastLocation(route_id: String?) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            location?.let {
                val latitude = it.latitude
                val longitude = it.longitude

                // 위치 정보를 서버로 전송
                sendLocationToServer(route_id, latitude, longitude)
            } ?: run {
                Toast.makeText(this, "위치를 가져올 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendLocationToServer(route_id: String?, latitude: Double, longitude: Double): Deferred<Boolean?> {
        val client = OkHttpClient()
        val json = JSONObject().apply {
            put("route_id", route_id)
            put("timestamp", Instant.now().epochSecond)
            put("latitude", latitude)
            put("longitude", longitude)
        }

        // val body = RequestBody.create("application/json".toMediaTypeOrNull(), json.toString())
        val body = FormBody.Builder()
            .add("route_id", json.getString("route_id"))
            .add("longitude", json.getString("longitude"))
            .add("latitude", json.getString("latitude"))
            .add("timestamp", json.getString("timestamp"))
            .build()

        val request = Request.Builder()
            .url("http://$serverIP/data/gps") // 서버 엔드포인트 URL
            .post(body)
            .addHeader("Connection", "keep-alive")  // 연결 닫기
            .build()

        return CoroutineScope(Dispatchers.IO).async {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    println(response.body?.string())
                    return@async true
                } else {
                    println("서버 응답 실패: ${response.code}")
                    return@async false
                }
            } catch (e: Exception) {
                println("   ${e.message}")
                return@async false
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (!(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
}
