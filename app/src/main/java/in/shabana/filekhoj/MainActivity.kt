package com.shabana.filekhoj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FileKhojApp() }
    }
}

@Composable
private fun FileKhojApp() {
    var query by remember { mutableStateOf("") }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("शबाना", style = MaterialTheme.typography.headlineMedium)
                Text("FileKhoj", style = MaterialTheme.typography.displaySmall)
                Text("तुमचे सर्व दस्तऐवज, शोधा क्षणार्धात!")
                Text("Save once. Search anything. Find instantly.")
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search documents…") }, singleLine = true
                )
                Text("Offline-first document library starter.")
                Text(
                    "Starter UI only. Import, OCR and indexed search are planned modules.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
