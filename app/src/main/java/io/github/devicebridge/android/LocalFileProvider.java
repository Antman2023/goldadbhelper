package io.github.devicebridge.android;
import android.content.*;import android.database.*;import android.net.Uri;import android.os.*;import android.provider.OpenableColumns;import android.webkit.MimeTypeMap;import java.io.*;
/** Read-only, temporary grants for completed downloads. No arbitrary-path access. */
public final class LocalFileProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    private File file(Uri uri)throws FileNotFoundException{try{File root=new File(getContext().getCacheDir(),"exports").getCanonicalFile();File f=new File(root,uri.getLastPathSegment()).getCanonicalFile();if(!f.getParentFile().equals(root)||!f.isFile())throw new FileNotFoundException();return f;}catch(IOException e){throw new FileNotFoundException("File unavailable");}}
    public String getType(Uri u){String n=u.getLastPathSegment(),ext=n.contains(".")?n.substring(n.lastIndexOf('.')+1).toLowerCase():"";String t=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);return t==null?"application/octet-stream":t;}
    public Cursor query(Uri u,String[] projection,String selection,String[] args,String sort){try{File f=file(u);MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{f.getName(),f.length()});return c;}catch(IOException e){return null;}}
    public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Read-only provider");return ParcelFileDescriptor.open(file(u),ParcelFileDescriptor.MODE_READ_ONLY);}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
