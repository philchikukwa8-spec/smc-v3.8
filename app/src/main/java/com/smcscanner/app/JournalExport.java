package com.smcscanner.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.smcscanner.engine.Ledger;
import com.smcscanner.engine.Manage;
import com.smcscanner.engine.SignalLog;
import com.smcscanner.engine.XlsxJournal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;

/** Builds the Excel journal from the phone's signal record and your trades, and hands it to the share sheet. */
public final class JournalExport {
    private JournalExport() {}

    public static final String MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Writes the workbook into the app cache and returns it. Call from a background thread. */
    public static File build(Context c) throws Exception {
        Properties p = new Properties();
        try (InputStream in = c.getAssets().open("journal_template.properties")) { p.load(in); }
        List<SignalLog.Record> signals = Store.loadSignals(c);
        List<Manage.Trade> trades = Store.loadTrades(c);
        File dir = new File(c.getCacheDir(), "journal");
        if (!dir.exists()) dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File out = new File(dir, "SMC-Journal-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".xlsx");
        try (InputStream tpl = c.getAssets().open("journal_template.xlsx"); FileOutputStream fo = new FileOutputStream(out)) {
            XlsxJournal.write(tpl, p, Ledger.fromSignals(signals), trades, TimeZone.getDefault(), fo);
        }
        return out;
    }

    public static Intent shareIntent(Context c, File f) {
        Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".files", f);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(MIME);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.putExtra(Intent.EXTRA_SUBJECT, "SMC Journal");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(i, "Save or open the SMC journal");
    }
}
