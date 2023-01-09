package org.thoughtcrime.securesms.database;


import android.content.Context;

import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.SmsMessageRecord;
import org.thoughtcrime.securesms.util.FileUtilsJW;
import org.thoughtcrime.securesms.util.StorageUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

import java.io.File;
import java.io.IOException;

public class PlaintextBackupExporter {

  private static final String FILENAME = "MollyPlaintextBackup.xml";
  private static final String ZIPFILENAME = "MollyPlaintextBackup.zip";

  public static void exportPlaintextToSd(Context context)
      throws NoExternalStorageException, IOException
  {
    exportPlaintext(context);
  }

  public static File getPlaintextExportFile() throws NoExternalStorageException {
    return new File(StorageUtil.getBackupPlaintextDirectory(), FILENAME);
  }

  private static File getPlaintextZipFile() throws NoExternalStorageException {
    return new File(StorageUtil.getBackupPlaintextDirectory(), ZIPFILENAME);
  }

  private static void exportPlaintext(Context context)
      throws NoExternalStorageException, IOException
  {
    SmsTable         database = SignalDatabase.sms();
    int              count    = database.getMessageCount();
    XmlBackup.Writer writer   = new XmlBackup.Writer(getPlaintextExportFile().getAbsolutePath(), count);

    SmsMessageRecord record;

    SmsTable.Reader  reader    = null;
    int              skip      = 0;
    int              ROW_LIMIT = 500;

    do {
      if (reader != null)
        reader.close();

      reader = database.readerFor(database.getMessages(skip, ROW_LIMIT));

      while ((record = reader.getNext()) != null) {
        XmlBackup.XmlBackupItem item =
            new XmlBackup.XmlBackupItem(0,
                                        record.getIndividualRecipient().getSmsAddress().orElse("null"),
                                        record.getIndividualRecipient().getDisplayName(context),
                                        record.getDateReceived(),
                                        MmsSmsColumns.Types.translateToSystemBaseType(record.getType()),
                                        null, record.getDisplayBody(context).toString(), null,
                                        1, record.getDeliveryStatus(),
                                        getTransportType(record));

        writer.writeItem(item);
      }

      skip += ROW_LIMIT;
    } while (reader.getCount() > 0);

    writer.close();

    if (TextSecurePreferences.isPlainBackupInZipfile(context)) {
      File test = new File(getPlaintextZipFile().getAbsolutePath());
      if (test.exists()) {
        test.delete();
      }
      FileUtilsJW.createEncryptedPlaintextZipfile(context, getPlaintextZipFile().getAbsolutePath(), getPlaintextExportFile().getAbsolutePath());
      FileUtilsJW.secureDelete(getPlaintextExportFile());
    }
  }

  private static String getTransportType(MessageRecord messageRecord) {
    String transportText = "-";
    if (messageRecord.isOutgoing() && messageRecord.isFailed()) {
      transportText = "-";
    } else if (messageRecord.isPending()) {
      transportText = "Pending";
    } else if (messageRecord.isPush()) {
      transportText = "Data";
    } else if (messageRecord.isMms()) {
      transportText = "MMS";
    } else {
      transportText = "SMS";
    }
    return transportText;
  }
}
