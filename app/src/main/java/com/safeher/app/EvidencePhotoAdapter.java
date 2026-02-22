package com.safeher.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EvidencePhotoAdapter extends RecyclerView.Adapter<EvidencePhotoAdapter.VH> {

    public interface OnPhotoClickListener {
        void onPhotoClick(File file);
    }

    private final List<File>          photos;
    private final OnPhotoClickListener listener;
    private final ExecutorService     executor = Executors.newFixedThreadPool(3);
    private final Handler             mainHandler = new Handler(Looper.getMainLooper());

    public EvidencePhotoAdapter(List<File> photos, OnPhotoClickListener listener) {
        this.photos   = photos;
        this.listener = listener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_evidence_photo, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        File file = photos.get(position);

        // Reset to placeholder while loading
        holder.ivThumb.setImageResource(android.R.drawable.ic_menu_camera);

        // Decode thumbnail off the main thread
        executor.execute(() -> {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 4; // quarter resolution = fast load
            Bitmap bm = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            mainHandler.post(() -> {
                if (holder.getBindingAdapterPosition() == position && bm != null) {
                    holder.ivThumb.setImageBitmap(bm);
                }
            });
        });

        // Format timestamp label from filename: CAM_20240101_120000_3.jpg
        holder.tvTime.setText(formatName(file.getName()));

        holder.itemView.setOnClickListener(v -> listener.onPhotoClick(file));
    }

    @Override public int getItemCount() { return photos.size(); }

    private String formatName(String name) {
        // Try to parse yyyyMMdd_HHmmss from filename
        try {
            // filename: CAM_20240101_120000_3.jpg  →  strip prefix & suffix
            String part = name.replace("CAM_", "").replaceAll("_\\d+\\.jpg$", "");
            Date d = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(part);
            if (d != null)
                return new SimpleDateFormat("dd MMM  HH:mm:ss", Locale.US).format(d);
        } catch (Exception ignored) {}
        return name;
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView  tvTime;
        VH(View v) {
            super(v);
            ivThumb = v.findViewById(R.id.ivThumb);
            tvTime  = v.findViewById(R.id.tvTime);
        }
    }
}
