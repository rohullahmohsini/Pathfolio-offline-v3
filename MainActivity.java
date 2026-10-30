package org.eba.pathpolio;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Picture;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class MainActivity extends AppCompatActivity {
    private static final int CREATE_PDF_REQUEST = 4101;
    private static final int FILE_PICKER_REQUEST = 4102;

    private WebView webView;
    private ValueCallback<Uri[]> filePickerCallback;
    private byte[] pendingPdf;
    private String pendingPdfName;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePickerCallback != null) filePickerCallback.onReceiveValue(null);
                filePickerCallback = callback;
                Intent intent;
                try {
                    intent = params.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType("image/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                }
                try {
                    startActivityForResult(intent, FILE_PICKER_REQUEST);
                } catch (ActivityNotFoundException e) {
                    filePickerCallback = null;
                    Toast.makeText(MainActivity.this, "No file picker is available", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new PdfBridge(), "PathfolioNative");
        webView.loadUrl("file:///android_asset/index.html");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack();
                else finish();
            }
        });
    }

    public final class PdfBridge {
        @JavascriptInterface
        public void savePdf(String base64, String filename) {
            byte[] bytes = decode(base64);
            if (bytes == null) return;
            runOnUiThread(() -> launchSavePicker(bytes, filename));
        }

        @JavascriptInterface
        public void sharePdf(String base64, String filename, String title) {
            byte[] bytes = decode(base64);
            if (bytes == null) return;
            runOnUiThread(() -> shareNative(bytes, filename, title));
        }

        @JavascriptInterface
        public void openPdf(String base64, String filename) {
            byte[] bytes = decode(base64);
            if (bytes == null) return;
            runOnUiThread(() -> openNative(bytes, filename));
        }

        @JavascriptInterface
        public void printPage(String jobName) {
            runOnUiThread(() -> printWebView(jobName));
        }

        @JavascriptInterface
        public void savePage(String jobName, String filename) {
            runOnUiThread(() -> createPdfFromWebView(jobName, filename));
        }

        @JavascriptInterface
        public String randomSalt() {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            return Base64.encodeToString(salt, Base64.NO_WRAP);
        }

        @JavascriptInterface
        public String pbkdf2(String password, String saltBase64, int iterations) {
            try {
                byte[] salt = Base64.decode(saltBase64, Base64.DEFAULT);
                PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
                byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).getEncoded();
                spec.clearPassword();
                return Base64.encodeToString(hash, Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String sha256(String value) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8));
                return Base64.encodeToString(hash, Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        private byte[] decode(String value) {
            try {
                return Base64.decode(value, Base64.DEFAULT);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "The PDF data could not be read", Toast.LENGTH_LONG).show());
                return null;
            }
        }
    }

    private void printWebView(String jobName) {
        PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE);
        String safeJobName = (jobName == null || jobName.trim().isEmpty())
                ? "Pathfolio Document" : jobName.trim();
        PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(safeJobName);
        manager.print(safeJobName, adapter, new PrintAttributes.Builder().build());
    }

    private void createPdfFromWebView(String jobName, String filename) {
        File directory = new File(getCacheDir(), "generated-pdf");
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, "Could not create the PDF folder", Toast.LENGTH_LONG).show();
            return;
        }
        File output = new File(directory, safeName(filename));
        try {
            Picture picture = webView.capturePicture();
            int sourceWidth = Math.max(1, picture.getWidth());
            int sourceHeight = Math.max(1, picture.getHeight());
            final int pageWidth = 595;
            final int pageHeight = 842;
            final int margin = 24;
            float scale = (pageWidth - margin * 2f) / sourceWidth;
            float sourcePerPage = (pageHeight - margin * 2f) / scale;
            int pageCount = Math.max(1, (int) Math.ceil(sourceHeight / sourcePerPage));

            PdfDocument document = new PdfDocument();
            for (int page = 0; page < pageCount; page++) {
                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                        pageWidth, pageHeight, page + 1).create();
                PdfDocument.Page pdfPage = document.startPage(info);
                Canvas canvas = pdfPage.getCanvas();
                canvas.save();
                canvas.clipRect(margin, margin, pageWidth - margin, pageHeight - margin);
                canvas.translate(margin, margin - page * sourcePerPage * scale);
                canvas.scale(scale, scale);
                picture.draw(canvas);
                canvas.restore();
                document.finishPage(pdfPage);
            }

            try (FileOutputStream stream = new FileOutputStream(output)) {
                document.writeTo(stream);
            } finally {
                document.close();
            }
            launchSavePicker(readBytes(output), filename);
        } catch (Exception e) {
            Toast.makeText(this, "Could not create the PDF", Toast.LENGTH_LONG).show();
        }
    }

    private String safeName(String name) {
        String value = name == null ? "Pathfolio.pdf" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!value.toLowerCase().endsWith(".pdf")) value += ".pdf";
        return value;
    }

    private byte[] readBytes(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private File cachePdf(byte[] bytes, String filename) throws IOException {
        File directory = new File(getCacheDir(), "pdf");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create PDF cache");
        File file = new File(directory, safeName(filename));
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(bytes);
        }
        return file;
    }

    private Uri contentUri(File file) {
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
    }

    private void launchSavePicker(byte[] bytes, String filename) {
        pendingPdf = bytes;
        pendingPdfName = safeName(filename);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, pendingPdfName);
        try {
            startActivityForResult(intent, CREATE_PDF_REQUEST);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No document app is available", Toast.LENGTH_LONG).show();
        }
    }

    private void shareNative(byte[] bytes, String filename, String title) {
        try {
            Uri uri = contentUri(cachePdf(bytes, filename));
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, title == null ? "Pathfolio PDF" : title);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share or save PDF"));
        } catch (Exception e) {
            Toast.makeText(this, "Could not share PDF", Toast.LENGTH_LONG).show();
        }
    }

    private void openNative(byte[] bytes, String filename) {
        try {
            Uri uri = contentUri(cachePdf(bytes, filename));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open PDF to print"));
        } catch (ActivityNotFoundException e) {
            launchSavePicker(bytes, filename);
            Toast.makeText(this, "Install a PDF viewer to print. You can save the PDF now.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not open PDF", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_PICKER_REQUEST) {
            if (filePickerCallback != null) {
                Uri[] result = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        result = new Uri[count];
                        for (int i = 0; i < count; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        result = new Uri[]{data.getData()};
                    }
                }
                filePickerCallback.onReceiveValue(result);
                filePickerCallback = null;
            }
            return;
        }

        if (requestCode == CREATE_PDF_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && pendingPdf != null) {
                try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
                    if (stream == null) throw new IOException("No output stream");
                    stream.write(pendingPdf);
                    Toast.makeText(this, "PDF saved successfully", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Could not save PDF", Toast.LENGTH_LONG).show();
                }
            }
            pendingPdf = null;
            pendingPdfName = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("PathfolioNative");
            webView.destroy();
        }
        super.onDestroy();
    }
}
