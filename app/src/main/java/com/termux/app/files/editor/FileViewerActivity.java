package com.termux.app.files.editor;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.*;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.termux.R;
import java.io.*;
import java.util.Locale;

/** Local-only media/font/text preview. Never uploads a file or executes scripts. */
public final class FileViewerActivity extends EditorActivity {
    private MediaPlayer audio;
    private VideoView video;
    private Bitmap bitmap;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long textOffset;
    private EditorIo.TextPage textPage;
    private EditorIo.Encoding textEncoding;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); if (file == null) return;
        button("Text editor", () -> open(TextEditorActivity.class));
        button("Hex editor", () -> open(HexEditorActivity.class));
        button("Preview as…", () -> new AlertDialog.Builder(this).setTitle("Preview as")
            .setItems(new String[]{"Image", "Audio", "Video", "Font", "Text"}, (d, w) -> preview(w)).show());
        button("Previous text page", () -> {
            if (textPage == null) return;
            if (textOffset == textEncoding.bom.length) { status.setText("First text page"); return; }
            // Locate a prior valid character boundary, rather than seek into a multibyte sequence.
            background("Reading previous text page…", () -> {
                long offset = textEncoding.bom.length;
                EditorIo.TextPage previous = null;
                while (offset < textOffset) {
                    EditorIo.TextPage page = EditorIo.readTextPage(file, textEncoding, offset, EditorIo.TEXT_PAGE_BYTES);
                    if (page.end >= textOffset) { previous = page; break; }
                    offset = page.end;
                }
                return previous;
            }, page -> { if (page != null) showText(page); });
        });
        button("Next text page", () -> { if (textPage != null && textPage.end < file.length()) text(textPage.end); });
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.matches(".*\\.(png|jpe?g|gif|webp|bmp|heic|heif|avif)$")) preview(0);
        else if (name.matches(".*\\.(mp3|wav|ogg|flac|m4a|aac|opus)$")) preview(1);
        else if (name.matches(".*\\.(mp4|mkv|webm|3gp|mov)$")) preview(2);
        else if (name.matches(".*\\.(ttf|otf|ttc)$")) preview(3);
        else preview(4);
    }
    private void open(Class<?> activity) {
        releaseMedia(); startActivityForResult(new Intent(this, activity).putExtra(EXTRA_FILE_PATH, file.getAbsolutePath()), 1);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && result == RESULT_OK) { savedOnce = true; setResult(RESULT_OK, getIntent()); }
    }
    private void preview(int kind) {
        releaseMedia(); content.removeAllViews(); textPage = null;
        if (bitmap != null) { bitmap.recycle(); bitmap = null; }
        if (!file.isFile()) { error(new IOException("Not a readable file")); return; }
        switch (kind) {
            case 0: image(); break;
            case 1: audio(); break;
            case 2: video(); break;
            case 3: font(); break;
            default:
                background("Detecting text encoding…", () -> EditorIo.detectEncoding(file), value -> { textEncoding = value; text(value.bom.length); });
        }
    }
    private void image() {
        background("Decoding image preview…", () -> {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Unsupported or invalid image");
            options.inSampleSize = 1;
            while ((long) (options.outWidth / options.inSampleSize) * (options.outHeight / options.inSampleSize) > 4 * 1024 * 1024)
                options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (decoded == null) throw new IOException("Image decoder failed");
            return new Object[]{decoded, options.inSampleSize};
        }, result -> {
            if (bitmap != null) bitmap.recycle(); bitmap = (Bitmap) result[0];
            ZoomImage image = new ZoomImage(); image.setImageBitmap(bitmap); content.addView(image, new FrameLayout.LayoutParams(-1, -1));
            status.setText("Pinch to zoom · drag to pan · double tap resets\n" + bitmap.getWidth() + " × " + bitmap.getHeight() +
                " preview; sample factor " + result[1] + ". Animated formats show the first frame.");
        });
    }
    private void audio() {
        LinearLayout form = form(); Button play = new Button(this); play.setText("Preparing audio…"); play.setEnabled(false); form.addView(play);
        SeekBar seek = new SeekBar(this); form.addView(seek); content.addView(form);
        try {
            audio = new MediaPlayer(); final MediaPlayer player = audio;
            try (FileInputStream in = new FileInputStream(file)) { player.setDataSource(in.getFD()); }
            player.setOnPreparedListener(p -> {
                play.setEnabled(true); play.setText("Play / pause"); seek.setMax(p.getDuration()); status.setText("Audio ready · " + p.getDuration() / 1000 + " s");
                play.setOnClickListener(v -> { if (p.isPlaying()) p.pause(); else p.start(); });
                seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar bar, int position, boolean user) { if (user) p.seekTo(position); }
                    public void onStartTrackingTouch(SeekBar bar) {}
                    public void onStopTrackingTouch(SeekBar bar) {}
                });
                handler.post(new Runnable() {
                    public void run() { if (audio == p) { seek.setProgress(p.getCurrentPosition()); handler.postDelayed(this, 500); } }
                });
            });
            player.setOnErrorListener((p, what, extra) -> { error(new IOException("Audio codec/playback failed (" + what + ", " + extra + ")")); return true; });
            player.prepareAsync();
        } catch (Exception e) { releaseMedia(); error(e); }
    }
    private void video() {
        video = new VideoView(this); content.addView(video, new FrameLayout.LayoutParams(-1, -1));
        MediaController controller = new MediaController(this); controller.setAnchorView(video); video.setMediaController(controller);
        video.setOnPreparedListener(player -> { status.setText("Video ready · tap for playback controls"); controller.show(); });
        video.setOnErrorListener((player, what, extra) -> { error(new IOException("Video codec/playback failed (" + what + ", " + extra + ")")); return true; });
        video.setVideoURI(Uri.fromFile(file)); video.requestFocus();
    }
    private void font() {
        background("Loading font…", () -> {
            if (file.length() > 32L * 1024 * 1024) throw new IOException("Font preview limit is 32 MiB");
            return Typeface.createFromFile(file);
        }, typeface -> {
            LinearLayout form = form(); EditText sample = input("Enter sample text");
            sample.setSingleLine(false); sample.setText("VeyTerm Aa Bb Cc 0123456789\n你好，世界。日本語 · 한글\nThe quick brown fox jumps over the lazy dog.");
            sample.setTypeface(typeface); sample.setTextSize(28); form.addView(sample);
            SeekBar size = new SeekBar(this); size.setMax(88); size.setProgress(20); form.addView(size);
            size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int value, boolean user) { sample.setTextSize(8 + value); }
                public void onStartTrackingTouch(SeekBar bar) {}
                public void onStopTrackingTouch(SeekBar bar) {}
            });
            ScrollView scroll = new ScrollView(this); scroll.addView(form); content.addView(scroll);
            status.setText("Font preview · editable sample · size 8–96 sp (not saved to font)");
        });
    }
    private void text(long start) {
        background("Reading text preview…", () -> EditorIo.readTextPage(file, textEncoding, start, EditorIo.TEXT_PAGE_BYTES), this::showText);
    }
    private void showText(EditorIo.TextPage page) {
        textOffset = page.start; textPage = page; content.removeAllViews();
        TextView text = label(EditorIo.displayText(page.text)); text.setTypeface(Typeface.MONOSPACE); text.setTextSize(14);
        text.setTextIsSelectable(true); text.setPadding(dp(12), dp(8), dp(12), dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(text); content.addView(scroll);
        status.setText("Read only · " + textEncoding + " · bytes [" + page.start + ", " + page.end + ") / " + file.length() +
            "\nUse previous/next text page for large files, or open the text editor.");
    }
    private void releaseMedia() {
        handler.removeCallbacksAndMessages(null);
        if (audio != null) { audio.release(); audio = null; }
        if (video != null) { video.stopPlayback(); video = null; }
    }
    @Override protected void onStop() {
        if (audio != null) { try { if (audio.isPlaying()) audio.pause(); } catch (IllegalStateException ignored) {} }
        if (video != null) video.pause();
        super.onStop();
    }
    @Override protected void onDestroy() {
        releaseMedia(); if (bitmap != null) { bitmap.recycle(); bitmap = null; } super.onDestroy();
    }
    private final class ZoomImage extends ImageView {
        final Matrix transform = new Matrix();
        float x, y, scale = 1;
        final ScaleGestureDetector pinch;
        final GestureDetector gestures;
        ZoomImage() {
            super(FileViewerActivity.this); setScaleType(ScaleType.MATRIX);
            pinch = new ScaleGestureDetector(FileViewerActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                public boolean onScale(ScaleGestureDetector detector) {
                    float next = Math.max(.1f, Math.min(32f, scale * detector.getScaleFactor()));
                    transform.postScale(next / scale, next / scale, detector.getFocusX(), detector.getFocusY()); scale = next; setImageMatrix(transform); return true;
                }
            });
            gestures = new GestureDetector(FileViewerActivity.this, new GestureDetector.SimpleOnGestureListener() {
                public boolean onDown(MotionEvent e) { return true; }
                public boolean onDoubleTap(MotionEvent e) { fit(); return true; }
            });
        }
        void fit() {
            if (bitmap == null || getWidth() == 0) return;
            scale = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
            transform.reset(); transform.postScale(scale, scale);
            transform.postTranslate((getWidth() - bitmap.getWidth() * scale) / 2, (getHeight() - bitmap.getHeight() * scale) / 2); setImageMatrix(transform);
        }
        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { super.onSizeChanged(w, h, oldW, oldH); fit(); }
        @Override public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event); gestures.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !pinch.isInProgress() && event.getPointerCount() == 1) {
                transform.postTranslate(event.getX() - x, event.getY() - y); setImageMatrix(transform);
            }
            x = event.getX(); y = event.getY(); return true;
        }
    }
}
