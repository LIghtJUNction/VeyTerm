package com.termux.app.files.remote;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Standalone saved-connection editor and remote path picker. All protocol I/O runs off the UI thread. */
public final class RemoteConnectionsActivity extends Activity {
    public static final String EXTRA_CONNECTION="remote_connection", EXTRA_PATH="remote_path";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private ConnectionStore store;
    private RemoteFiles files;
    private RemoteFiles.Connection connection;
    private RemoteFiles.Cancellation cancellation=new RemoteFiles.Cancellation();
    private LocalHttpTransferServer transfer;
    private AlertDialog sharingDialog;
    private LinearLayout page,toolbar;
    private TextView status;
    private ListView list;
    private Button choose,up,cancel;
    private String path="/";
    private int generation;
    private boolean destroyed;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); int pad=(int)(12*getResources().getDisplayMetrics().density); page.setPadding(pad,pad,pad,pad); setContentView(page);
        toolbar=new LinearLayout(this); page.addView(toolbar);
        button(toolbar,"Connections",v -> connections()); button(toolbar,"Add",v -> edit(null)); button(toolbar,"HTTP transfer",v -> transferDialog());
        status=new TextView(this); status.setTextIsSelectable(true); page.addView(status);
        LinearLayout actions=new LinearLayout(this); page.addView(actions);
        up=button(actions,"Up",v -> browse(RemoteFiles.parent(path)));
        choose=button(actions,"Choose folder",v -> finishChoice(path));
        cancel=button(actions,"Cancel I/O",v -> { generation++; cancellation.cancel(); files=null; list.setEnabled(false); status.setText("Cancelled. Select a connection to reconnect."); choose.setEnabled(false); up.setEnabled(false); });
        list=new ListView(this); page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        choose.setEnabled(false); up.setEnabled(false);
        try { store=new ConnectionStore(this); }
        catch (IOException e) { status.setText(e.getMessage()); toolbar.getChildAt(0).setEnabled(false); toolbar.getChildAt(1).setEnabled(false); return; }
        String id=getIntent().getStringExtra(EXTRA_CONNECTION);
        if (id!=null) {
            worker.execute(() -> { try { RemoteFiles.Connection c=store.load(id); runOnUiThread(() -> { if (!destroyed) open(c,getIntent().getStringExtra(EXTRA_PATH)); }); }
                catch (IOException e) { runOnUiThread(() -> { if (!destroyed) { status.setText(e.getMessage()); connections(); } }); } });
        } else connections();
    }
    private Button button(LinearLayout row,String label,View.OnClickListener listener) { Button b=new Button(this); b.setText(label); b.setOnClickListener(listener); row.addView(b,new LinearLayout.LayoutParams(0,-2,1)); return b; }
    private void connections() {
        generation++; cancellation.cancel(); files=null; choose.setEnabled(false); up.setEnabled(false);
        worker.execute(() -> {
            try {
                List<String> ids=store.ids(); List<RemoteFiles.Connection> all=new ArrayList<>();
                for (String id:ids) { try { all.add(store.load(id)); } catch (IOException e) { all.add(null); } }
                runOnUiThread(() -> {
                if (destroyed) return; list.setEnabled(true); status.setText("Saved connections — tap to connect, hold to edit or delete");
                List<String> names=new ArrayList<>(); for (RemoteFiles.Connection c:all) names.add(c==null ? "Unreadable connection — hold to remove" : c.label+" ("+c.protocol+")");
                list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,names));
                list.setOnItemClickListener((p,v,i,id) -> { if (all.get(i)!=null) open(all.get(i),"/"); else status.setText("Device key changed or record damaged. Hold to remove, then add credentials again."); });
                list.setOnItemLongClickListener((p,v,i,id) -> { RemoteFiles.Connection c=all.get(i); if (c==null) { new AlertDialog.Builder(this).setMessage("Remove this unreadable connection?").setPositiveButton("Remove",(d,n)->{ store.delete(ids.get(i)); connections(); }).setNegativeButton("Cancel",null).show(); return true; } new AlertDialog.Builder(this).setTitle(c.label)
                    .setItems(new String[]{"Edit","Delete saved connection"},(d,n) -> { if (n==0) edit(c); else { store.delete(c.id); connections(); } }).show(); return true; });
            }); } catch (IOException e) { showError(e.getMessage()); }
        });
    }
    private void open(RemoteFiles.Connection c,String initial) {
        generation++; final int version=generation; cancellation.cancel(); files=null; cancellation=new RemoteFiles.Cancellation();
        RemoteFiles.Cancellation token=cancellation; connection=c; path="/";
        String start; try { start=RemoteFiles.path(initial==null ? "/" : initial); } catch (IllegalArgumentException e) { start="/"; }
        final String initialPath=start;
        status.setText("Connecting…"); choose.setEnabled(false); up.setEnabled(false); list.setAdapter(null);
        worker.execute(() -> {
            try { RemoteFiles connected=RemoteFiles.connect(c,token);
                runOnUiThread(() -> { if (destroyed || version!=generation) { connected.close(); return; } files=connected; browse(initialPath); });
            } catch (IOException e) { showError(version,e.getMessage()); }
        });
    }
    private void browse(String next) {
        if (files==null) return;
        final RemoteFiles client=files; final int version=++generation;
        status.setText("Loading…"); choose.setEnabled(false); up.setEnabled(false); list.setEnabled(false);
        worker.execute(() -> {
            try { List<RemoteFiles.Entry> entries=client.list(next);
                entries.sort((a,b) -> a.directory!=b.directory ? (a.directory ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
                runOnUiThread(() -> {
                    if (destroyed || version!=generation) return; path=next; status.setText(connection.label+"  "+next); choose.setEnabled(true); up.setEnabled(!next.equals("/")); list.setEnabled(true);
                    List<String> names=new ArrayList<>(); for (RemoteFiles.Entry e:entries) names.add((e.directory ? "▸ " : "")+e.name+(e.symbolicLink ? " ↗" : ""));
                    list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,names)); list.setOnItemLongClickListener(null);
                    list.setOnItemClickListener((p,v,i,id) -> { RemoteFiles.Entry e=entries.get(i); if (e.directory && !e.symbolicLink) browse(e.path); else finishChoice(e.path); });
                });
            } catch (IOException e) { showError(version,e.getMessage()); runOnUiThread(() -> { if (version==generation && !destroyed) { list.setEnabled(true); choose.setEnabled(true); up.setEnabled(!path.equals("/")); } }); }
        });
    }
    private void finishChoice(String selected) {
        if (connection==null) return;
        setResult(RESULT_OK,new Intent().putExtra(EXTRA_CONNECTION,connection.id).putExtra(EXTRA_PATH,selected)); finish();
    }
    private EditText field(LinearLayout form,String hint,String value,boolean secret) {
        EditText input=new EditText(this); input.setHint(hint); input.setText(value); input.setSingleLine(true); input.setSaveEnabled(false);
        input.setInputType(secret ? InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (android.os.Build.VERSION.SDK_INT>=26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        form.addView(input); return input;
    }
    private void edit(RemoteFiles.Connection existing) {
        ScrollView scroll=new ScrollView(this); LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); scroll.addView(form);
        Spinner protocol=new Spinner(this); protocol.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,RemoteFiles.Protocol.values())); form.addView(protocol);
        if (existing!=null) protocol.setSelection(existing.protocol.ordinal());
        EditText label=field(form,"Connection name",existing==null ? "" : existing.label,false);
        EditText host=field(form,"Hostname (no URI or credentials)",existing==null ? "" : existing.host,false);
        EditText port=field(form,"Port (blank: protocol default)",existing==null ? "" : String.valueOf(existing.port),false); port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText root=field(form,"Server root (/share for SMB)",existing==null ? "/" : existing.root,false);
        EditText user=field(form,"Username",existing==null ? "" : existing.username,false);
        EditText password=field(form,"Password",existing==null ? "" : existing.password,true);
        EditText domain=field(form,"SMB domain (optional)",existing==null ? "" : existing.domain,false);
        EditText key=field(form,"SFTP host fingerprint SHA256:… (verify independently)",existing==null ? "" : existing.hostKeySha256,false);
        EditText timeout=field(form,"Timeout milliseconds (1000–120000)",existing==null ? "15000" : String.valueOf(existing.timeoutMillis),false); timeout.setInputType(InputType.TYPE_CLASS_NUMBER);
        CheckBox insecure=new CheckBox(this); insecure.setText("Allow unencrypted FTP / HTTP WebDAV (credentials and files are visible on the network)"); insecure.setChecked(existing!=null && existing.allowInsecure); form.addView(insecure);
        TextView error=new TextView(this); form.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null ? "New remote connection" : "Edit remote connection").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d -> { dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                RemoteFiles.Protocol type=(RemoteFiles.Protocol)protocol.getSelectedItem();
                int defaultPort=type==RemoteFiles.Protocol.SFTP ? 22 : type==RemoteFiles.Protocol.SMB ? 445 : type==RemoteFiles.Protocol.WEBDAV ? (insecure.isChecked() ? 80 : 443) : 21;
                RemoteFiles.Connection c=new RemoteFiles.Connection(existing==null ? UUID.randomUUID().toString() : existing.id,label.getText().toString(),type,host.getText().toString(),port.length()==0 ? defaultPort : Integer.parseInt(port.getText().toString()),root.getText().toString(),user.getText().toString(),password.getText().toString(),domain.getText().toString(),key.getText().toString(),Integer.parseInt(timeout.getText().toString()),insecure.isChecked());
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                worker.execute(() -> { try { store.save(c); runOnUiThread(() -> { if (!destroyed) { dialog.dismiss(); connections(); } }); }
                    catch (IOException e) { runOnUiThread(() -> { if (!destroyed) { error.setText(e.getMessage()); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); } }); } });
            } catch (IllegalArgumentException e) { error.setText(e instanceof NumberFormatException ? "Enter a valid port and timeout" : e.getMessage()); }
        }); });
        dialog.setOnDismissListener(d -> { password.setText(""); user.setText(""); }); dialog.show();
    }
    private void transferDialog() {
        LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        EditText folder=field(form,"Local directory to share",getFilesDir().getAbsolutePath(),false);
        CheckBox lan=new CheckBox(this); lan.setText("Explicitly allow LAN access (unencrypted HTTP; use only a trusted network)"); form.addView(lan);
        TextView info=new TextView(this); info.setTextIsSelectable(true); info.setText("Stopped. Uploads create new files only. Sharing stops when this screen leaves the foreground."); form.addView(info);
        LinearLayout buttons=new LinearLayout(this); form.addView(buttons);
        Button start=button(buttons,"Start",v -> {
            try {
                if (transfer!=null) transfer.close();
                AndroidTransferRoot root=new AndroidTransferRoot(new File(folder.getText().toString()));
                LocalHttpTransferServer server=new LocalHttpTransferServer(root,1024L*1024*1024);
                int port;
                try { port=server.start(lan.isChecked(),0); } catch (IOException e) { server.close(); throw e; }
                transfer=server;
                info.setText("Running on "+(lan.isChecked() ? "all IPv4 interfaces (use this device’s LAN IP)" : "127.0.0.1")+":"+port+"\nSend header: Authorization: Bearer "+server.token()+"\nGET /list?path=%2F\nGET /download?path=%2Fname\nPUT /upload?path=%2Fname (Content-Length required; max 1 GiB)");
                folder.setEnabled(false); lan.setEnabled(false);
            } catch (IOException e) { info.setText("Cannot start transfer server. Check the directory and access permissions."); }
        });
        button(buttons,"Stop",v -> { stopTransfer(); info.setText("Stopped"); folder.setEnabled(true); lan.setEnabled(true); });
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Local HTTP file transfer").setView(form).setNegativeButton("Close and stop",null).create();
        sharingDialog=dialog;
        dialog.setOnDismissListener(d -> { stopTransfer(); sharingDialog=null; }); dialog.show(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (transfer!=null) start.setEnabled(false);
    }
    private void stopTransfer() { if (transfer!=null) { try { transfer.close(); } catch (IOException ignored) { } transfer=null; } }
    private void showError(String message) { showError(generation,message); }
    private void showError(int version,String message) { runOnUiThread(() -> { if (!destroyed && version==generation) status.setText(message); }); }
    @Override protected void onStop() { if (sharingDialog!=null) sharingDialog.dismiss(); stopTransfer(); super.onStop(); }
    @Override protected void onDestroy() { destroyed=true; generation++; cancellation.cancel(); worker.shutdownNow(); stopTransfer(); super.onDestroy(); }
}
