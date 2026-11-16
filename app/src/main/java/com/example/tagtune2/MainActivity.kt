package com.example.tagtune2

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.view.Gravity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val textView = TextView(this)
        textView.text = "TagTune2: Движок запущен!"
        textView.gravity = Gravity.CENTER
        
        setContentView(textView)
    }
}
