package com.example.moodsync

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.GoogleAuthProvider

class LoginActivity : AppCompatActivity() {

    private lateinit var btnLogin: Button
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnTogglePassword: ImageButton
    private lateinit var tvRegister: TextView
    private lateinit var progressLogin: View
    private lateinit var btnGoogleSignIn: View

    private lateinit var googleSignInClient: GoogleSignInClient

    private var tvForgotPassword: TextView? = null
    private var isPasswordVisible = false

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data: Intent? = result.data
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        
        try {
            val account = task.getResult(ApiException::class.java)!!
            firebaseAuthWithGoogle(account.idToken!!, account.photoUrl?.toString())
        } catch (e: ApiException) {
            // This is the CRITICAL diagnostic. It will show Code 10, 12500, etc.
            Toast.makeText(this, "Google Security Error: ${e.statusCode}\nCode 10 = SHA-1 Mismatch", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            if (result.resultCode == RESULT_CANCELED) {
                Toast.makeText(this, "Sign-in cancelled (Result: 0). Check SHA-1 in Google Cloud.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Login failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (FirebaseAuthHelper.isLoggedIn()) {
            openScan()
            return
        }

        setContentView(R.layout.activity_login)

        setupGoogleSignIn()
        bindViews()
        setupLogin()
        setupPasswordToggle()
        setupRegister()
        setupForgotPassword()
        setupGoogleButton()
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun bindViews() {
        btnLogin = findViewById(R.id.btnLogin)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnTogglePassword = findViewById(R.id.btnTogglePassword)
        tvRegister = findViewById(R.id.tvRegister)
        progressLogin = findViewById(R.id.progressLogin)
        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)

        tvForgotPassword = try {
            findViewById(R.id.tvForgotPassword)
        } catch (_: Exception) {
            null
        }
    }

    private fun setupLogin() {
        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isEmpty()) {
                etEmail.error = "Enter email"
                etEmail.requestFocus()
                return@setOnClickListener
            }

            if (password.isEmpty()) {
                etPassword.error = "Enter password"
                etPassword.requestFocus()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            btnLogin.text = ""
            progressLogin.visibility = View.VISIBLE

            FirebaseAuthHelper.loginUser(
                email = email,
                password = password,
                onSuccess = {
                    FirebaseHistorySyncHelper.syncLocalHistoryToCloud(
                        context = this,
                        onSuccess = {
                            runOnUiThread {
                                progressLogin.visibility = View.GONE
                                openScan()
                            }
                        },
                        onError = {
                            runOnUiThread {
                                progressLogin.visibility = View.GONE
                                openScan()
                            }
                        }
                    )
                },
                onError = { error ->
                    runOnUiThread {
                        progressLogin.visibility = View.GONE
                        btnLogin.isEnabled = true
                        btnLogin.text = "Login"

                        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                        shakeView(etEmail)

                        try {
                            shakeView(findViewById(R.id.passwordContainer))
                        } catch (_: Exception) {
                            shakeView(etPassword)
                        }
                    }
                }
            )
        }
    }

    private fun setupPasswordToggle() {
        btnTogglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible

            if (isPasswordVisible) {
                etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                btnTogglePassword.setImageResource(android.R.drawable.ic_menu_view)
            } else {
                etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                btnTogglePassword.setImageResource(android.R.drawable.ic_secure)
            }

            etPassword.setSelection(etPassword.text.length)
        }
    }

    private fun setupRegister() {
        tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun setupForgotPassword() {
        tvForgotPassword?.setOnClickListener {
            val email = etEmail.text.toString().trim()

            FirebaseAuthHelper.sendPasswordReset(
                email = email,
                onSuccess = {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Password reset email sent",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    private fun setupGoogleButton() {
        btnGoogleSignIn.setOnClickListener {
            val signInIntent = googleSignInClient.signInIntent
            googleSignInLauncher.launch(signInIntent)
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String, photoUrl: String?) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        progressLogin.visibility = View.VISIBLE
        btnLogin.isEnabled = false

        FirebaseAuthHelper.loginWithGoogle(
            credential = credential,
            photoUrl = photoUrl,
            onSuccess = {
                runOnUiThread {
                    progressLogin.visibility = View.GONE
                    openScan()
                }
            },
            onError = { error ->
                runOnUiThread {
                    progressLogin.visibility = View.GONE
                    btnLogin.isEnabled = true
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun openScan() {
        val intent = Intent(this, ScanActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun shakeView(view: View) {
        view.animate()
            .translationX(18f)
            .setDuration(60)
            .withEndAction {
                view.animate()
                    .translationX(-18f)
                    .setDuration(60)
                    .withEndAction {
                        view.animate()
                            .translationX(12f)
                            .setDuration(60)
                            .withEndAction {
                                view.animate()
                                    .translationX(-12f)
                                    .setDuration(60)
                                    .withEndAction {
                                        view.animate()
                                            .translationX(0f)
                                            .setDuration(60)
                                            .start()
                                    }
                                    .start()
                            }
                            .start()
                    }
                    .start()
            }
            .start()
    }
}