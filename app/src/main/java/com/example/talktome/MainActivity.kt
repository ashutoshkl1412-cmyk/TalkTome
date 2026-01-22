package com.example.talktome

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.*
import kotlin.concurrent.thread

// Suppress warnings for simple projects
@SuppressLint("SetTextI18n", "CommitPrefEdits")
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val smsDailyLimit = 3
    private val updateJsonUrl = "https://raw.githubusercontent.com/ashutoshkl1412-cmyk/TalkTome/main/update.json"
    private val formUrl = "https://docs.google.com/forms/d/e/1FAIpQLSe-5IM2.../formResponse"
    private val formFieldId = "entry.2053569083"

    lateinit var layoutLogin: View
    lateinit var layoutMainApp: View
    lateinit var layoutOtpEntry: View
    lateinit var etLoginInput: EditText
    lateinit var etOtp: EditText
    lateinit var rbMobile: RadioButton
    lateinit var rbGmail: RadioButton
    lateinit var etInput: EditText
    lateinit var tvResult: TextView
    lateinit var tvStatus: TextView
    lateinit var spinnerSource: Spinner
    lateinit var spinnerTarget: Spinner

    lateinit var sharedPreferences: SharedPreferences
    lateinit var tts: TextToSpeech
    var translator: Translator? = null
    var generatedOTP: String = ""

    val languageCodeMap = mapOf(
        "English" to TranslateLanguage.ENGLISH,
        "Hindi" to TranslateLanguage.HINDI,
        "Bengali" to TranslateLanguage.BENGALI,
        "Marathi" to TranslateLanguage.MARATHI
    )

    val languageNames = languageCodeMap.keys.toList()

    private val micLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
            etInput.setText(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("TalkTomeData", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        initViews()

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languageNames)
        spinnerSource.adapter = adapter
        spinnerTarget.adapter = adapter
        spinnerSource.setSelection(languageNames.indexOf("English"))
        spinnerTarget.setSelection(languageNames.indexOf("Hindi"))

        if (sharedPreferences.getBoolean("is_logged_in", false)) {
            showMainScreen()
        }

        checkForUpdates()

        findViewById<RadioGroup>(R.id.radioGroupAuth).setOnCheckedChangeListener { _, id ->
            if (id == R.id.rbMobile) {
                etLoginInput.hint = "Enter Mobile Number"
                etLoginInput.inputType = InputType.TYPE_CLASS_PHONE
                layoutOtpEntry.visibility = View.GONE
            } else {
                etLoginInput.hint = "Enter Gmail ID"
                etLoginInput.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                layoutOtpEntry.visibility = View.GONE
            }
        }

        findViewById<Button>(R.id.btnSendOTP).setOnClickListener { handleSendOTP() }
        findViewById<Button>(R.id.btnVerifyOTP).setOnClickListener { verifyOTP() }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            sharedPreferences.edit().clear().apply()
            finish(); startActivity(intent)
        }

        findViewById<ImageButton>(R.id.btnAbout).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("About Developer")
                .setMessage("Developed by Ashutosh\n\nVersion: 1.0\n\nThank you for using Talk Tome!")
                .setPositiveButton("OK", null)
                .show()
        }

        findViewById<Button>(R.id.btnTranslate).setOnClickListener {
            val sLang = spinnerSource.selectedItem.toString()
            val tLang = spinnerTarget.selectedItem.toString()
            val text = etInput.text.toString()
            if (text.isNotEmpty()) runTranslation(sLang, tLang, text)
        }

        findViewById<ImageButton>(R.id.btnMic).setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            try { micLauncher.launch(intent) } catch (e: Exception) {}
        }

        findViewById<Button>(R.id.btnSpeakResult).setOnClickListener {
            val tLang = spinnerTarget.selectedItem.toString()
            val localeCode = when(tLang) {
                "Hindi" -> "hi-IN"
                "Bengali" -> "bn-IN"
                "Marathi" -> "mr-IN"
                else -> "en-US"
            }
            tts.language = Locale.forLanguageTag(localeCode)
            tts.speak(tvResult.text.toString(), TextToSpeech.QUEUE_FLUSH, null, "")
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Tr", tvResult.text))
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnShare).setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type="text/plain"
                putExtra(Intent.EXTRA_TEXT, tvResult.text.toString())
            }
            startActivity(Intent.createChooser(intent, "Share via"))
        }

        findViewById<Button>(R.id.btnInstagram).setOnClickListener { openUrl("https://instagram.com") }
        findViewById<Button>(R.id.btnFacebook).setOnClickListener { openUrl("https://facebook.com") }
    }

    private fun handleSendOTP() {
        val input = etLoginInput.text.toString().trim()
        if (input.isEmpty()) return

        if (rbMobile.isChecked) {
            val usage = sharedPreferences.getInt("sms_usage", 0)
            if (usage >= smsDailyLimit) {
                AlertDialog.Builder(this)
                    .setTitle("Limit Reached")
                    .setMessage("Sorry, daily mobile limit reached. Please use Gmail.")
                    .setPositiveButton("OK") { d, _ -> rbGmail.isChecked = true; d.dismiss() }
                    .show()
            } else {
                generatedOTP = (1000..9999).random().toString()
                Toast.makeText(this, "SMS Sent: $generatedOTP", Toast.LENGTH_LONG).show()
                layoutOtpEntry.visibility = View.VISIBLE
                sharedPreferences.edit().putInt("sms_usage", usage + 1).apply()
            }
        } else {
            if (input.contains("@") && input.contains(".com")) {
                generatedOTP = (1000..9999).random().toString()
                Toast.makeText(this, "Gmail Sent: $generatedOTP", Toast.LENGTH_LONG).show()
                layoutOtpEntry.visibility = View.VISIBLE
            } else {
                Toast.makeText(this, "Invalid Gmail", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun verifyOTP() {
        if (etOtp.text.toString() == generatedOTP) {
            sharedPreferences.edit().putBoolean("is_logged_in", true).apply()
            trackUserLogin(etLoginInput.text.toString())
            showMainScreen()
        } else Toast.makeText(this, "Wrong OTP", Toast.LENGTH_SHORT).show()
    }

    private fun runTranslation(source: String, target: String, text: String) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(languageCodeMap[source] ?: TranslateLanguage.ENGLISH)
            .setTargetLanguage(languageCodeMap[target] ?: TranslateLanguage.HINDI)
            .build()

        translator?.close()
        translator = Translation.getClient(options)

        val conditions = DownloadConditions.Builder().requireWifi().build()
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "Checking offline model..."

        translator!!.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                tvStatus.text = "Translating..."
                translator!!.translate(text).addOnSuccessListener {
                    tvResult.text = it
                    tvStatus.visibility = View.GONE
                }
            }
            .addOnFailureListener {
                tvStatus.text = "Error: Need internet for first-time setup."
            }
    }

    private fun checkForUpdates() {
        thread {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(updateJsonUrl).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val jsonObject = JSONObject(response.body?.string() ?: "")
                    val latestVer = jsonObject.getInt("versionCode")

                    val pInfo = packageManager.getPackageInfo(packageName, 0)
                    val currentVer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        pInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        pInfo.versionCode
                    }

                    if (latestVer > currentVer) {
                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle("Update Available!")
                                .setMessage("A new version is ready.")
                                .setPositiveButton("Update") { _, _ -> openUrl(jsonObject.getString("url")) }
                                .setNegativeButton("Later", null)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun trackUserLogin(userData: String) {
        thread {
            try {
                val fullUrl = "$formUrl?$formFieldId=$userData"
                val client = OkHttpClient()
                val request = Request.Builder().url(fullUrl).build()
                client.newCall(request).execute()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun initViews() {
        layoutLogin = findViewById(R.id.layoutLogin)
        layoutMainApp = findViewById(R.id.layoutMainApp)
        layoutOtpEntry = findViewById(R.id.layoutOtpEntry)
        etLoginInput = findViewById(R.id.etLoginInput)
        etOtp = findViewById(R.id.etOtp)
        rbMobile = findViewById(R.id.rbMobile)
        rbGmail = findViewById(R.id.rbGmail)
        etInput = findViewById(R.id.etInput)
        tvResult = findViewById(R.id.tvResult)
        tvStatus = findViewById(R.id.tvStatus)
        spinnerSource = findViewById(R.id.spinnerSource)
        spinnerTarget = findViewById(R.id.spinnerTarget)
    }

    private fun showMainScreen() { layoutLogin.visibility = View.GONE; layoutMainApp.visibility = View.VISIBLE }
    private fun openUrl(url: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    override fun onInit(status: Int) {}
}