package ziad_mrx.vcd.wfdsinkapp;

import static androidx.core.app.ActivityCompat.finishAffinity;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;

public class ConfirmExitDialogFragment extends DialogFragment {
    private final String TAG = "ConfirmExitDialogFragment";
    private Context mContext;
    private Window mWindow;
    private MiracastSinkManager mSinkMgr;
    private AppCompatActivity mActivity;


    public ConfirmExitDialogFragment(Context ctxt, Window wnd, MiracastSinkManager sinkMgr, AppCompatActivity activity) {
        mWindow = wnd;
        mContext = ctxt;
        mSinkMgr = sinkMgr;
        mActivity = activity;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder adialog_builder = new AlertDialog.Builder(mContext);
        adialog_builder.setMessage(R.string.exit_dialog_content)
                .setTitle(R.string.exit_dialog_title)
                .setPositiveButton(
                        R.string.exit_dialog_yes, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Log.i(TAG, "User has confirmed the intent to exit, exiting...");
                                mSinkMgr.stopBroadcasting(); // teardown p2p group
                                mSinkMgr.stopP2pDhcpServer(); // stop p2p dhcp server
                                Log.i(TAG, "Exiting app process...");
                                finishAffinity(mActivity);
                                // force stop?
                                System.exit(0);
                            }
                        }
                )
                .setNegativeButton(
                        R.string.exit_dialog_no,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // do nothing
                                Log.i(TAG, "User has refused to quit app.");
                            }
                        }
                );

        return adialog_builder.create();
    }
}
