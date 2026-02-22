package com.safeher.app;

import android.app.AlertDialog;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class PhotoViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PHOTO_PATH = "photo_path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_viewer);

        String path = getIntent().getStringExtra(EXTRA_PHOTO_PATH);
        if (path == null) { finish(); return; }
        File file = new File(path);

        ImageView ivFull   = findViewById(R.id.ivFullPhoto);
        TextView  tvName   = findViewById(R.id.tvPhotoName);
        TextView  tvBack   = findViewById(R.id.tvBack);
        TextView  tvDelete = findViewById(R.id.tvDelete);

        ivFull.setImageBitmap(BitmapFactory.decodeFile(path));
        tvName.setText(file.getName());

        tvBack.setOnClickListener(v -> finish());

        tvDelete.setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle("Delete photo?")
                .setMessage(file.getName())
                .setPositiveButton("Delete", (d, w) -> {
                    file.delete();
                    setResult(RESULT_OK);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show()
        );
    }
}
