package com.termux.app.terminal;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.List;

public class TermuxSessionsListViewController extends ArrayAdapter<TermuxSession> implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    final TermuxActivity mActivity;

    public TermuxSessionsListViewController(TermuxActivity activity, List<TermuxSession> sessionList) {
        super(activity.getApplicationContext(), R.layout.item_terminal_sessions_list, sessionList);
        this.mActivity = activity;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View sessionRowView = convertView;
        if (sessionRowView == null) {
            LayoutInflater inflater = mActivity.getLayoutInflater();
            sessionRowView = inflater.inflate(R.layout.item_terminal_sessions_list, parent, false);
        }

        View sessionRow = sessionRowView.findViewById(R.id.session_row);
        View statusIndicator = sessionRowView.findViewById(R.id.session_status_indicator);
        TextView sessionTitleView = sessionRowView.findViewById(R.id.session_title);
        TextView sessionDetailView = sessionRowView.findViewById(R.id.session_detail);

        TermuxSession termuxSession = getItem(position);
        TerminalSession sessionAtRow = termuxSession == null ? null : termuxSession.getTerminalSession();
        if (sessionAtRow == null) {
            sessionRow.setActivated(false);
            statusIndicator.setBackgroundResource(R.drawable.session_status_stopped);
            sessionTitleView.setText(R.string.terminal_header_local_shell);
            sessionDetailView.setText("");
            sessionRow.setContentDescription(sessionTitleView.getText());
            return sessionRowView;
        }

        boolean isCurrentSession = sessionAtRow == mActivity.getCurrentSession();
        boolean isRunning = sessionAtRow.isRunning();
        int exitStatus = sessionAtRow.getExitStatus();

        String name = sessionAtRow.mSessionName;
        String sessionTitle = sessionAtRow.getTitle();
        String displayName = TextUtils.isEmpty(name)
            ? mActivity.getString(R.string.terminal_header_local_shell) + " " + (position + 1)
            : name;
        String detail = isRunning
            ? (TextUtils.isEmpty(sessionTitle) ? mActivity.getString(R.string.terminal_session_detail_running) : sessionTitle)
            : mActivity.getString(R.string.terminal_session_detail_exited, exitStatus);

        sessionRow.setActivated(isCurrentSession);
        sessionTitleView.setText(displayName);
        sessionTitleView.setTextColor(ContextCompat.getColor(mActivity, R.color.vey_text_primary));
        sessionDetailView.setText(detail);
        sessionDetailView.setTextColor(ContextCompat.getColor(mActivity, isRunning ? R.color.vey_text_secondary :
            (exitStatus == 0 ? R.color.vey_text_muted : R.color.vey_error)));

        int statusDrawable = isRunning ? R.drawable.session_status_running :
            (exitStatus == 0 ? R.drawable.session_status_stopped : R.drawable.session_status_error);
        statusIndicator.setBackgroundResource(statusDrawable);
        sessionRow.setContentDescription(displayName + ", " + detail);
        return sessionRowView;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        TermuxSession clickedSession = getItem(position);
        if (clickedSession == null || clickedSession.getTerminalSession() == null) return;

        mActivity.getTermuxTerminalSessionClient().setCurrentSession(clickedSession.getTerminalSession());
        mActivity.getDrawer().closeDrawers();
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        TermuxSession selectedSession = getItem(position);
        if (selectedSession == null || selectedSession.getTerminalSession() == null) return true;

        mActivity.getTermuxTerminalSessionClient().renameSession(selectedSession.getTerminalSession());
        return true;
    }

}
