package com.termux.app.terminal;

import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

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
        View actionsButton = sessionRowView.findViewById(R.id.session_actions_button);

        TermuxSession termuxSession = getItem(position);
        TerminalSession sessionAtRow = termuxSession == null ? null : termuxSession.getTerminalSession();
        if (sessionAtRow == null) {
            sessionRow.setActivated(false);
            statusIndicator.setBackgroundResource(R.drawable.session_status_stopped);
            sessionTitleView.setText(R.string.terminal_header_local_shell);
            sessionDetailView.setText("");
            sessionRow.setContentDescription(sessionTitleView.getText());
            actionsButton.setVisibility(View.INVISIBLE);
            actionsButton.setOnClickListener(null);
            return sessionRowView;
        }

        boolean isCurrentSession = sessionAtRow == mActivity.getCurrentSession();
        SessionPresentation presentation = mActivity.getSessionPresentation(sessionAtRow);

        sessionRow.setActivated(isCurrentSession);
        sessionTitleView.setText(presentation.getDisplayName());
        sessionTitleView.setTextColor(ContextCompat.getColor(mActivity, R.color.vey_text_primary));
        sessionDetailView.setText(presentation.getDetail());
        sessionDetailView.setTextColor(ContextCompat.getColor(mActivity,
            presentation.getStatus() == SessionPresentationStatus.RUNNING ? R.color.vey_text_secondary :
                (presentation.getStatus() == SessionPresentationStatus.EXITED ? R.color.vey_text_muted : R.color.vey_error)));

        int statusDrawable = presentation.getStatus() == SessionPresentationStatus.RUNNING ? R.drawable.session_status_running :
            (presentation.getStatus() == SessionPresentationStatus.EXITED ? R.drawable.session_status_stopped : R.drawable.session_status_error);
        statusIndicator.setBackgroundResource(statusDrawable);
        String description = mActivity.getString(R.string.terminal_session_accessibility,
            presentation.getDisplayName(), presentation.getDetail());
        sessionRow.setContentDescription(isCurrentSession
            ? description + ", " + mActivity.getString(R.string.terminal_current_session) : description);
        actionsButton.setVisibility(View.VISIBLE);
        actionsButton.setContentDescription(mActivity.getString(R.string.action_session_actions_for, presentation.getDisplayName()));
        ViewCompat.setTooltipText(actionsButton, mActivity.getString(R.string.action_session_actions));
        actionsButton.setOnClickListener(anchor -> {
            PopupMenu popup = new PopupMenu(new ContextThemeWrapper(mActivity, R.style.VeyTermPopupTheme), anchor);
            popup.inflate(R.menu.session_actions);
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.action_rename_session) {
                    mActivity.getTermuxTerminalSessionClient().renameSession(sessionAtRow);
                } else if (item.getItemId() == R.id.action_close_session) {
                    mActivity.confirmCloseSession(sessionAtRow);
                } else {
                    return false;
                }
                return true;
            });
            popup.show();
        });
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
