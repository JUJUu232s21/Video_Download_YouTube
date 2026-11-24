import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadService extends Service {

    public static final String EXTRA_URL = "extra_youtube_url";
    private static final String CHANNEL_ID = "yt_download_channel";
    private static final int NOTIFICATION_ID = 1001;

    private ExecutorService executor;
    private NotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        notificationManager = getSystemService(NotificationManager.class);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String youtubeUrl = intent != null ? intent.getStringExtra(EXTRA_URL) : null;

        Notification startNotification = buildNotification("Подготовка к загрузке...", 0, true);
        int serviceType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;
        ServiceCompat.startForeground(this, NOTIFICATION_ID, startNotification, serviceType);

        if (youtubeUrl == null || youtubeUrl.trim().isEmpty()) {
            showToast("Пустая ссылка");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        executor.execute(() -> {
            try {
                SharedPreferences prefs = SecureStorage.getPrefs(DownloadService.this);
                String cookies = prefs.getString("cookies", null);

                YouTubeExtractor.VideoResult video = YouTubeExtractor.getDirectMp4Url(youtubeUrl, cookies);
                downloadFile(video, cookies);
                showToast("Скачано в Загрузки: " + video.title + ".mp4");
            } catch (Exception e) {
                android.util.Log.e("DownloadService", "Error during download", e);
                showToast("Ошибка: " + e.getMessage());
            } finally {
                stopSelf(startId);
            }
        });

        return START_NOT_STICKY;
    }

    private void downloadFile(YouTubeExtractor.VideoResult video, String cookies) throws Exception {
        URL url = new URL(video.downloadUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", video.userAgent);
        conn.setRequestProperty("Referer", "https://www.youtube.com/");
        conn.setRequestProperty("Origin", "https://www.youtube.com");
        if (cookies != null && !cookies.isEmpty()) {
            conn.setRequestProperty("Cookie", cookies);
        }
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();

        long totalBytes = conn.getContentLengthLong();
        String fileName = video.title + ".mp4";

        OutputStream out = null;
        Uri mediaUri = null;
        File file = null;
        ContentResolver resolver = getContentResolver();
        boolean success = false;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                mediaUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (mediaUri == null) throw new Exception("Не удалось создать файл в Downloads");
                out = resolver.openOutputStream(mediaUri);
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                file = new File(dir, fileName);
                out = new FileOutputStream(file);
            }

            try (InputStream in = conn.getInputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long downloaded = 0;
                int lastProgress = -1;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;
                    if (totalBytes > 0) {
                        int progress = (int) ((downloaded * 100) / totalBytes);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            notificationManager.notify(NOTIFICATION_ID, buildNotification("Скачивание: " + video.title, progress, false)
                            );
                        }
                    }
                }
                out.flush();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaUri != null) {
                ContentValues doneValues = new ContentValues();
                doneValues.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(mediaUri, doneValues, null, null);
            }

            success = true;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {}
            }
            conn.disconnect();
            if (!success) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaUri != null) {
                    try {
                        resolver.delete(mediaUri, null, null);
                    } catch (Exception ignored) {}
                } else if (file != null && file.exists()) {
                    try {
                        file.delete();
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private Notification buildNotification(String text, int progress, boolean indeterminate) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("YouTube Downloader")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, indeterminate)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Загрузка видео",
                    NotificationManager.IMPORTANCE_LOW
            );
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void showToast(String msg) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
