package com.example.blackoutprotocol

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class TestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple test to see if app starts
        Toast.makeText(this, "App started successfully", Toast.LENGTH_LONG).show()

        // Check Firebase
        try {
            val database = com.google.firebase.database.FirebaseDatabase.getInstance()
            Toast.makeText(this, "Firebase OK", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Firebase Error: ${e.message}", Toast.LENGTH_LONG).show()
        }

        finish()
    }
}