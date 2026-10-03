package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import java.io.File;

public final class PdfPrintHelper {

    public interface Callback {
        void onSuccess();
        void onError(String error);
    }

    private PdfPrintHelper() {}

    public static void printToPdf(
            final PrintDocumentAdapter printAdapter,
            final File outputFile,
            final Callback callback
    ) {
        final PrintAttributes printAttributes = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build();

        try {
            printAdapter.onStart();
            printAdapter.onLayout(
                    null,
                    printAttributes,
                    new CancellationSignal(),
                    new PrintDocumentAdapter.LayoutResultCallback() {
                        @Override
                        public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                            super.onLayoutFinished(info, changed);
                            try {
                                final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                                        outputFile,
                                        ParcelFileDescriptor.MODE_READ_WRITE
                                                | ParcelFileDescriptor.MODE_CREATE
                                                | ParcelFileDescriptor.MODE_TRUNCATE
                                );
                                printAdapter.onWrite(
                                        new PageRange[]{PageRange.ALL_PAGES},
                                        pfd,
                                        new CancellationSignal(),
                                        new PrintDocumentAdapter.WriteResultCallback() {
                                            @Override
                                            public void onWriteFinished(PageRange[] pages) {
                                                super.onWriteFinished(pages);
                                                try {
                                                    pfd.close();
                                                } catch (Exception ignored) {}
                                                try {
                                                    printAdapter.onFinish();
                                                } catch (Exception ignored) {}
                                                if (outputFile.exists() && outputFile.length() > 0) {
                                                    callback.onSuccess();
                                                } else {
                                                    callback.onError("Output PDF file is empty");
                                                }
                                            }

                                            @Override
                                            public void onWriteFailed(CharSequence error) {
                                                super.onWriteFailed(error);
                                                try {
                                                    pfd.close();
                                                } catch (Exception ignored) {}
                                                try {
                                                    printAdapter.onFinish();
                                                } catch (Exception ignored) {}
                                                callback.onError(error != null ? error.toString() : "Failed to write PDF");
                                            }

                                            @Override
                                            public void onWriteCancelled() {
                                                super.onWriteCancelled();
                                                try {
                                                    pfd.close();
                                                } catch (Exception ignored) {}
                                                try {
                                                    printAdapter.onFinish();
                                                } catch (Exception ignored) {}
                                                callback.onError("PDF write cancelled");
                                            }
                                        }
                                );
                            } catch (Exception e) {
                                try {
                                    printAdapter.onFinish();
                                } catch (Exception ignored) {}
                                callback.onError("Error preparing PDF file: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onLayoutFailed(CharSequence error) {
                            super.onLayoutFailed(error);
                            try {
                                printAdapter.onFinish();
                            } catch (Exception ignored) {}
                            callback.onError(error != null ? error.toString() : "Failed to layout PDF");
                        }

                        @Override
                        public void onLayoutCancelled() {
                            super.onLayoutCancelled();
                            try {
                                printAdapter.onFinish();
                            } catch (Exception ignored) {}
                            callback.onError("PDF layout cancelled");
                        }
                    },
                    null
            );
        } catch (Exception e) {
            callback.onError("Failed to start print adapter: " + e.getMessage());
        }
    }
}
