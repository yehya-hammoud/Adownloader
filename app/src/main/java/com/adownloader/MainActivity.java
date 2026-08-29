package com.adownloader;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private EditText urlInput;
    private Button goButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        goButton = findViewById(R.id.goButton);

        goButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String downloadUrl = urlInput.getText().toString().trim();

                if (downloadUrl.isEmpty()) {
                    urlInput.setError("Please enter a link!");
                } else {
                    Toast.makeText(MainActivity.this, "Downloading: " + downloadUrl, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}