package local.usbbridge;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants Android's installer read-only access to an explicitly selected cached APK. */
public final class UpdateFileProvider extends ContentProvider {
 @Override public boolean onCreate(){return true;}
 private File file(Uri uri)throws FileNotFoundException {
  if(!uri.getAuthority().equals(getContext().getPackageName()+".updates")||uri.getPathSegments().size()!=1)throw new FileNotFoundException();
  String name=uri.getLastPathSegment();if(name==null||!name.matches("[A-Za-z0-9._-]+\\.apk"))throw new FileNotFoundException();
  try{File parent=new File(getContext().getCacheDir(),"updates").getCanonicalFile(),file=new File(parent,name).getCanonicalFile();if(!parent.equals(file.getParentFile())||!file.isFile())throw new FileNotFoundException();return file;}catch(IOException error){throw new FileNotFoundException();}
 }
 @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
 @Override public String getType(Uri uri){return "application/vnd.android.package-archive";}
 @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){try{File f=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++)row[i]=OpenableColumns.DISPLAY_NAME.equals(columns[i])?f.getName():OpenableColumns.SIZE.equals(columns[i])?f.length():null;cursor.addRow(row);return cursor;}catch(FileNotFoundException error){return null;}}
 @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
 @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException();}
 @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException();}
}
