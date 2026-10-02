package com.example.moodsync

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale

class AccountInfoActivity : AppCompatActivity() {

    private lateinit var imgProfileTop: ImageView
    private lateinit var imgProfileLarge: ImageView

    private lateinit var etUsername: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var spinnerDay: Spinner
    private lateinit var spinnerMonth: Spinner
    private lateinit var etYear: EditText
    private lateinit var spinnerCountry: Spinner

    private lateinit var btnBack: Button
    private lateinit var btnSaveChanges: Button

    private lateinit var prefs: SharedPreferences

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    private val auth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance()
    }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            processAndUploadImage(uri)
        }
    }

    private val dayOptions = listOf("Day") + (1..31).map { it.toString() }

    private val monthOptions = listOf(
        "Month",
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )

    private val countryOptions = listOf(
        "Select Country",
        "Philippines",
        "United States",
        "Canada",
        "United Kingdom",
        "Australia",
        "Japan",
        "South Korea",
        "Singapore",
        "Malaysia",
        "Thailand",
        "Indonesia",
        "Vietnam",
        "India",
        "Germany",
        "France",
        "Italy",
        "Spain",
        "Brazil",
        "Mexico"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_info)

        prefs = getSharedPreferences("moodsync_settings", MODE_PRIVATE)

        bindViews()
        setupSpinners()
        loadSavedSettings()
        loadAccountFromCloud()
        setupButtons()
    }

    private fun bindViews() {
        imgProfileTop = findViewById(R.id.imgProfileTop)
        imgProfileLarge = findViewById(R.id.imgProfileLarge)

        etUsername = findViewById(R.id.etUsername)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        spinnerDay = findViewById(R.id.spinnerDay)
        spinnerMonth = findViewById(R.id.spinnerMonth)
        etYear = findViewById(R.id.etYear)
        spinnerCountry = findViewById(R.id.spinnerCountry)

        btnBack = findViewById(R.id.btnBack)
        btnSaveChanges = findViewById(R.id.btnSaveChanges)
    }

    private fun setupSpinners() {
        val dayAdapter = ArrayAdapter(this, R.layout.spinner_item, dayOptions)
        dayAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerDay.adapter = dayAdapter

        val monthAdapter = ArrayAdapter(this, R.layout.spinner_item, monthOptions)
        monthAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerMonth.adapter = monthAdapter

        val countryAdapter = ArrayAdapter(this, R.layout.spinner_item, countryOptions)
        countryAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerCountry.adapter = countryAdapter
    }

    private fun loadSavedSettings() {
        etUsername.setText(prefs.getString("username", ""))
        etEmail.setText(prefs.getString("email", auth.currentUser?.email ?: ""))

        etPassword.setText("")

        val savedDay = prefs.getString("birth_day", "Day") ?: "Day"
        val savedMonth = prefs.getString("birth_month", "Month") ?: "Month"
        val savedYear = prefs.getString("birth_year", "") ?: ""
        val savedCountry = prefs.getString("country", "Philippines") ?: "Philippines"

        spinnerDay.setSelection(dayOptions.indexOf(savedDay).takeIf { it >= 0 } ?: 0)
        spinnerMonth.setSelection(monthOptions.indexOf(savedMonth).takeIf { it >= 0 } ?: 0)
        spinnerCountry.setSelection(countryOptions.indexOf(savedCountry).takeIf { it >= 0 } ?: 0)

        etYear.setText(savedYear)
    }

    private fun loadAccountFromCloud() {
        val uid = FirebaseAuthHelper.currentUserId()

        if (uid == null) {
            Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        db.collection("users")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener

                val username = doc.getString("username") ?: ""
                val email = doc.getString("email") ?: auth.currentUser?.email.orEmpty()
                val day = doc.getString("birth_day") ?: "Day"
                val month = doc.getString("birth_month") ?: "Month"
                val year = doc.getString("birth_year") ?: ""
                val country = doc.getString("country") ?: "Philippines"

                etUsername.setText(username)
                etEmail.setText(email)
                etYear.setText(year)

                spinnerDay.setSelection(dayOptions.indexOf(day).takeIf { it >= 0 } ?: 0)
                spinnerMonth.setSelection(monthOptions.indexOf(month).takeIf { it >= 0 } ?: 0)
                spinnerCountry.setSelection(countryOptions.indexOf(country).takeIf { it >= 0 } ?: 0)

                val profileUrl = doc.getString("profileImageUrl")
                val base64 = doc.getString("profileImageBase64")

                if (!base64.isNullOrBlank()) {
                    ProfileImageLoader.loadFromBase64(imgProfileTop, base64)
                    ProfileImageLoader.loadFromBase64(imgProfileLarge, base64)
                } else if (!profileUrl.isNullOrBlank()) {
                    ProfileImageLoader.loadFromUrl(imgProfileTop, profileUrl)
                    ProfileImageLoader.loadFromUrl(imgProfileLarge, profileUrl)
                }

                saveLocalOnly(
                    username = username,
                    email = email,
                    selectedDay = day,
                    selectedMonth = month,
                    year = year,
                    selectedCountry = country,
                    passwordInput = ""
                )
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load cloud account", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupButtons() {
        val onProfileClick = {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        imgProfileTop.setOnClickListener { onProfileClick() }
        imgProfileLarge.setOnClickListener { onProfileClick() }

        btnBack.setOnClickListener {
            finish()
        }

        btnSaveChanges.setOnClickListener {
            saveAccountInfo()
        }
    }

    private fun saveAccountInfo() {
        val username = etUsername.text.toString().trim()
        val email = etEmail.text.toString().trim()
        val passwordInput = etPassword.text?.toString()?.trim().orEmpty()
        val selectedDay = spinnerDay.selectedItem.toString()
        val selectedMonth = spinnerMonth.selectedItem.toString()
        val year = etYear.text.toString().trim()
        val selectedCountry = spinnerCountry.selectedItem.toString()

        if (username.isEmpty()) {
            etUsername.error = "Enter username"
            etUsername.requestFocus()
            return
        }

        if (email.isEmpty()) {
            etEmail.error = "Enter email"
            etEmail.requestFocus()
            return
        }

        val uid = FirebaseAuthHelper.currentUserId()

        if (uid == null) {
            Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        btnSaveChanges.isEnabled = false

        val userData: MutableMap<String, Any> = hashMapOf(
            "username" to username,
            "email" to email,
            "birth_day" to selectedDay,
            "birth_month" to selectedMonth,
            "birth_year" to year,
            "country" to selectedCountry,
            "updatedAt" to System.currentTimeMillis()
        )

        db.collection("users")
            .document(uid)
            .set(userData, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                saveLocalOnly(
                    username = username,
                    email = email,
                    selectedDay = selectedDay,
                    selectedMonth = selectedMonth,
                    year = year,
                    selectedCountry = selectedCountry,
                    passwordInput = passwordInput
                )

                updatePasswordIfNeeded(passwordInput)

                etPassword.setText("")
                btnSaveChanges.isEnabled = true

                Toast.makeText(this, "Account information saved", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                btnSaveChanges.isEnabled = true
                Toast.makeText(
                    this,
                    it.message ?: "Failed to save account information",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun updatePasswordIfNeeded(passwordInput: String) {
        if (passwordInput.isBlank()) return

        val user = auth.currentUser ?: return

        user.updatePassword(passwordInput)
            .addOnSuccessListener {
                Toast.makeText(this, "Password updated", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    "Password update needs recent login",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun saveLocalOnly(
        username: String,
        email: String,
        selectedDay: String,
        selectedMonth: String,
        year: String,
        selectedCountry: String,
        passwordInput: String
    ) {
        val editor = prefs.edit()
            .putString("username", username)
            .putString("email", email)
            .putString("birth_day", selectedDay)
            .putString("birth_month", selectedMonth)
            .putString("birth_year", year)
            .putString("country", selectedCountry)

        if (passwordInput.isNotEmpty()) {
            editor.putString("password_hash", sha256(passwordInput))
        }

        editor.apply()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun processAndUploadImage(uri: android.net.Uri) {
        val uid = FirebaseAuthHelper.currentUserId() ?: return
        Toast.makeText(this, "Processing image...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                // 1. Load bitmap from Uri
                val inputStream = contentResolver.openInputStream(uri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap == null) throw Exception("Failed to decode image")

                // 2. Resize to 200x200 max to keep Base64 small
                val size = 200
                val width = originalBitmap.width
                val height = originalBitmap.height
                val ratio = width.toFloat() / height.toFloat()

                val newWidth: Int
                val newHeight: Int
                if (ratio > 1) {
                    newWidth = size
                    newHeight = (size / ratio).toInt()
                } else {
                    newHeight = size
                    newWidth = (size * ratio).toInt()
                }

                val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)

                // 3. Compress to JPEG
                val baos = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                val bytes = baos.toByteArray()

                // 4. Encode to Base64
                val base64String = Base64.encodeToString(bytes, Base64.DEFAULT)

                runOnUiThread {
                    saveBase64ToFirestore(base64String)
                }

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Image processing failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun saveBase64ToFirestore(base64: String) {
        val uid = FirebaseAuthHelper.currentUserId() ?: return
        db.collection("users").document(uid)
            .update("profileImageBase64", base64)
            .addOnSuccessListener {
                Toast.makeText(this, "Profile image updated", Toast.LENGTH_SHORT).show()
                ProfileImageLoader.loadFromBase64(imgProfileTop, base64)
                ProfileImageLoader.loadFromBase64(imgProfileLarge, base64)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to save image: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }
}
