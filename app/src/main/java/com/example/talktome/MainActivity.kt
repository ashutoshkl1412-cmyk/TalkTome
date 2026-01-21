package com.example.talktome

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

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ==========================================
    // 1. CONFIGURATION AREA (FILL THESE LATER)
    // ==========================================
    val SMS_DAILY_LIMIT = 3

    // LINK 1: Your GitHub Update File (Raw Link)
    val UPDATE_JSON_URL = "https://raw.githubusercontent.com/YourGitHubUser/YourRepo/main/update.json"

    // LINK 2: Your Google Form Tracker Link (replace 'viewform' with 'formResponse')
    val FORM_URL = "https://docs.google.com/forms/d/e/YOUR_FORM_ID/formResponse"
    val FORM_FIELD_ID = "entry.123456789"

    // ==========================================
    // 2. VARIABLES
    // ==========================================
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

    // Language Codes
    val languageCodeMap = mapOf(
        "English" to TranslateLanguage.ENGLISH,
        "Hindi" to TranslateLanguage.HINDI,
        "Bengali" to TranslateLanguage.BENGALI,
        "Marathi" to TranslateLanguage.MARATHI
    )

    // TTS Locales
    val ttsLocaleMap = mapOf(
        "English" to Locale.ENGLISH,
        "Hindi" to Locale("hi", "IN"),
        "Bengali" to Locale("bn", "IN"),
        "Marathi" to Locale("mr", "IN")
    )
    val languageNames = languageCodeMap.keys.toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Init Data & Tools
        sharedPreferences = getSharedPreferences("TalkTomeData", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        initViews()

        // Init Spinners
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languageNames)
        spinnerSource.adapter = adapter
        spinnerTarget.adapter = adapter
        spinnerSource.setSelection(languageNames.indexOf("English"))
        spinnerTarget.setSelection(languageNames.indexOf("Hindi"))

        // CHECK 1: Auto-Login
        if (sharedPreferences.getBoolean("is_logged_in", false)) {
            showMainScreen()
        }

        // CHECK 2: Auto-Update
        checkForUpdates()

        // --- AUTHENTICATION LISTENERS ---

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

        // --- APP FEATURE LISTENERS ---

        // About Developer Button
        findViewById<ImageButton>(R.id.btnAbout).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("About Developer")
                .setMessage("Developed by Ashutosh kaushal\n\nVersion: 1.0\n\nThank you for using Talk Tome!")
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
            try { startActivityForResult(intent, 100) } catch (e: Exception) {}
        }

        findViewById<Button>(R.id.btnSpeakResult).setOnClickListener {
            val tLang = spinnerTarget.selectedItem.toString()
            tts.language = ttsLocaleMap[tLang] ?: Locale.US
            tts.speak(tvResult.text.toString(), TextToSpeech.QUEUE_FLUSH, null, "")
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Tr", tvResult.text))
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnShare).setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type="text/plain"; putExtra(Intent.EXTRA_TEXT, tvResult.text)
            }, "Share"))
        }

        findViewById<Button>(R.id.btnInstagram).setOnClickListener { openUrl("https://instagram.com") }
        findViewById<Button>(R.id.btnFacebook).setOnClickListener { openUrl("https://facebook.com") }
    }

    // ==========================================
    // 3. LOGIC FUNCTIONS
    // ==========================================

    private fun handleSendOTP() {
        val input = etLoginInput.text.toString().trim()
        if (input.isEmpty()) return

        if (rbMobile.isChecked) {
            val usage = sharedPreferences.getInt("sms_usage", 0)
            if (usage >= SMS_DAILY_LIMIT) {
                AlertDialog.Builder(this)
                    .setTitle("Limit Reached")
                    .setMessage("Mobile limit reached. Please use Gmail.")
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
            } else Toast.makeText(this, "Invalid Gmail", Toast.LENGTH_SHORT).show()
        }
    }

    private fun verifyOTP() {
        if (etOtp.text.toString() == generatedOTP) {
            // Success!
            sharedPreferences.edit().putBoolean("is_logged_in", true).apply()
            trackUserLogin(etLoginInput.text.toString()) // Track user
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
        tvStatus.text = "Checking model..."

        translator!!.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                tvStatus.text = "Translating..."
                translator!!.translate(text).addOnSuccessListener {
                    tvResult.text = it
                    tvStatus.visibility = View.GONE
                }
            }
            .addOnFailureListener {
                tvStatus.text = "Need Internet for 1st download."
            }
    }

    private fun checkForUpdates() {
        thread {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(UPDATE_JSON_URL).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val jsonObject = JSONObject(response.body?.string() ?: "")
                    if (jsonObject.getInt("versionCode") > packageManager.getPackageInfo(packageName, 0).versionCode) {
                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle("Update Available")
                                .setMessage("New version ready.")
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
                val fullUrl = "$https://docs.google.com/forms/d/e/1FAIpQLSe-5lM2ULld-JR7qVRb4_1GtVPWvOehZmnI84Yc1jw7N-uXcw/formResponse?usp=pp_url&entry.2053569083=testFORM_URL?$FORMentry.2053569083_FIELD_ID=$userData"
                OkHttpClient().newCall(Request.Builder().url(fullUrl).build()).execute()
            } catch (e: Exception) {}
        }
    }

    // Helpers
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK)
            etInput.setText(data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0))
    }
    override fun onInit(status: Int) {}
}